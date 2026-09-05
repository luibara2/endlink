package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.endstone.proxy.auth.AuthData;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for unique entity ids sent by the active backend after a switch. */
final class BackendRelayUniqueIdTest {
    private static final long CLIENT_PLAYER_ID = 1L;
    private static final long BACKEND_PLAYER_ID = 109L;

    @Test
    void rewritesLateBossEventPlayerIdsAfterABackendSwitch() {
        BossEventPacket bossEvent = new BossEventPacket();
        bossEvent.setAction(BossEventPacket.Action.CREATE);
        bossEvent.setBossUniqueEntityId(BACKEND_PLAYER_ID);
        bossEvent.setPlayerUniqueEntityId(BACKEND_PLAYER_ID);

        handlerAfterSwitch().rewriteClientboundRuntimeIds(bossEvent);

        assertEquals(CLIENT_PLAYER_ID, bossEvent.getBossUniqueEntityId());
        assertEquals(CLIENT_PLAYER_ID, bossEvent.getPlayerUniqueEntityId());
    }

    @Test
    void preservesSyntheticBossIdWhileRewritingTheTargetPlayer() {
        long syntheticBossId = -7_001L;
        BossEventPacket bossEvent = new BossEventPacket();
        bossEvent.setAction(BossEventPacket.Action.CREATE);
        bossEvent.setBossUniqueEntityId(syntheticBossId);
        bossEvent.setPlayerUniqueEntityId(BACKEND_PLAYER_ID);

        handlerAfterSwitch().rewriteClientboundRuntimeIds(bossEvent);

        assertEquals(syntheticBossId, bossEvent.getBossUniqueEntityId());
        assertEquals(CLIENT_PLAYER_ID, bossEvent.getPlayerUniqueEntityId());
    }

    @Test
    void mountLinksUseUniqueIdsAndOnlyRewriteTheLocalPlayer() {
        long vehicleUniqueId = 50_000L;
        SetEntityLinkPacket linkPacket = new SetEntityLinkPacket();
        linkPacket.setEntityLink(new EntityLinkData(
                vehicleUniqueId,
                BACKEND_PLAYER_ID,
                EntityLinkData.Type.RIDER,
                true,
                true,
                0.25f
        ));

        handlerAfterSwitch().rewriteClientboundRuntimeIds(linkPacket);

        assertEquals(vehicleUniqueId, linkPacket.getEntityLink().getFrom());
        assertEquals(CLIENT_PLAYER_ID, linkPacket.getEntityLink().getTo());
        assertEquals(EntityLinkData.Type.RIDER, linkPacket.getEntityLink().getType());
        assertEquals(0.25f, linkPacket.getEntityLink().getVehicleAngularVelocity());
    }

    private static BackendRelayPacketHandler handlerAfterSwitch() {
        ProxyConnection connection = connection();
        connection.setBackendPlayerUniqueEntityId(CLIENT_PLAYER_ID);
        connection.setBackendPlayerUniqueEntityId(BACKEND_PLAYER_ID);
        assertEquals(CLIENT_PLAYER_ID, connection.clientPlayerUniqueEntityId());
        return new BackendRelayPacketHandler(
                connection,
                null,
                "tntrun",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
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
