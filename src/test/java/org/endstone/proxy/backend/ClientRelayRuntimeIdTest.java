package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    void rewritesTheVehicleAMountedPlayerNamesOntoTheCurrentBackendsId() {
        // The horse the player is sitting on. The new backend gave it runtime id 1, which is the id
        // the client already uses for itself, so the proxy had to invent a synthetic client-side id
        // for it — and from then on the two sides disagree about what "the vehicle" is called.
        ProxyConnection connection = connectionAfterSwitch();
        long clientVehicleId = connection.registerEntityRuntimeMapping(500, CLIENT_ID);
        assertNotEquals(CLIENT_ID, clientVehicleId,
                "the horse must not keep an id the client already spent on itself");

        PlayerAuthInputPacket authInput = new PlayerAuthInputPacket();
        authInput.setPredictedVehicle(clientVehicleId);

        new ClientRelayPacketHandler(connection, null, null).normalizePlayerRuntimeId(authInput);

        // Left alone this names an entity the backend has never heard of, and the mount silently
        // ignores every input the player gives it.
        assertEquals(CLIENT_ID, authInput.getPredictedVehicle());
    }

    @Test
    void rewritesTheRiddenEntityOnALegacyMove() {
        ProxyConnection connection = connectionAfterSwitch();
        long clientVehicleId = connection.registerEntityRuntimeMapping(500, CLIENT_ID);

        MovePlayerPacket move = new MovePlayerPacket();
        move.setRuntimeEntityId(CLIENT_ID);
        move.setRidingRuntimeEntityId(clientVehicleId);

        new ClientRelayPacketHandler(connection, null, null).normalizePlayerRuntimeId(move);

        assertEquals(BACKEND_ID, move.getRuntimeEntityId());
        assertEquals(CLIENT_ID, move.getRidingRuntimeEntityId());
    }

    @Test
    void leavesAnUnmountedInputAlone() {
        // Not riding anything is spelled zero, and toBackendRuntimeEntityId must not turn that into
        // an entity reference — every input tick of every player on foot carries this field.
        PlayerAuthInputPacket authInput = new PlayerAuthInputPacket();
        authInput.setPredictedVehicle(0);

        handlerAfterSwitch().normalizePlayerRuntimeId(authInput);

        assertEquals(0, authInput.getPredictedVehicle());
    }

    @Test
    void leavesPacketsAloneBeforeAnyStartGameHasArrived() {
        RespawnPacket respawn = new RespawnPacket();
        respawn.setState(RespawnPacket.State.CLIENT_READY);
        respawn.setRuntimeEntityId(CLIENT_ID);

        new ClientRelayPacketHandler(connection(), null, null).normalizePlayerRuntimeId(respawn);

        assertEquals(CLIENT_ID, respawn.getRuntimeEntityId());
    }

    @Test
    void despawnedEntitiesDoNotAccumulateRuntimeMappingsForTheWholeSession() {
        ProxyConnection connection = connection();
        connection.setBackendPlayerRuntimeEntityId(20);
        connection.setBackendPlayerRuntimeEntityId(30);

        long clientEntityId = connection.registerEntityRuntimeMapping(200, 20);
        assertEquals(1, connection.trackedRuntimeEntityMappingCount());

        connection.removeEntityRuntimeMapping(200);

        assertEquals(0, connection.trackedRuntimeEntityMappingCount());
        assertEquals(clientEntityId, connection.toBackendRuntimeEntityId(clientEntityId),
                "both directions of the stale mapping must be released");
    }

    /** A connection that joined on a backend using id 1 and then switched to one using id 109. */
    private static ClientRelayPacketHandler handlerAfterSwitch() {
        return new ClientRelayPacketHandler(connectionAfterSwitch(), null, null);
    }

    private static ProxyConnection connectionAfterSwitch() {
        ProxyConnection connection = connection();
        connection.setBackendPlayerRuntimeEntityId(CLIENT_ID);
        connection.setBackendPlayerRuntimeEntityId(BACKEND_ID);
        assertEquals(CLIENT_ID, connection.clientPlayerRuntimeEntityId());
        return connection;
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
