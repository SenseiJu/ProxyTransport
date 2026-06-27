package org.nethergames.proxytransport.utils;

import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Adds the embedded QUIC jars (and netty-handler, which WaterdogPE does not bundle) to netty-common's
 * classloader at runtime.
 * <p>
 * Netty loads the QUIC native through {@code netty-common} and resolves the native's JNI classes via the
 * classloader that loaded {@code netty-common} - on WaterdogPE, the system classloader. Shading QUIC into the
 * plugin's child classloader therefore fails the native load, so we inject the jars onto that classloader
 * instead; the plugin then reaches them by parent-first delegation.
 * <p>
 * The reflective injection needs an {@code --add-opens} on Java 17+: {@code java.base/jdk.internal.loader} for
 * the system classloader, or {@code java.base/java.net} for a URLClassLoader.
 */
public final class QuicLibraryInstaller {
    private static final String PROBE_CLASS = "io.netty.incubator.codec.quic.QuicSslContextBuilder";
    private static final String[] EMBEDDED_JARS = {
        // netty-handler first: the QUIC classes need io.netty.handler.ssl.SslContext, which WaterdogPE lacks.
        "/quic-libs/netty-handler.jar",
        "/quic-libs/netty-incubator-codec-classes-quic.jar",
        "/quic-libs/netty-incubator-codec-native-quic.jar",
    };
    private static final String[] NETTY_COMMON_PROBES = {
        "io.netty.util.internal.NativeLibraryLoader",
        "io.netty.util.internal.NativeLibraryUtil",
        "io.netty.util.internal.PlatformDependent",
    };

    private QuicLibraryInstaller() {
    }

    public static final class InstallException extends Exception {
        public InstallException(String message) {
            super(message);
        }

        public InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Ensures the QUIC libraries are present on netty-common's classloader. Call before referencing any QUIC class. */
    public static synchronized void install() throws InstallException {
        ClassLoader nettyClassLoader = nettyCommonClassLoader();
        if (isQuicAvailable(nettyClassLoader)) {
            return;
        }

        Path stagingDir = Path.of(System.getProperty("java.io.tmpdir"), "proxytransport-quic-libs");
        try {
            Files.createDirectories(stagingDir);
        } catch (Exception e) {
            throw new InstallException("Failed to create staging directory " + stagingDir, e);
        }

        for (String resource : EMBEDDED_JARS) {
            appendToClassLoader(nettyClassLoader, extract(resource, stagingDir));
        }

        if (!isQuicAvailable(nettyClassLoader)) {
            throw new InstallException("QUIC classes not visible after injection (missing --add-opens flag?).");
        }
    }

    /** Installs QUIC, logging (not throwing) on failure so it never aborts plugin startup. */
    public static boolean tryInstall(Logger logger) {
        try {
            install();
            return true;
        } catch (InstallException e) {
            logger.warn("Could not load the QUIC native; QUIC is unavailable (TCP unaffected). " + e.getMessage(), e);
            return false;
        }
    }

    private static void appendToClassLoader(ClassLoader loader, Path jar) throws InstallException {
        // A URLClassLoader takes addURL; the system classloader takes appendClassPath. Each needs a different
        // --add-opens to reflect into.
        if (loader instanceof URLClassLoader urlClassLoader) {
            Method addUrl = accessible(() -> URLClassLoader.class.getDeclaredMethod("addURL", URL.class),
                "java.base/java.net");
            invoke(() -> addUrl.invoke(urlClassLoader, jar.toUri().toURL()), jar, loader);
            return;
        }

        Method append = findAppendMethod(loader.getClass());
        if (append == null) {
            throw new InstallException("Classloader " + loader.getClass().getName() +
                " is not a URLClassLoader and has no classpath-append method.");
        }
        try {
            append.setAccessible(true);
        } catch (InaccessibleObjectException e) {
            throw missingOpens("java.base/jdk.internal.loader", e);
        }
        invoke(() -> append.invoke(loader, jar.toAbsolutePath().toString()), jar, loader);
    }

    private interface MethodSupplier {
        Method get() throws NoSuchMethodException;
    }

    private interface Invocation {
        void run() throws Exception;
    }

    private static Method accessible(MethodSupplier supplier, String module) throws InstallException {
        try {
            Method method = supplier.get();
            method.setAccessible(true);
            return method;
        } catch (InaccessibleObjectException e) {
            throw missingOpens(module, e);
        } catch (NoSuchMethodException e) {
            throw new InstallException("Expected method not found on this JVM.", e);
        }
    }

    private static void invoke(Invocation invocation, Path jar, ClassLoader loader) throws InstallException {
        try {
            invocation.run();
        } catch (Exception e) {
            throw new InstallException("Failed to add " + jar + " to " + loader, e);
        }
    }

    private static InstallException missingOpens(String module, Throwable cause) {
        return new InstallException("Add this JVM flag and restart: --add-opens " + module + "=ALL-UNNAMED", cause);
    }

    private static Method findAppendMethod(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : new String[]{"appendToClassPathForInstrumentation", "appendClassPath"}) {
                try {
                    return current.getDeclaredMethod(name, String.class);
                } catch (NoSuchMethodException ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    private static ClassLoader nettyCommonClassLoader() throws InstallException {
        for (String probe : NETTY_COMMON_PROBES) {
            try {
                ClassLoader loader = Class.forName(probe, false, QuicLibraryInstaller.class.getClassLoader()).getClassLoader();
                if (loader != null) {
                    return loader;
                }
            } catch (Throwable ignored) {
                // try the next probe
            }
        }
        throw new InstallException("Could not locate netty-common's classloader.");
    }

    private static boolean isQuicAvailable(ClassLoader classLoader) {
        try {
            Class.forName(PROBE_CLASS, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Path extract(String resource, Path dir) throws InstallException {
        Path target = dir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
        try (InputStream in = QuicLibraryInstaller.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new InstallException("Embedded library " + resource + " is missing from the plugin jar.");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            throw new InstallException("Failed to extract " + resource, e);
        }
        return target;
    }
}