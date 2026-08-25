package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddHangingEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPaintingPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LocatorBarPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerLocationPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScoreboardIdentityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendSwitchEntityReplayTest {
    @Test
    void preservesEveryEntitySpawnSuppressedByTheDimensionReset() {
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new AddEntityPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new AddItemEntityPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new AddHangingEntityPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new AddPaintingPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new AddPlayerPacket()));
    }

    @Test
    void preservesEntityRemovalOrderingWithoutBufferingEveryMovementTick() {
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new RemoveEntityPacket()));
        assertFalse(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new MoveEntityDeltaPacket()));
    }

    @Test
    void preservesPersistentHudPacketsUntilTheClientFinishesChangingDimension() {
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new BossEventPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new SetDisplayObjectivePacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new RemoveObjectivePacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new SetScorePacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new SetScoreboardIdentityPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new LocatorBarPacket()));
        assertTrue(BackendRelayPacketHandler.isDeferrableWorldStatePacket(new PlayerLocationPacket()));
    }
}
