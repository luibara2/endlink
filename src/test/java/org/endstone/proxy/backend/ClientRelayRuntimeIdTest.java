package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.endstone.proxy.auth.AuthData;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The client keeps the runtime entity id from its first StartGame for the whole proxy session, while
 * every backend assigns its own. After a switch they differ, and a serverbound packet still carrying
 * the client's id names an entity the backend does not associate with this player — so it is dropped
 * in silence.
 */
final class ClientRelayRuntimeIdTest {
    private static final long CLIENT_ID = 1;
    private static final long BACKEND_ID = 109;

    @Test
    void rewritesTheRespawnRequestOntoTheCurrentBackendsPlayerId() {
        // Left alone, the backend never answers SERVER_READY and the player is stuck on the
        // respawn screen while the client retries forever.
        RespawnPacket respawn = new RespawnPacket();
        respawn.setState(RespawnPacket.State.CLIENT_READY);
        respawn.setRuntimeEntityId(CLIENT_ID);

        handlerAfterSwitch().normalizePlayerRuntimeId(respawn);

        assertEquals(BACKEND_ID, respawn.getRuntimeEntityId());
    }

    @Test
    void stillRewritesTheOtherLocalPlayerPackets() {
        ClientRelayPacketHandler handler = handlerAfterSwitch();

        PlayerActionPacket action = new PlayerActionPacket();
        action.setRuntimeEntityId(CLIENT_ID);
        AnimatePacket animate = new AnimatePacket();
        animate.setRuntimeEntityId(CLIENT_ID);
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(CLIENT_ID);

        handler.normalizePlayerRuntimeId(action);
        handler.normalizePlayerRuntimeId(animate);
        handler.normalizePlayerRuntimeId(initialized);

        assertEquals(BACKEND_ID, action.getRuntimeEntityId());
        assertEquals(BACKEND_ID, animate.getRuntimeEntityId());
        assertEquals(BACKEND_ID, initialized.getRuntimeEntityId());
    }

    @Test
    void leavesPacketsAloneBeforeAnyStartGameHasArrived() {
        RespawnPacket respawn = new RespawnPacket();
        respawn.setState(RespawnPacket.State.CLIENT_READY);
        respawn.setRuntimeEntityId(CLIENT_ID);

        new ClientRelayPacketHandler(connection(), null, null).normalizePlayerRuntimeId(respawn);

        assertEquals(CLIENT_ID, respawn.getRuntimeEntityId());
    }

    /** A connection that joined on a backend using id 1 and then switched to one using id 109. */
    private static ClientRelayPacketHandler handlerAfterSwitch() {
        ProxyConnection connection = connection();
        connection.setBackendPlayerRuntimeEntityId(CLIENT_ID);
        connection.setBackendPlayerRuntimeEntityId(BACKEND_ID);
        assertEquals(CLIENT_ID, connection.clientPlayerRuntimeEntityId());
        return new ClientRelayPacketHandler(connection, null, null);
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
