package org.endstone.proxy.session;

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.endstone.proxy.auth.AuthData;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.backend.ProxyConnection;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConnectedPlayerRegistryTest {
    @Test
    void acceptsMultipleDifferentAuthenticatedPlayers() {
        ConnectedPlayerRegistry registry = new ConnectedPlayerRegistry(2);

        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.ACCEPTED,
                registry.register(connection("Steve", "111"))
        );
        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.ACCEPTED,
                registry.register(connection("Alex", "222"))
        );
        assertEquals(2, registry.size());
    }

    @Test
    void rejectsDuplicateXuidUntilOriginalConnectionUnregisters() {
        ConnectedPlayerRegistry registry = new ConnectedPlayerRegistry(2);
        ProxyConnection first = connection("Steve", "111");

        assertEquals(ConnectedPlayerRegistry.RegistrationResult.ACCEPTED, registry.register(first));
        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.DUPLICATE_XUID,
                registry.register(connection("OtherSteve", "111"))
        );

        registry.unregister(first);

        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.ACCEPTED,
                registry.register(connection("OtherSteve", "111"))
        );
    }

    @Test
    void rejectsNewPlayersWhenFull() {
        ConnectedPlayerRegistry registry = new ConnectedPlayerRegistry(1);

        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.ACCEPTED,
                registry.register(connection("Steve", "111"))
        );
        assertEquals(
                ConnectedPlayerRegistry.RegistrationResult.FULL,
                registry.register(connection("Alex", "222"))
        );
    }

    private static ProxyConnection connection(String name, String xuid) {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        ClientLogin login = new ClientLogin(
                new AuthData(name, UUID.randomUUID(), xuid),
                new JSONObject(),
                keyPair.getPublic()
        );
        return new ProxyConnection(null, null, login, keyPair, new LoginPacket(), null);
    }
}
