package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {
    @Test
    void defaultsAreProtectiveRatherThanPermissive() {
        SecurityConfig security = SecurityConfig.defaults();

        // The proxy used to remove the rate limiter outright and set the packet limit to 0, which
        // left the unconnected-ping path unmetered on a public address.
        assertTrue(security.rateLimitEnabled());
        // Protective, but not so tight that joining trips it. RakNet's own 120 per 10ms tick is
        // exceeded by a single Bedrock login burst over loopback or a LAN, and a trip blocks the
        // address for ten seconds — so the player times out, retries, and never gets in. The limit
        // has to stay far below the global one to still bound a single address.
        assertTrue(security.packetLimit() >= 500,
                () -> "packet limit " + security.packetLimit() + " is low enough to block a login burst");
        assertTrue(security.packetLimit() <= security.globalPacketLimit() / 10,
                () -> "one address may claim too much of the global budget");
        assertTrue(security.sendConnectionCookie());
        assertTrue(security.requireXuid());
    }

    @Test
    void oneAddressCannotHoldEverySlotByDefault() {
        SecurityConfig security = SecurityConfig.defaults();

        // Above 1, because a home NAT, a school or an office is one address with several players
        // behind it.
        assertTrue(security.maxConnectionsPerAddress() > 1);
        assertTrue(security.maxConnectionAttempts() > 1);
    }

    @Test
    void readsOverridesFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("security.rateLimit.enabled", "false");
        properties.setProperty("security.rateLimit.packetLimit", "240");
        properties.setProperty("security.sendConnectionCookie", "false");
        properties.setProperty("security.maxConnectionsPerAddress", "2");
        properties.setProperty("security.maxConnectionAttempts", "4");
        properties.setProperty("security.connectionAttemptWindowMillis", "5000");
        properties.setProperty("security.requireXuid", "false");
        properties.setProperty("security.commandCooldownMillis", "0");

        SecurityConfig security = SecurityConfig.from(properties);

        assertFalse(security.rateLimitEnabled());
        assertEquals(240, security.packetLimit());
        assertFalse(security.sendConnectionCookie());
        assertEquals(2, security.maxConnectionsPerAddress());
        assertEquals(4, security.maxConnectionAttempts());
        assertEquals(5000, security.connectionAttemptWindowMillis());
        assertFalse(security.requireXuid());
        assertEquals(0, security.commandCooldownMillis());
    }

    @Test
    void stripsAnInlineCommentBeforeParsingANumber() {
        // Properties keeps "# per tick" as part of the value, and Integer.parseInt would then throw
        // during startup with nothing pointing at the line responsible.
        Properties properties = new Properties();
        properties.setProperty("security.rateLimit.packetLimit", "240  # per tick");
        properties.setProperty("security.requireXuid", "false ; loosen");

        SecurityConfig security = SecurityConfig.from(properties);

        assertEquals(240, security.packetLimit());
        assertFalse(security.requireXuid());
    }

    @Test
    void aZeroPacketLimitIsAllowedBecauseItUninstallsTheLimiter() {
        // It must not be read as "unlimited": the RakNet limiter blocks an address as soon as its
        // count exceeds the limit, so a limit of zero blocks everyone on their first datagram. The
        // listener removes the handler instead.
        Properties properties = new Properties();
        properties.setProperty("security.rateLimit.packetLimit", "0");

        assertEquals(0, SecurityConfig.from(properties).packetLimit());
    }

    @Test
    void rejectsLimitsThatWouldLockEveryoneOut() {
        SecurityConfig defaults = SecurityConfig.defaults();

        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                true, 120, 100_000, true, 0, 8, 10_000, true, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                true, 120, 100_000, true, defaults.maxConnectionsPerAddress(), 0, 10_000, true, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                true, -1, 100_000, true, defaults.maxConnectionsPerAddress(), 8, 10_000, true, 1_000));
    }
}
