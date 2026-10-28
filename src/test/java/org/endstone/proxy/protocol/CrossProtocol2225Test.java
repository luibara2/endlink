package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.BedrockCodecHelper_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.EnvironmentAttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.FloatAttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.UpdateEnvironmentAttributesData;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftNonImplementedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.sound.SetPitchSoundData;
import org.cloudburstmc.protocol.bedrock.data.sound.SetVolumeSoundData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundAttributeLayerSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundCursorItemDragPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPassengerOfBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.endstone.proxy.protocol.block.BedrockBlockStateHash;
import org.endstone.proxy.protocol.block.BlockStateUpgrade;
import org.endstone.proxy.protocol.block.BlockStateValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.26.50 &rarr; 1.26.60 hop.
 *
 * <p>As in {@link CrossProtocol2192AbsentFieldTest}, the packets below are decoded with the
 * <em>older</em> codec first, because that is the only way to get a field that is absent because the
 * sending version has no such field, rather than a null a test remembered to set. Then they go
 * through {@link ModernClientTo2193Translator} and are encoded with 2225, which is what the proxy
 * does for a 1.26.60 client on a 1.26.50 backend.</p>
 */
class CrossProtocol2225Test {

    private static final BedrockCodec OLD = Bedrock_v2193.CODEC;
    private static final BedrockCodec NEW = Bedrock_v2225.CODEC;

    @Test
    void aDimensionFromA1_26_50BackendKeepsItsClouds() {
        DimensionDataPacket packet = new DimensionDataPacket();
        packet.getDefinitions().add(new DimensionDefinition(
                "minecraft:overworld", 320, -64, 0, 0, UUID.randomUUID(), "minecraft:plains"));

        DimensionDataPacket decoded = (DimensionDataPacket) roundTrip(OLD, packet);
        assertFalse(decoded.getDefinitions().get(0).isRenderClouds());

        BedrockPacket translated = ModernClientTo2193Translator.INSTANCE.translateClientbound(decoded, null);
        DimensionDataPacket received = (DimensionDataPacket) roundTrip(NEW, translated);
        DimensionDefinition definition = received.getDefinitions().get(0);
        assertTrue(definition.isRenderClouds());
        assertEquals(ModernClientTo2193Translator.DEFAULT_CLOUD_HEIGHT, definition.getCloudHeight());
        assertEquals("minecraft:plains", definition.getDefaultBiome());
        assertEquals(320, definition.getMaximumHeight());
        assertEquals(-64, definition.getMinimumHeight());
    }

    @Test
    void aDimensionThatSaysItsOwnCloudsIsLeftAlone() {
        DimensionDefinition definition = new DimensionDefinition(
                "minecraft:overworld", 320, -64, 0, 0, null, "", 200, false);
        assertSame(definition, ModernClientTo2193Translator.withClouds(definition));
    }

    /**
     * The clock and noise names are new at 1.26.60 and written unconditionally, so a transition or a
     * noise transition from a 1.26.50 backend reaches the 2225 writer with both null.
     */
    @Test
    void environmentTransitionsFromA1_26_50BackendReachA1_26_60Client() {
        FloatAttributeData from = new FloatAttributeData(0.1f, FloatAttributeData.Operation.OVERRIDE, null, null);
        FloatAttributeData to = new FloatAttributeData(0.9f, FloatAttributeData.Operation.OVERRIDE, null, null);
        ClientboundAttributeLayerSyncPacket packet = new ClientboundAttributeLayerSyncPacket();
        packet.setData(new UpdateEnvironmentAttributesData("fog", 0, List.of(
                new EnvironmentAttributeData("density", from, from, to, 0, 20, CameraEase.LINEAR, 0, false, null),
                new EnvironmentAttributeData("density", from, from, to, 0, 20, CameraEase.LINEAR, 5, true, null))));

        ClientboundAttributeLayerSyncPacket decoded = (ClientboundAttributeLayerSyncPacket) roundTrip(OLD, packet);
        UpdateEnvironmentAttributesData data = (UpdateEnvironmentAttributesData) decoded.getData();
        assertNull(data.getAttributes().get(0).getClockName());
        assertNull(data.getAttributes().get(1).getNoiseName());

        assertDoesNotThrow(() -> roundTrip(NEW, decoded));
    }

    @Test
    void aSoundUpdateFromA1_26_50BackendCarriesOneEvent() {
        ClientboundUpdateSoundDataPacket packet = new ClientboundUpdateSoundDataPacket();
        packet.setServerSoundHandle(42);
        packet.setVolume(new SetVolumeSoundData(0.25f));
        packet.setPitch(new SetPitchSoundData(1.5f));

        ClientboundUpdateSoundDataPacket decoded = (ClientboundUpdateSoundDataPacket) roundTrip(OLD, packet);
        assertNull(decoded.getEvent());

        BedrockPacket translated = ModernClientTo2193Translator.INSTANCE.translateClientbound(decoded, null);
        ClientboundUpdateSoundDataPacket received = (ClientboundUpdateSoundDataPacket) roundTrip(NEW, translated);
        assertEquals(42, received.getServerSoundHandle());
        SetVolumeSoundData volume = assertInstanceOf(SetVolumeSoundData.class, received.getEvent());
        assertEquals(0.25f, volume.getVolume());
    }

    @Test
    void anEmptySoundUpdateIsDropped() {
        assertNull(ModernClientTo2193Translator.singleSoundEvent(new ClientboundUpdateSoundDataPacket()));
    }

    /**
     * The "Action type" byte is BDS's enum value, and 1.26.60 moved the two deprecated craft actions
     * up by one to make room for the disc press. Read from Mojang's dumps: CraftNonImplemented is 18
     * on r26_u5 and 19 on r26_u6. A wrong byte is a TERMINATING_CONNECTION violation.
     */
    @Test
    void theActionTypeByteFollowsEachVersionsOwnNumbering() {
        assertEquals(18, actionTypeByte(OLD, new CraftNonImplementedAction()));
        assertEquals(19, actionTypeByte(NEW, new CraftNonImplementedAction()));
    }

    @Test
    void theNewCodecKeepsThe1_26_45RemoveScoreShape() {
        BedrockCodecHelper_v2168 helper = (BedrockCodecHelper_v2168) NEW.createHelper();
        assertTrue(helper instanceof BedrockCodecHelper_v2169);
        assertFalse(helper.isRemoveScoreKeyedConstant());
    }

    @Test
    void theEightNewPacketsExistOnlyOnTheNewerSide() {
        for (int id = 353; id <= 360; id++) {
            assertNull(OLD.getPacketDefinition(id), "id " + id + " must be 1.26.60-only");
            assertTrue(NEW.getPacketDefinition(id) != null, "id " + id + " must be registered");
        }
        assertEquals(PacketRecipient.SERVER, NEW.getPacketDefinition(ServerboundCursorItemDragPacket.class).getRecipient());
        assertEquals(PacketRecipient.BOTH, NEW.getPacketDefinition(SetPassengerOfBlockPacket.class).getRecipient());
    }

    @Test
    void theSharedPacketIdTableIsUnchanged() {
        for (int id = 0; id < 512; id++) {
            var oldDefinition = OLD.getPacketDefinition(id);
            var newDefinition = NEW.getPacketDefinition(id);
            if (id >= 353 && id <= 360) {
                continue;
            }
            if (oldDefinition == null && newDefinition == null) {
                continue;
            }
            assertTrue(oldDefinition != null && newDefinition != null, "id " + id + " exists on one side only");
            assertEquals(oldDefinition.getFactory().get().getClass(),
                    newDefinition.getFactory().get().getClass(),
                    "id " + id + " is a different packet on each side");
        }
    }

    @Test
    void aNewClientReachesEveryBackendTheProxyAlreadyServed() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();
        for (int backend : new int[]{2193, 2169, 2168}) {
            assertTrue(registry.findPath(2225, backend).isPresent(), "no path from 2225 to " + backend);
        }
        assertEquals(ModernClientTo2193Translator.INSTANCE, registry.findPath(2225, 2193).orElseThrow().get(0));
    }

    /**
     * Redstone wire gained its four sides at 1.26.60. Its 1.26.50 id must come up to the all-"none"
     * state, and every one of the 81 side combinations must come back down to the same id.
     */
    @Test
    void redstoneWireRenumbersBothWays() {
        BlockStateUpgrade upgrade = ModernClientTo2193Translator.blocks().upgrade();
        assertEquals(2193, upgrade.fromProtocol());
        assertEquals(2225, upgrade.toProtocol());

        List<BlockStateValue> old = List.of(BlockStateValue.of("redstone_signal", BlockStateValue.Type.INT, 7));
        int oldId = BedrockBlockStateHash.of("minecraft:redstone_wire", old);
        List<BlockStateValue> none = new ArrayList<>(old);
        for (String side : new String[]{"redstone_east", "redstone_north", "redstone_south", "redstone_west"}) {
            none.add(BlockStateValue.of(side, BlockStateValue.Type.STRING, "none"));
        }
        int newId = BedrockBlockStateHash.of("minecraft:redstone_wire", none);
        assertNotEquals(oldId, newId);
        assertEquals(newId, upgrade.toNewer(oldId));
        assertEquals(oldId, upgrade.toOlder(newId));

        List<BlockStateValue> up = new ArrayList<>(old);
        for (String side : new String[]{"redstone_east", "redstone_north", "redstone_south", "redstone_west"}) {
            up.add(BlockStateValue.of(side, BlockStateValue.Type.STRING, "up"));
        }
        assertEquals(oldId, upgrade.toOlder(BedrockBlockStateHash.of("minecraft:redstone_wire", up)));
    }

    /**
     * Six blocks, every old state of each. Counted from Mojang's metadata: chorus plant 1, fire 16,
     * the two repeaters 16 each, redstone wire 16, scaffolding 16.
     */
    @Test
    void theTableCoversEveryOldStateOfTheSixBlocks() {
        BlockStateUpgrade upgrade = ModernClientTo2193Translator.blocks().upgrade();
        assertEquals(1 + 16 + 32 + 16 + 16, upgrade.upgradeCount());
        assertEquals(64 + 16 * 32 + 32 * 2 + 16 * 81 + 16 * 2, upgrade.downgradeCount());
    }

    private static int actionTypeByte(BedrockCodec codec, ItemStackRequestAction action) {
        BedrockCodecHelper helper = codec.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            helper.writeItemStackRequest(buffer, new ItemStackRequest(1, new ItemStackRequestAction[]{action}, new String[0]));
            VarInts.readInt(buffer);
            VarInts.readUnsignedInt(buffer);
            VarInts.readUnsignedInt(buffer);
            return buffer.readUnsignedByte();
        } finally {
            buffer.release();
        }
    }

    private static BedrockPacket roundTrip(BedrockCodec codec, BedrockPacket packet) {
        int id = codec.getPacketDefinition(packet.getClass()).getId();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }
}
