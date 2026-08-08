package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.endstone.proxy.auth.AuthData;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every accepted {@code /server} costs a backend dial-out and, when it fails, a whole retry window
 * of them. A client can send command packets as fast as it likes, so the gate is what stops one
 * player with a macro becoming a connection flood the backend cannot attribute to anyone.
 */
final class ProxyCommandCooldownTest {
    @Test
    void refusesASecondCommandInsideTheCooldown() {
        ProxyConnection connection = connection();

        assertTrue(connection.claimProxyCommandSlot(60_000));
        assertFalse(connection.claimProxyCommandSlot(60_000));
        assertFalse(connection.claimProxyCommandSlot(60_000));
    }

    @Test
    void theFirstCommandOfASessionIsAlwaysAllowed() {
        // The last-used stamp starts far in the past rather than at zero, so joining does not begin
        // with the cooldown already running.
        assertTrue(connection().claimProxyCommandSlot(60_000));
    }

    @Test
    void aZeroCooldownDisablesTheGateEntirely() {
        ProxyConnection connection = connection();

        assertTrue(connection.claimProxyCommandSlot(0));
        assertTrue(connection.claimProxyCommandSlot(0));
        assertTrue(connection.claimProxyCommandSlot(-1));
    }

    @Test
    void aRefusedCommandDoesNotExtendTheCooldown() {
        ProxyConnection connection = connection();
        assertTrue(connection.claimProxyCommandSlot(60_000));

        assertFalse(connection.claimProxyCommandSlot(60_000));
        // Refusals must not restamp: a player holding a macro would otherwise never be let back in.
        assertTrue(connection.claimProxyCommandSlot(0));
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
