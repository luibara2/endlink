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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyConnectionFailoverTest {
    @Test
    void refusesASecondFailoverWhileOneIsRunning() {
        ProxyConnection connection = connection();

        assertEquals(ProxyConnection.FailoverStart.STARTED, connection.beginFailover());
        assertEquals(ProxyConnection.FailoverStart.ALREADY_RUNNING, connection.beginFailover());
        assertTrue(connection.isFailingOver());

        connection.finishFailover();
        assertFalse(connection.isFailingOver());
    }

    @Test
    void capsFailoversThatKeepHappeningBackToBack() {
        // A fallback that accepts the player and immediately drops them again would otherwise
        // ping-pong them between backends at connection speed.
        ProxyConnection connection = connection();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(ProxyConnection.FailoverStart.STARTED, connection.beginFailover());
            connection.finishFailover();
        }

        assertEquals(ProxyConnection.FailoverStart.TOO_MANY, connection.beginFailover());
        assertFalse(connection.isFailingOver());
    }

    @Test
    void doesNotConsiderTheClientInAWorldUntilStartGameHasBeenForwarded() {
        ProxyConnection connection = connection();

        assertFalse(connection.hasClientJoinedWorld());

        connection.markClientJoinedWorld();
        assertTrue(connection.hasClientJoinedWorld());
    }

    @Test
    void staysInAWorldAcrossBackendSwitches() {
        ProxyConnection connection = connection();
        connection.markClientJoinedWorld();

        connection.setBackend("lobby", null);

        assertTrue(connection.hasClientJoinedWorld());
    }

    @Test
    void holdsTheSwitchLockUntilItIsReleased() {
        ProxyConnection connection = connection();

        assertEquals(ProxyConnection.SwitchStart.STARTED, connection.beginBackendSwitch("lobby"));
        assertEquals(ProxyConnection.SwitchStart.ALREADY_SWITCHING, connection.beginBackendSwitch("survival"));
        assertEquals("lobby", connection.backendSwitchTarget());
        assertTrue(connection.isSwitchingBackend());

        connection.finishBackendSwitch();

        assertFalse(connection.isSwitchingBackend());
        assertEquals(ProxyConnection.SwitchStart.STARTED, connection.beginBackendSwitch("survival"));
    }

    @Test
    void arrivingOnABackendReleasesTheSwitchLock() {
        // A switch that succeeds never calls finishBackendSwitch; setBackend is what ends it.
        ProxyConnection connection = connection();
        connection.beginBackendSwitch("lobby");

        connection.setBackend("lobby", null);

        assertFalse(connection.isSwitchingBackend());
        assertEquals(ProxyConnection.SwitchStart.STARTED, connection.beginBackendSwitch("survival"));
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
