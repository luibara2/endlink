package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModernClientTo898TranslatorTest {
    private final ProtocolBinding binding = ProtocolRegistry.createDefault().findBinding(975, 898).orElseThrow();
    private final TranslationContext context = new TranslationContext(
            binding.clientCodec(),
            binding.canonicalCodec(),
            binding.backendCodec()
    );

    @Test
    void preservesPacketsRepresentableByProtocol898() {
        TextPacket packet = new TextPacket();

        assertSame(packet, ModernClientTo898Translator.INSTANCE.translateServerbound(packet, context));
        assertSame(packet, ModernClientTo898Translator.INSTANCE.translateClientbound(packet, context));
    }

    @Test
    void dropsModernOnlyPacketsThatCannotBeRepresentedByProtocol898() {
        assertNull(ModernClientTo898Translator.INSTANCE.translateServerbound(new ServerboundDiagnosticsPacket(), context));
        assertNull(ModernClientTo898Translator.INSTANCE.translateClientbound(new VoxelShapesPacket(), context));
        assertNull(ModernClientTo898Translator.INSTANCE.translateClientbound(
                new ClientboundDataDrivenUIShowScreenPacket(),
                context
        ));
    }

    @Test
    void stripsEntityMetadataAddedAfterProtocol898() {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        EntityDataMap metadata = packet.getMetadata();
        metadata.putType(EntityDataTypes.AIM_ASSIST_PRIORITY_PRESET_ID, 1);
        metadata.putType(EntityDataTypes.AIM_ASSIST_PRIORITY_CATEGORY_ID, 2);
        metadata.putType(EntityDataTypes.AIM_ASSIST_PRIORITY_ACTOR_ID, 3);
        metadata.putType(EntityDataTypes.RESERVED_139, 4L);
        metadata.putType(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX, 32.0f);
        metadata.setFlag(EntityFlag.USES_LEGACY_FRICTION, true);
        metadata.setFlag(EntityFlag.USES_UNIFORM_AIR_DRAG, true);
        metadata.setFlag(EntityFlag.NAMEPLATE_DEPTH_TESTED, true);

        assertSame(packet, ModernClientTo898Translator.INSTANCE.translateServerbound(packet, context));

        assertFalse(metadata.containsKey(EntityDataTypes.AIM_ASSIST_PRIORITY_PRESET_ID));
        assertFalse(metadata.containsKey(EntityDataTypes.AIM_ASSIST_PRIORITY_CATEGORY_ID));
        assertFalse(metadata.containsKey(EntityDataTypes.AIM_ASSIST_PRIORITY_ACTOR_ID));
        assertFalse(metadata.containsKey(EntityDataTypes.RESERVED_139));
        assertFalse(metadata.containsKey(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX));
        assertFalse(metadata.getFlags().containsKey(EntityFlag.USES_LEGACY_FRICTION));
        assertFalse(metadata.getFlags().containsKey(EntityFlag.USES_UNIFORM_AIR_DRAG));
        assertFalse(metadata.getFlags().containsKey(EntityFlag.NAMEPLATE_DEPTH_TESTED));
    }

    @Test
    void leavesLevelSoundEventSoundToTheCodecIdTables() {
        LevelSoundEventPacket sound = new LevelSoundEventPacket();
        sound.setSound(SoundEvent.ITEM_TRIDENT_HIT_GROUND);

        assertSame(sound, ModernClientTo898Translator.INSTANCE.translateClientbound(sound, context));

        // The backend codec decodes the 898 id into this enum value and the client codec re-encodes it
        // with the 975 id table, so the translator must not shift it a second time.
        assertEquals(SoundEvent.ITEM_TRIDENT_HIT_GROUND, sound.getSound());
    }

    @Test
    void remapsClientboundHeartbeatSoundMetadataFrom898To975() {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.getMetadata().putType(EntityDataTypes.HEARTBEAT_SOUND_EVENT, 568);

        assertSame(packet, ModernClientTo898Translator.INSTANCE.translateClientbound(packet, context));

        // 898 id 568 lands at 591 in the 975 id table (+19, then +2, then +2).
        assertEquals(591, packet.getMetadata().get(EntityDataTypes.HEARTBEAT_SOUND_EVENT));
    }

    @Test
    void doesNotShiftServerboundHeartbeatSoundMetadataUp() {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.getMetadata().putType(EntityDataTypes.HEARTBEAT_SOUND_EVENT, 568);

        assertSame(packet, ModernClientTo898Translator.INSTANCE.translateServerbound(packet, context));

        assertEquals(568, packet.getMetadata().get(EntityDataTypes.HEARTBEAT_SOUND_EVENT));
    }
}
