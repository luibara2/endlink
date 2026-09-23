package org.endstone.proxy.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * When a switch has to go round a reconnect instead of a seamless handoff.
 *
 * <p>The sub-chunk rule comes from a PowerNukkitX backend behind a BDS hub: players who met the hub
 * first arrived on "Building terrain" and never left it, asking PowerNukkitX for sub-chunks it does
 * not serve, while the same backend joined first rendered perfectly.</p>
 */
final class ReconnectReasonTest {

    @Test
    void aClientInRequestModeReachesAWholeChunkBackendByReconnect() {
        assertNotNull(BackendConnector.reconnectReason(true, true, true, true));
    }

    @Test
    void aClientThatNeverMetRequestModeSwitchesSeamlessly() {
        // Joined the whole-chunk backend first: nothing to unlearn.
        assertNull(BackendConnector.reconnectReason(true, true, false, true));
    }

    @Test
    void aRequestModeBackendIsReachedSeamlesslyFromAnywhere() {
        // It announces the mode on its own chunks, so no client has to be told beforehand.
        assertNull(BackendConnector.reconnectReason(true, true, true, false));
        assertNull(BackendConnector.reconnectReason(true, true, false, false));
    }

    @Test
    void theBlockIdSchemeRuleStillApplies() {
        assertNotNull(BackendConnector.reconnectReason(true, false, false, false));
        assertNull(BackendConnector.reconnectReason(null, false, false, false));
        assertNull(BackendConnector.reconnectReason(true, null, false, false));
    }
}
