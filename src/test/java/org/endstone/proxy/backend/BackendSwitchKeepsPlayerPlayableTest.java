package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.endstone.proxy.auth.AuthData;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code /server} to a backend that is down must not cost the player their current session.
 *
 * <p>The retry window is up to 30 seconds of wall clock, and the player spends all of it still
 * standing in the world they were already in. They have to keep moving, breaking blocks and being
 * relayed to the backend they are actually on for the whole window — a switch that "succeeds later"
 * is worth nothing if the player is frozen while it tries.
 *
 * <p>These pin the connection-state half of that, which is what decides where
 * {@link ClientRelayPacketHandler} sends serverbound packets: it forwards to
 * {@code connection.backend()}, and only diverts to the pending session once that session is both
 * present and connected. If a switch attempt were ever to point {@code backend()} at the target
 * before the target had actually arrived, every packet in the window would be addressed to a
 * backend that is not there.
 */
final class BackendSwitchKeepsPlayerPlayableTest {

    @Test
    void startingASwitchDoesNotMoveThePlayerOffTheirCurrentBackend() {
        ProxyConnection connection = connection();
        BackendSession current = null; // the session object is opaque here; the name is what routes
        connection.setBackend("default", current);

        assertEquals(ProxyConnection.SwitchStart.STARTED, connection.beginBackendSwitch("lobby"));

        assertTrue(connection.isSwitchingBackend());
        assertEquals("lobby", connection.backendSwitchTarget(), "the target is only a target");
        assertEquals("default", connection.backendName(),
                "the player is still on their old backend until the new one sends StartGame");
        assertSame(current, connection.backend());
        assertNull(connection.pendingBackend(),
                "a backend that never answered never becomes a pending session, so serverbound "
                        + "traffic keeps going to the live one");
    }

    @Test
    void aFailedSwitchLeavesTheConnectionExactlyAsItWas() {
        ProxyConnection connection = connection();
        connection.setBackend("default", null);
        connection.beginBackendSwitch("lobby");

        // What BackendSwitcher does once the retry window elapses without the target ever answering.
        connection.finishBackendSwitch();

        assertEquals("default", connection.backendName(), "the player stays where they were");
        assertNull(connection.backendSwitchTarget());
        assertTrue(!connection.isSwitchingBackend(),
                "the lock must be released or every later /server answers 'already connecting'");
        assertEquals(ProxyConnection.SwitchStart.STARTED, connection.beginBackendSwitch("lobby"),
                "and a second attempt must be allowed to start");
    }

    /**
     * The switch lock is held across every retry of the same backend on purpose, so a second
     * {@code /server} mid-window cannot race the first. That must never be mistaken for a reason to
     * stop relaying: it gates <em>switching</em>, not <em>playing</em>.
     */
    @Test
    void theSwitchLockRefusesASecondSwitchWithoutTouchingTheCurrentBackend() {
        ProxyConnection connection = connection();
        connection.setBackend("default", null);
        connection.beginBackendSwitch("lobby");

        assertEquals(ProxyConnection.SwitchStart.ALREADY_SWITCHING, connection.beginBackendSwitch("survival"));

        assertEquals("default", connection.backendName());
        assertEquals("lobby", connection.backendSwitchTarget(), "the first target is not replaced");
    }

    private static ProxyConnection connection() {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        ClientLogin login = new ClientLogin(
                new AuthData("Steve", UUID.randomUUID(), "111"),
                new JSONObject(),
                keyPair.getPublic()
        );
        return new ProxyConnection(null, null, login, keyPair, new LoginPacket(), null);
    }
}
