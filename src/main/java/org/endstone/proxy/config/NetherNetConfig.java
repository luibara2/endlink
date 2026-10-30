package org.endstone.proxy.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public record NetherNetConfig(
        boolean enabled,
        String signalingHost,
        int signalingPort,
        String iceHost,
        int icePort,
        Path identityFile,
        String identityDomain,
        List<String> advertisedAddresses,
        Path httpsCertificate,
        Path httpsPrivateKey,
        boolean requireTls,
        int maxPendingJoins
) {
    public static final int ICE_PORT_OFFSET = 8;
    public static final String DEFAULT_IDENTITY_FILE = "nethernet-identity.pem";
    public static final int DEFAULT_MAX_PENDING_JOINS = 64;

    public NetherNetConfig {
        if (signalingPort < 1 || signalingPort > 65535) {
            throw new IllegalArgumentException("nethernet.port must be between 1 and 65535");
        }
        if (icePort < 1 || icePort > 65535) {
            throw new IllegalArgumentException("nethernet.icePort must be between 1 and 65535");
        }
        if (enabled && identityFile == null) {
            throw new IllegalArgumentException("nethernet.identityFile cannot be empty");
        }
        if ((httpsCertificate == null) != (httpsPrivateKey == null)) {
            throw new IllegalArgumentException("nethernet.https.certificate and nethernet.https.privateKey go together");
        }
        if (maxPendingJoins < 1) {
            throw new IllegalArgumentException("nethernet.maxPendingJoins must be positive");
        }
        advertisedAddresses = List.copyOf(advertisedAddresses == null ? List.of() : advertisedAddresses);
    }

    public boolean https() {
        return httpsCertificate != null;
    }

    public static NetherNetConfig disabled(String listenHost, int listenPort) {
        return new NetherNetConfig(false, listenHost, listenPort, listenHost, defaultIcePort(listenPort),
                null, "", List.of(), null, null, false, DEFAULT_MAX_PENDING_JOINS);
    }

    static int defaultIcePort(int listenPort) {
        int port = listenPort + ICE_PORT_OFFSET;
        return port > 65535 ? listenPort - ICE_PORT_OFFSET : port;
    }

    public static NetherNetConfig from(Properties properties, Path configDir, String listenHost, int listenPort, String motd) {
        boolean enabled = bool(properties, "nethernet.enabled", true);
        int signalingPort = integer(properties, "nethernet.port", 0);
        int icePort = integer(properties, "nethernet.icePort", 0);
        String identityFile = value(properties, "nethernet.identityFile", DEFAULT_IDENTITY_FILE);
        String certificate = value(properties, "nethernet.https.certificate", "");
        String privateKey = value(properties, "nethernet.https.privateKey", "");
        return new NetherNetConfig(
                enabled,
                value(properties, "nethernet.host", listenHost),
                signalingPort == 0 ? listenPort : signalingPort,
                value(properties, "nethernet.iceHost", listenHost),
                icePort == 0 ? defaultIcePort(listenPort) : icePort,
                identityFile.isEmpty() ? null : resolve(configDir, identityFile),
                value(properties, "nethernet.identityDomain", motd),
                Arrays.stream(value(properties, "nethernet.advertisedAddresses", "").split(","))
                        .map(String::trim)
                        .filter(entry -> !entry.isEmpty())
                        .toList(),
                certificate.isEmpty() ? null : resolve(configDir, certificate),
                privateKey.isEmpty() ? null : resolve(configDir, privateKey),
                bool(properties, "nethernet.https.requireTls", false),
                integer(properties, "nethernet.maxPendingJoins", DEFAULT_MAX_PENDING_JOINS)
        );
    }

    private static Path resolve(Path configDir, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() && configDir != null) {
            path = configDir.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        String stripped = ConfigValues.stripInlineComment(value).trim();
        return stripped.isEmpty() && !fallback.isEmpty() ? fallback : stripped;
    }

    private static int integer(Properties properties, String key, int fallback) {
        String value = value(properties, key, "");
        return value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = value(properties, key, "");
        return value.isEmpty() ? fallback : Boolean.parseBoolean(value);
    }
}
