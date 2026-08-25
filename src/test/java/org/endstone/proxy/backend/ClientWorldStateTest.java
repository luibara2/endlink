package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.LocatorBarWaypoint;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LocatorBarPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScoreboardIdentityPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientWorldStateTest {
    @Test
    void clearsBossBarScoreboardAndLocatorStateFromThePreviousBackend() {
        ClientWorldState state = new ClientWorldState();

        BossEventPacket createBossBar = new BossEventPacket();
        createBossBar.setAction(BossEventPacket.Action.CREATE);
        createBossBar.setBossUniqueEntityId(71L);
        state.track(createBossBar);

        SetDisplayObjectivePacket displayObjective = new SetDisplayObjectivePacket();
        displayObjective.setObjectiveId("hub-sidebar");
        state.track(displayObjective);

        SetScoreboardIdentityPacket addIdentity = new SetScoreboardIdentityPacket();
        addIdentity.setAction(SetScoreboardIdentityPacket.Action.ADD);
        addIdentity.getEntries().add(new SetScoreboardIdentityPacket.Entry(91L, 1234L));
        state.track(addIdentity);

        UUID groupHandle = UUID.fromString("00000000-0000-0000-0000-000000000123");
        LocatorBarPacket addWaypoint = new LocatorBarPacket();
        addWaypoint.getWaypoints().add(new LocatorBarPacket.Payload(
                LocatorBarPacket.Action.ADD,
                groupHandle,
                new LocatorBarWaypoint()
        ));
        state.track(addWaypoint);

        List<BedrockPacket> cleanup = state.clearPackets();

        BossEventPacket removeBossBar = only(cleanup, BossEventPacket.class);
        assertEquals(BossEventPacket.Action.REMOVE, removeBossBar.getAction());
        assertEquals(71L, removeBossBar.getBossUniqueEntityId());

        RemoveObjectivePacket removeObjective = only(cleanup, RemoveObjectivePacket.class);
        assertEquals("hub-sidebar", removeObjective.getObjectiveId());

        SetScoreboardIdentityPacket removeIdentity = only(cleanup, SetScoreboardIdentityPacket.class);
        assertEquals(SetScoreboardIdentityPacket.Action.REMOVE, removeIdentity.getAction());
        assertEquals(List.of(91L), removeIdentity.getEntries().stream()
                .map(SetScoreboardIdentityPacket.Entry::getScoreboardId)
                .toList());

        LocatorBarPacket removeWaypoint = only(cleanup, LocatorBarPacket.class);
        assertEquals(1, removeWaypoint.getWaypoints().size());
        LocatorBarPacket.Payload removal = removeWaypoint.getWaypoints().get(0);
        assertEquals(LocatorBarPacket.Action.REMOVE, removal.getActionFlag());
        assertEquals(groupHandle, removal.getGroupHandle());
        assertEquals(0, removal.getWaypoint().getUpdateFlag());
        assertNull(removal.getWaypoint().getEntityUniqueId());

        assertTrue(state.clearPackets().isEmpty(), "cleanup must also forget the previous backend state");
    }

    @Test
    void doesNotRepeatStateTheBackendAlreadyRemoved() {
        ClientWorldState state = new ClientWorldState();

        BossEventPacket createBossBar = new BossEventPacket();
        createBossBar.setAction(BossEventPacket.Action.CREATE);
        createBossBar.setBossUniqueEntityId(72L);
        state.track(createBossBar);
        BossEventPacket removeBossBar = new BossEventPacket();
        removeBossBar.setAction(BossEventPacket.Action.REMOVE);
        removeBossBar.setBossUniqueEntityId(72L);
        state.track(removeBossBar);

        SetDisplayObjectivePacket displayObjective = new SetDisplayObjectivePacket();
        displayObjective.setObjectiveId("temporary");
        state.track(displayObjective);
        RemoveObjectivePacket removeObjective = new RemoveObjectivePacket();
        removeObjective.setObjectiveId("temporary");
        state.track(removeObjective);

        SetScoreboardIdentityPacket addIdentity = new SetScoreboardIdentityPacket();
        addIdentity.setAction(SetScoreboardIdentityPacket.Action.ADD);
        addIdentity.getEntries().add(new SetScoreboardIdentityPacket.Entry(92L, 5678L));
        state.track(addIdentity);
        SetScoreboardIdentityPacket removeIdentity = new SetScoreboardIdentityPacket();
        removeIdentity.setAction(SetScoreboardIdentityPacket.Action.REMOVE);
        removeIdentity.getEntries().add(new SetScoreboardIdentityPacket.Entry(92L, 0L));
        state.track(removeIdentity);

        UUID groupHandle = UUID.fromString("00000000-0000-0000-0000-000000000124");
        LocatorBarPacket addWaypoint = new LocatorBarPacket();
        addWaypoint.getWaypoints().add(new LocatorBarPacket.Payload(
                LocatorBarPacket.Action.ADD,
                groupHandle,
                new LocatorBarWaypoint()
        ));
        state.track(addWaypoint);
        LocatorBarPacket removeWaypoint = new LocatorBarPacket();
        removeWaypoint.getWaypoints().add(new LocatorBarPacket.Payload(
                LocatorBarPacket.Action.REMOVE,
                groupHandle,
                new LocatorBarWaypoint()
        ));
        state.track(removeWaypoint);

        assertTrue(state.clearPackets().isEmpty());
    }

    @Test
    void generatedHudCleanupEncodesForTheCurrentBedrockProtocol() {
        ClientWorldState state = new ClientWorldState();

        BossEventPacket bossBar = new BossEventPacket();
        bossBar.setAction(BossEventPacket.Action.CREATE);
        bossBar.setBossUniqueEntityId(73L);
        state.track(bossBar);

        SetDisplayObjectivePacket objective = new SetDisplayObjectivePacket();
        objective.setObjectiveId("sidebar");
        state.track(objective);

        SetScoreboardIdentityPacket identity = new SetScoreboardIdentityPacket();
        identity.setAction(SetScoreboardIdentityPacket.Action.ADD);
        identity.getEntries().add(new SetScoreboardIdentityPacket.Entry(93L, 9012L));
        state.track(identity);

        LocatorBarPacket waypoint = new LocatorBarPacket();
        waypoint.getWaypoints().add(new LocatorBarPacket.Payload(
                LocatorBarPacket.Action.ADD,
                UUID.fromString("00000000-0000-0000-0000-000000000125"),
                new LocatorBarWaypoint()
        ));
        state.track(waypoint);

        for (BedrockPacket cleanup : state.clearPackets()) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), buffer, cleanup);
                assertTrue(buffer.isReadable(), cleanup.getClass().getSimpleName() + " encoded no bytes");
            } finally {
                buffer.release();
            }
        }
    }

    private static <T extends BedrockPacket> T only(List<BedrockPacket> packets, Class<T> type) {
        List<T> matches = packets.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), () -> "expected one " + type.getSimpleName() + " in " + packets);
        return matches.get(0);
    }
}
