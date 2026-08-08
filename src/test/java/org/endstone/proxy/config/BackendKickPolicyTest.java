package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restart and a ban arrive as the same packet, and treating them alike breaks one or the other.
 *
 * <p>Both failures were seen in production. Failing over on every kick defeated a ban: the player
 * was moved to {@code afk}, transferred straight back to {@code skygen} by the configured backend,
 * banned again, round and round. Never failing over broke the other end — a backend restart sent
 * {@code HOST_DISCONNECTED} and its players were dropped instead of being moved to {@code afk}.</p>
 *
 * <p>{@code auto} keys off the {@code messageSkipped} flag on the wire rather than guessing at kick
 * text, so it is the backend's own statement about which kind of kick this is.</p>
 */
class BackendKickPolicyTest {

    private static final boolean WROTE_A_MESSAGE = true;
    private static final boolean SKIPPED_THE_MESSAGE = false;

    @Test
    void autoIsTheDefault() {
        assertEquals(BackendKickAction.AUTO, ProxyConfig.from(new Properties()).failover().onBackendKick());
    }

    @Test
    void autoRescuesAHostLevelDisconnect() {
        // HOST_DISCONNECTED / SERVER_SHUTDOWN: a bare reason, no text. The host went away, so the
        // player is moved to the fallback exactly as before.
        assertTrue(BackendKickAction.AUTO.failsOver(SKIPPED_THE_MESSAGE));
    }

    @Test
    void autoLetsABanStand() {
        // "You have been banned! Reason: IP banned ..." - written for this player. Relaying it both
        // honours the ban and shows them the message, which carries the appeal link.
        assertFalse(BackendKickAction.AUTO.failsOver(WROTE_A_MESSAGE));
    }

    @Test
    void theTwoOverridesIgnoreTheMessageEntirely() {
        assertFalse(BackendKickAction.DISCONNECT.failsOver(SKIPPED_THE_MESSAGE));
        assertFalse(BackendKickAction.DISCONNECT.failsOver(WROTE_A_MESSAGE));
        assertTrue(BackendKickAction.FAILOVER.failsOver(SKIPPED_THE_MESSAGE));
        assertTrue(BackendKickAction.FAILOVER.failsOver(WROTE_A_MESSAGE));
    }

    @Test
    void theValueIsCaseAndSpaceInsensitive() {
        Properties properties = new Properties();
        properties.setProperty("failover.onBackendKick", "  FailOver  ");

        assertEquals(BackendKickAction.FAILOVER, ProxyConfig.from(properties).failover().onBackendKick());
    }

    @Test
    void anythingUnrecognisedFallsBackToAuto() {
        Properties properties = new Properties();
        properties.setProperty("failover.onBackendKick", "yes please");

        assertEquals(BackendKickAction.AUTO, ProxyConfig.from(properties).failover().onBackendKick());
    }

    @Test
    void theKickPolicyDoesNotDisturbTheFallbackChain() {
        Properties properties = new Properties();
        properties.setProperty("failover.fallbacks", "afk,lobby");

        FailoverConfig failover = ProxyConfig.from(properties).failover();

        assertTrue(failover.enabled(), "outages are still rescued");
        assertTrue(failover.fallbacks().contains("afk"));
        assertEquals(BackendKickAction.AUTO, failover.onBackendKick());
    }
}
