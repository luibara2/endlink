package org.cloudburstmc.protocol.bedrock.codec.v1001;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PartyDestinationCookieResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.SendPartyDestinationCookiePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Guards the parts of 1.26.30 that differ from 1.26.20 on the wire. A v1001 codec that merely
 * inherits v975 passes none of these.
 */
class Bedrock_v1001Test {
    private final BedrockCodecHelper helper = Bedrock_v1001.CODEC.createHelper();

    private static Class<?> packetClassAt(int id) {
        return Bedrock_v1001.CODEC.getPacketDefinition(id).getFactory().get().getClass();
    }

    @Test
    void identifiesItselfAs1_26_30() {
        assertEquals(1001, Bedrock_v1001.CODEC.getProtocolVersion());
        assertEquals("1.26.30", Bedrock_v1001.CODEC.getMinecraftVersion());
    }

    @Test
    void registersThePacketsAddedIn1_26_30() {
        assertEquals(ClientboundUpdateSoundDataPacket.class, packetClassAt(348));
        assertEquals(SendPartyDestinationCookiePacket.class, packetClassAt(349));
        assertEquals(PartyDestinationCookieResponsePacket.class, packetClassAt(350));

        assertNotNull(Bedrock_v975.CODEC.getPacketDefinition(347));
        // 1.26.20 stops at 347, so a codec that just inherited it would have nothing at 348.
        assertEquals(null, Bedrock_v975.CODEC.getPacketDefinition(348));
    }

    @Test
    void reshapesThePacketsThatChangedIn1_26_30() {
        for (Class<? extends org.cloudburstmc.protocol.bedrock.packet.BedrockPacket> packet : java.util.List.of(
                org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.class,
                org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.BossEventPacket.class,
                org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket.class
        )) {
            assertNotSame(
                    Bedrock_v975.CODEC.getPacketDefinition(packet).getSerializer(),
                    Bedrock_v1001.CODEC.getPacketDefinition(packet).getSerializer(),
                    packet.getSimpleName() + " must use a 1.26.30 serializer"
            );
        }
    }

    @Test
    void writesLevelSoundEventSoundAsAName() {
        LevelSoundEventPacket packet = new LevelSoundEventPacket();
        packet.setSound(SoundEvent.GEYSER_ERUPTION_START);
        packet.setPosition(Vector3f.ZERO);
        packet.setIdentifier("");

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(helper, buffer, packet);
            // 1.26.20 and older write a varint id here; 1.26.30 writes the sound's name.
            assertEquals("geyser_eruption_start", helper.readString(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void toleratesASoundAnOlderBackendCodecCouldNotResolve() {
        LevelSoundEventPacket packet = new LevelSoundEventPacket();
        packet.setSound(null);
        packet.setPosition(Vector3f.ZERO);
        packet.setIdentifier("");

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(helper, buffer, packet);
            assertEquals("undefined", helper.readString(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void writesClientCacheBlobStatusNaksBeforeAcks() {
        ClientCacheBlobStatusPacket packet = new ClientCacheBlobStatusPacket();
        packet.getNaks().add(11L);
        packet.getAcks().add(22L);
        packet.getAcks().add(33L);

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(helper, buffer, packet);

            ClientCacheBlobStatusPacket decoded = new ClientCacheBlobStatusPacket();
            Bedrock_v1001.CODEC.getPacketDefinition(ClientCacheBlobStatusPacket.class)
                    .getSerializer()
                    .deserialize(buffer, helper, decoded);

            assertEquals(java.util.List.of(11L), java.util.List.copyOf(decoded.getNaks()));
            assertEquals(java.util.List.of(22L, 33L), java.util.List.copyOf(decoded.getAcks()));
        } finally {
            buffer.release();
        }
    }
}
