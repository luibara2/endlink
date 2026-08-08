package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientOptionsPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModernClientTo944TranslatorTest {
    private final ProtocolBinding binding = ProtocolRegistry.createDefault().findBinding(975, 944).orElseThrow();
    private final TranslationContext context = new TranslationContext(
            binding.clientCodec(),
            binding.canonicalCodec(),
            binding.backendCodec()
    );

    @Test
    void stripsV975OnlyServerboundFields() {
        UpdateClientOptionsPacket packet = new UpdateClientOptionsPacket();
        packet.setFilterProfanityChange(Boolean.TRUE);

        assertSame(packet, ModernClientTo944Translator.INSTANCE.translateServerbound(packet, context));

        assertNull(packet.getFilterProfanityChange());
    }

    @Test
    void zeroesStartGameChecksumForV975Client() {
        StartGamePacket packet = new StartGamePacket();
        packet.setBlockRegistryChecksum(12345);

        assertSame(packet, ModernClientTo944Translator.INSTANCE.translateClientbound(packet, context));

        assertEquals(0, packet.getBlockRegistryChecksum());
    }

    @Test
    void remapsClientboundSoundIdsFrom944To975() {
        SetEntityDataPacket sound = new SetEntityDataPacket();
        sound.getMetadata().putType(EntityDataTypes.HEARTBEAT_SOUND_EVENT, 599);

        assertSame(sound, ModernClientTo944Translator.INSTANCE.translateClientbound(sound, context));

        assertEquals(601, sound.getMetadata().get(EntityDataTypes.HEARTBEAT_SOUND_EVENT));
    }

    @Test
    void stripsEntityStateAddedInV975() {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.getMetadata().putType(EntityDataTypes.RESERVED_139, 4L);
        packet.getMetadata().putType(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX, 32.0f);
        packet.getMetadata().setFlag(EntityFlag.USES_LEGACY_FRICTION, true);

        assertSame(packet, ModernClientTo944Translator.INSTANCE.translateServerbound(packet, context));

        assertFalse(packet.getMetadata().containsKey(EntityDataTypes.RESERVED_139));
        assertFalse(packet.getMetadata().containsKey(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX));
        assertFalse(packet.getMetadata().getFlags().containsKey(EntityFlag.USES_LEGACY_FRICTION));
    }
}
