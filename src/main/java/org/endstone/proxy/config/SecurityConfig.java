package org.endstone.proxy.config;

import java.util.Properties;

/**
 * Limits that keep one client — or one host pretending to be many — from costing everyone else
 * their session.
 *
 * @param rateLimitEnabled            keep RakNet's per-address packet rate limiter installed. The
 *                                    proxy used to remove it outright, which left the unconnected
 *                                    ping path (a classic UDP amplification target) wide open
 * @param packetLimit                 RakNet datagrams accepted per address per tick before that
 *                                    address is blocked. RakNet's own default is 120, which is
 *                                    roughly two orders of magnitude above what a playing client
 *                                    sends. 0 uninstalls the limiter entirely — it cannot mean
 *                                    "unlimited", because the limiter blocks an address the moment
 *                                    its count <em>exceeds</em> the limit, and zero is exceeded by
 *                                    the first datagram anyone sends
 * @param globalPacketLimit           datagrams accepted across all addresses per tick. Shares a
 *                                    handler with {@code packetLimit}, so a zero packet limit
 *                                    disables this too
 * @param sendConnectionCookie        make the RakNet handshake echo a server-chosen cookie, so a
 *                                    client has to be able to receive at the address it claims.
 *                                    Turns a spoofed source IP from a way to open sessions into a
 *                                    dead end. Every Bedrock client since 1.20.60 supports it and
 *                                    BDS enables it by default; this RakNet build does not
 * @param maxConnectionsPerAddress    concurrent RakNet sessions from one IP. Set this above 1: phones
 *                                    and consoles behind one home NAT share an address, as does a
 *                                    whole school or office
 * @param maxConnectionAttempts       new sessions one IP may open within {@code connectionAttemptWindowMillis}
 * @param connectionAttemptWindowMillis the window the attempt count is measured over
 * @param requireXuid                 refuse a login whose Mojang-signed chain carries no XUID.
 *                                    Without it every such player collapses onto the same identity
 *                                    downstream, and the duplicate-login check cannot tell them apart
 * @param commandCooldownMillis       minimum gap between one player's proxy commands. Mostly this
 *                                    stops {@code /server} being used to hammer a backend with
 *                                    connection attempts
 */
public record SecurityConfig(
        boolean rateLimitEnabled,
        int packetLimit,
        int globalPacketLimit,
        boolean sendConnectionCookie,
        int maxConnectionsPerAddress,
        int maxConnectionAttempts,
        long connectionAttemptWindowMillis,
        boolean requireXuid,
        long commandCooldownMillis
) {
    public SecurityConfig {
        if (packetLimit < 0) {
            throw new IllegalArgumentException("packetLimit cannot be negative");
        }
        if (globalPacketLimit < 0) {
            throw new IllegalArgumentException("globalPacketLimit cannot be negative");
        }
        if (maxConnectionsPerAddress < 1) {
            throw new IllegalArgumentException("maxConnectionsPerAddress must be positive");
        }
        if (maxConnectionAttempts < 1) {
            throw new IllegalArgumentException("maxConnectionAttempts must be positive");
        }
        if (connectionAttemptWindowMillis < 0) {
            throw new IllegalArgumentException("connectionAttemptWindowMillis cannot be negative");
        }
        if (commandCooldownMillis < 0) {
            throw new IllegalArgumentException("commandCooldownMillis cannot be negative");
        }
    }

    public static SecurityConfig defaults() {
        // packetLimit is 500, not RakNet's own 120. 120 is a limit for a server that only plays the
        // game; a proxy also pushes resource packs, and both a login burst and a pack download are
        // answered by more than 120 datagrams inside one 10ms tick over loopback or a LAN. Tripping
        // it blocks the address for ten seconds, which turns the join into disconnect.timeout and
        // then repeats on every retry, so the default value made a legitimate player unable to join
        // at all. 500 still bounds one address to a small fraction of the global limit.
        return new SecurityConfig(true, 500, 100_000, true, 5, 8, 10_000, true, 1_000);
    }

    public static SecurityConfig from(Properties properties) {
        SecurityConfig defaults = defaults();
        return new SecurityConfig(
                booleanProperty(properties, "security.rateLimit.enabled", defaults.rateLimitEnabled()),
                intProperty(properties, "security.rateLimit.packetLimit", defaults.packetLimit()),
                intProperty(properties, "security.rateLimit.globalPacketLimit", defaults.globalPacketLimit()),
                booleanProperty(properties, "security.sendConnectionCookie", defaults.sendConnectionCookie()),
                intProperty(properties, "security.maxConnectionsPerAddress", defaults.maxConnectionsPerAddress()),
                intProperty(properties, "security.maxConnectionAttempts", defaults.maxConnectionAttempts()),
                intProperty(properties, "security.connectionAttemptWindowMillis",
                        (int) defaults.connectionAttemptWindowMillis()),
                booleanProperty(properties, "security.requireXuid", defaults.requireXuid()),
                intProperty(properties, "security.commandCooldownMillis", (int) defaults.commandCooldownMillis())
        );
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(ConfigValues.stripInlineComment(value));
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(ConfigValues.stripInlineComment(value));
    }
}
