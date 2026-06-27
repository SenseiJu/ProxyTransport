package org.nethergames.proxytransport;

import dev.waterdog.waterdogpe.network.protocol.ProtocolCodecs;
import dev.waterdog.waterdogpe.plugin.Plugin;
import org.nethergames.proxytransport.integration.QuicTransportServerInfo;
import org.nethergames.proxytransport.integration.TcpTransportServerInfo;
import org.nethergames.proxytransport.utils.CodecUpdater;
import org.nethergames.proxytransport.utils.QuicLibraryInstaller;

public class ProxyTransport extends Plugin {

    @Override
    public void onStartup() {
        ProtocolCodecs.addUpdater(new CodecUpdater());

        getLogger().info("ProxyTransport was started.");

        // Inject the QUIC native onto netty's classloader before any QUIC type is referenced, and only register
        // the QUIC type if that succeeded.
        if (QuicLibraryInstaller.tryInstall(getLogger())) {
            registerQuicType();
        }

        getLogger().info("Registered type with name {}", TcpTransportServerInfo.TYPE.getIdentifier());
    }

    // Separate method so the QUIC classes are only loaded when QUIC is available.
    private void registerQuicType() {
        getLogger().info("Registered type with name {}", QuicTransportServerInfo.TYPE.getIdentifier());
    }

    @Override
    public void onEnable() {
        getLogger().info("ProxyTransport was enabled.");
    }
}