package org.endstone.proxy.security;

import org.endstone.proxy.config.SecurityConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RakNet's {@code RAK_MAX_CONNECTIONS} is one pool shared by every address, so the property that
 * matters here is that no single address can empty it — and that a legitimate reconnect is not
 * mistaken for an attack.
 */
class ConnectionThrottleTest {
    private long now = 1_000_000L;

    private ConnectionThrottle throttle(int maxConnections, int maxAttempts, long windowMillis) {
        SecurityConfig defaults = SecurityConfig.defaults();
        SecurityConfig config = new SecurityConfig(
                defaults.rateLimitEnabled(),
                defaults.packetLimit(),
                defaults.globalPacketLimit(),
                defaults.sendConnectionCookie(),
                maxConnections,
                maxAttempts,
                windowMillis,
                defaults.requireXuid(),
                defaults.commandCooldownMillis()
        );
        return new ConnectionThrottle(config, () -> now);
    }

    /** Same host, different source port — which is what every reconnect looks like. */
    private static InetSocketAddress from(String host, int port) {
        return new InetSocketAddress(host, port);
    }

    @Test
    void allowsUpToTheConcurrentLimitThenRefuses() {
        ConnectionThrottle throttle = throttle(2, 100, 10_000);

        assertTrue(throttle.accept(from("10.0.0.1", 1)));
        assertTrue(throttle.accept(from("10.0.0.1", 2)));
        assertFalse(throttle.accept(from("10.0.0.1", 3)));
    }

    @Test
    void freesTheSlotWhenASessionCloses() {
        ConnectionThrottle throttle = throttle(1, 100, 10_000);
        assertTrue(throttle.accept(from("10.0.0.1", 1)));
        assertFalse(throttle.accept(from("10.0.0.1", 2)));

        throttle.release(from("10.0.0.1", 1));

        assertTrue(throttle.accept(from("10.0.0.1", 3)));
    }

    @Test
    void countsPerAddressNotPerSocket() {
        ConnectionThrottle throttle = throttle(1, 100, 10_000);

        assertTrue(throttle.accept(from("10.0.0.1", 1)));
        // A different port from the same host is the same player reconnecting, not a new one.
        assertFalse(throttle.accept(from("10.0.0.1", 40_000)));
        // A different host is unaffected.
        assertTrue(throttle.accept(from("10.0.0.2", 1)));
    }

    @Test
    void limitsHowFastOneAddressMayOpenSessions() {
        ConnectionThrottle throttle = throttle(100, 3, 10_000);

        for (int port = 1; port <= 3; port++) {
            assertTrue(throttle.accept(from("10.0.0.1", port)));
            throttle.release(from("10.0.0.1", port));
        }

        // Under the concurrent limit, but too many in the window: this is the case that costs the
        // backends a dial-out each.
        assertFalse(throttle.accept(from("10.0.0.1", 4)));
    }

    @Test
    void theAttemptWindowExpires() {
        ConnectionThrottle throttle = throttle(100, 2, 10_000);
        assertTrue(throttle.accept(from("10.0.0.1", 1)));
        assertTrue(throttle.accept(from("10.0.0.1", 2)));
        assertFalse(throttle.accept(from("10.0.0.1", 3)));

        now += 10_000;

        assertTrue(throttle.accept(from("10.0.0.1", 4)));
    }

    @Test
    void aRefusedAttemptDoesNotConsumeASlot() {
        ConnectionThrottle throttle = throttle(1, 100, 10_000);
        assertTrue(throttle.accept(from("10.0.0.1", 1)));

        assertFalse(throttle.accept(from("10.0.0.1", 2)));
        assertFalse(throttle.accept(from("10.0.0.1", 3)));

        // Still exactly the one real session; refusals must not inflate the count and lock the
        // address out permanently once it closes.
        throttle.release(from("10.0.0.1", 1));
        assertTrue(throttle.accept(from("10.0.0.1", 4)));
    }

    @Test
    void releasingAnAddressThatHoldsNothingIsHarmless() {
        ConnectionThrottle throttle = throttle(1, 100, 10_000);
        assertTrue(throttle.accept(from("10.0.0.1", 1)));

        // Counting is per address, so a release cannot name a particular session — which is why the
        // listener only releases sessions it accepted. An unrelated address must not go negative
        // and hand itself an extra slot later.
        throttle.release(from("10.0.0.2", 99));
        throttle.release(from("10.0.0.2", 98));
        assertTrue(throttle.accept(from("10.0.0.2", 1)));
        assertFalse(throttle.accept(from("10.0.0.2", 2)));

        assertFalse(throttle.accept(from("10.0.0.1", 2)));
    }
}
