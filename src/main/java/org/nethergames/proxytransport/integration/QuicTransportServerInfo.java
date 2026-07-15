package org.nethergames.proxytransport.integration;

import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoType;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import net.jodah.expiringmap.internal.NamedThreadFactory;
import org.nethergames.proxytransport.impl.TransportChannelInitializer;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class QuicTransportServerInfo extends ServerInfo {
    public static final int availableCPU = Runtime.getRuntime().availableProcessors();
    public static final ThreadFactory downstreamThreadFactory = new NamedThreadFactory("QUIC-Downstream %s");
    public static final EventLoopGroup downstreamLoopGroup = Epoll.isAvailable() ? new EpollEventLoopGroup(availableCPU, downstreamThreadFactory) : new NioEventLoopGroup(availableCPU, downstreamThreadFactory);

    public static final String TYPE_IDENT = "quic";
    public static final ServerInfoType TYPE = ServerInfoType.builder()
            .identifier(TYPE_IDENT)
            .serverInfoFactory(QuicTransportServerInfo::new)
            .register();


    private static final long MAX_DATA = 64L * 1024L * 1024L;
    private static final int IDLE_TIMEOUT_MILLIS = 30_000;

    // Failure-eviction: a shared connection whose streams keep dying during the downstream handshake
    // (flow-control wedged, downstream restarted, etc.) must stop being reused, or every new player
    // inherits the wedge until the proxy is rebooted. A stream that lives past SHORT_LIVED_NANOS is
    // treated as a successful handshake and resets the streak.
    private static final long SHORT_LIVED_NANOS = 20_000L * 1_000_000L; // 20s
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final AttributeKey<AtomicInteger> CONSECUTIVE_FAILURES =
            AttributeKey.valueOf("proxytransport-consecutive-failures");

    private final ConcurrentHashMap<InetSocketAddress, Future<QuicChannel>> serverConnections = new ConcurrentHashMap<>();

    public QuicTransportServerInfo(String serverName, InetSocketAddress address, InetSocketAddress publicAddress) {
        super(serverName, address, publicAddress);
    }

    @Override
    public ServerInfoType getServerType() {
        return TYPE;
    }

    @Override
    public Future<ClientConnection> createConnection(ProxiedPlayer proxiedPlayer) {
        EventLoop eventLoop = proxiedPlayer.getProxy().getWorkerEventLoopGroup().next();
        Promise<ClientConnection> promise = eventLoop.newPromise();

        final InetSocketAddress target = resolve(this.getAddress());
        final Future<QuicChannel> connectionFuture = this.createServerConnection(eventLoop, proxiedPlayer.getLogger(), this.getAddress());
        connectionFuture.addListener((Future<QuicChannel> future) -> {
            if (future.isSuccess()) {
                proxiedPlayer.getLogger().debug("Creating stream for " + this.getServerName() + " server");
                QuicChannel quicChannel = future.getNow();

                quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new TransportChannelInitializer(proxiedPlayer, this, promise)).addListener((Future<QuicStreamChannel> streamFuture) -> {
                    if (streamFuture.isSuccess()) {
                        this.trackStreamHealth(streamFuture.getNow(), quicChannel, target, connectionFuture, proxiedPlayer.getLogger());
                    } else {
                        promise.tryFailure(streamFuture.cause());
                        // Could not even open a stream: stop reusing this connection.
                        this.evictConnection(target, connectionFuture, proxiedPlayer.getLogger());
                    }
                });
            } else {
                promise.tryFailure(future.cause());
            }
        });

        return promise;
    }

    /**
     * Watches a freshly-opened stream: if streams keep dying during the downstream handshake
     * (short-lived), the shared connection is wedged, so evict it from the cache and let the next
     * player build a fresh one. A stream that lives past {@link #SHORT_LIVED_NANOS} counts as a
     * healthy handshake and resets the consecutive-failure streak for that connection.
     */
    private void trackStreamHealth(QuicStreamChannel stream, QuicChannel quicChannel, InetSocketAddress target,
                                   Future<QuicChannel> connectionFuture, MainLogger logger) {
        final long openedAt = System.nanoTime();

        Attribute<AtomicInteger> attribute = quicChannel.attr(CONSECUTIVE_FAILURES);
        AtomicInteger current = attribute.get();
        if (current == null) {
            current = new AtomicInteger();
            AtomicInteger existing = attribute.setIfAbsent(current);
            if (existing != null) {
                current = existing;
            }
        }
        final AtomicInteger failures = current;

        stream.closeFuture().addListener(f -> {
            if (System.nanoTime() - openedAt < SHORT_LIVED_NANOS) {
                int count = failures.incrementAndGet();
                if (count >= MAX_CONSECUTIVE_FAILURES) {
                    logger.warning("Evicting QUIC connection to " + target + " for " + this.getServerName()
                            + " server after " + count + " consecutive failed handshakes");
                    this.evictConnection(target, connectionFuture, logger);
                }
            } else {
                failures.set(0);
            }
        });
    }

    private void evictConnection(InetSocketAddress target, Future<QuicChannel> connectionFuture, MainLogger logger) {
        if (this.serverConnections.remove(target, connectionFuture)) {
            logger.debug("Discarded cached connection to " + target + " for " + this.getServerName() + " server");
        }
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        // QUIC needs a resolved address; unlike TCP it won't resolve a hostname and NPEs on a null InetAddress.
        return address.isUnresolved()
                ? new InetSocketAddress(address.getHostString(), address.getPort())
                : address;
    }

    private Future<QuicChannel> createServerConnection(EventLoopGroup eventLoopGroup, MainLogger logger, InetSocketAddress address) {
        EventLoop eventLoop = eventLoopGroup.next();

        final InetSocketAddress target = resolve(address);

        Future<QuicChannel> existing = this.serverConnections.get(target);
        if (existing != null) {
            if (isUsable(existing)) {
                logger.info("Reusing connection to " + target + " for " + this.getServerName() + " server");
                return existing;
            }

            logger.debug("Discarding stale connection to " + target + " for " + this.getServerName() + " server");
            this.serverConnections.remove(target, existing);
        }

        logger.info("Creating connection to " + target + " for " + this.getServerName() + " server");
        Promise<QuicChannel> promise = eventLoop.newPromise();
        this.serverConnections.put(target, promise);

        QuicSslContext sslContext = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).applicationProtocols("ng").build();
        ChannelHandler codec = new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .initialMaxData(MAX_DATA)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .maxRecvUdpPayloadSize(1350)
                .maxSendUdpPayloadSize(1350)
                .activeMigration(false)
                .build();

        new Bootstrap()
                .group(downstreamLoopGroup)
                .handler(codec)
                .channel(getProperSocketChannel())
                .bind(0).addListener((ChannelFuture channelFuture) -> {
                    if (channelFuture.isSuccess()) {
                        QuicChannel.newBootstrap(channelFuture.channel())
                                .streamHandler(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                        ctx.close();
                                    }
                                })
                                .remoteAddress(target)
                                .connect().addListener((Future<QuicChannel> quicChannelFuture) -> {
                                    if (quicChannelFuture.isSuccess()) {
                                        logger.debug("Connection to " + target + " for " + this.getServerName() + " server established");

                                        QuicChannel quicChannel = quicChannelFuture.getNow();
                                        quicChannel.closeFuture().addListener(f -> {
                                            logger.debug("Connection to " + target + " for " + this.getServerName() + " server closed");
                                            channelFuture.channel().close();
                                            this.serverConnections.remove(target, promise);
                                        });

                                        promise.trySuccess(quicChannel);
                                    } else {
                                        logger.warning("Connection to " + target + " for " + this.getServerName() + " server failed");

                                        promise.tryFailure(quicChannelFuture.cause());
                                        channelFuture.channel().close();
                                        this.serverConnections.remove(target, promise);
                                    }
                                });
                    } else {
                        promise.tryFailure(channelFuture.cause());
                        channelFuture.channel().close();
                        this.serverConnections.remove(target, promise);
                    }
                });

        return promise;
    }

    private boolean isUsable(Future<QuicChannel> future) {
        if (!future.isDone()) {
            return true;
        }
        if (!future.isSuccess()) {
            return false;
        }
        QuicChannel channel = future.getNow();
        return channel != null && channel.isActive();
    }

    public Class<? extends DatagramChannel> getProperSocketChannel() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}
