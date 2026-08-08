package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOutputType;
import org.cloudburstmc.protocol.bedrock.data.sound.FadeSoundData;
import org.cloudburstmc.protocol.bedrock.packet.AnvilDamagePacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the 1.26.40 wire changes. A codec that merely inherits v1001 passes none of these.
 *
 * <p>Every assertion here traces to a diff of EndstoneMC/protocol-docs {@code r26_u3} against
 * {@code r26_u4} — see the "Adding a new Minecraft version" section of HANDOFF.md.</p>
 */
class Bedrock_v2168Test {

    @Test
    void identifiesItselfAs1_26_40() {
        // Mojang renumbered: 1001 -> 2168, confirmed by the RequestNetworkSettings schema constraint
        // and by a live client announcing 2168.
        assertEquals(2168, Bedrock_v2168.CODEC.getProtocolVersion());
        assertEquals("1.26.40", Bedrock_v2168.CODEC.getMinecraftVersion());
    }

    @Test
    void anvilDamageNoLongerCarriesTheDamageByte() {
        AnvilDamagePacket packet = new AnvilDamagePacket();
        packet.setDamage(7);
        packet.setPosition(Vector3i.from(1, 2, 3));

        AnvilDamagePacket decoded = roundTrip(packet, AnvilDamagePacket.class);

        assertEquals(Vector3i.from(1, 2, 3), decoded.getPosition());
        // The byte is gone from the wire, so it cannot survive the trip - only the position does.
        assertEquals(0, decoded.getDamage());
    }

    @Test
    void anvilDamageIsShorterThanOnV1001() {
        AnvilDamagePacket packet = new AnvilDamagePacket();
        packet.setDamage(7);
        packet.setPosition(Vector3i.from(1, 2, 3));

        assertEquals(encodedLength(Bedrock_v1001.CODEC, packet) - 1,
                encodedLength(Bedrock_v2168.CODEC, packet),
                "1.26.40 drops exactly the leading damage byte");
    }

    @Test
    void playSoundCarriesALoopCount() {
        PlaySoundPacket packet = new PlaySoundPacket();
        packet.setSound("note.harp");
        packet.setPosition(Vector3f.from(8, 16, 24));
        packet.setVolume(0.5f);
        packet.setPitch(1.5f);
        packet.setLoopCount(3);
        packet.setServerSoundHandle(42L);

        PlaySoundPacket decoded = roundTrip(packet, PlaySoundPacket.class);

        assertEquals("note.harp", decoded.getSound());
        assertEquals(3, decoded.getLoopCount());
        // Ordering matters: loopCount lands between pitch and the handle, so a handle that survives
        // intact proves the new field did not shift the one after it.
        assertEquals(42L, decoded.getServerSoundHandle());
        assertEquals(1.5f, decoded.getPitch(), 0.0001);
    }

    @Test
    void transferGatheringsConfigurationIsOptionalAndAbsentByDefault() {
        TransferPacket packet = new TransferPacket();
        packet.setAddress("play.example.com");
        packet.setPort(19132);
        packet.setReloadWorld(true);

        TransferPacket decoded = roundTrip(packet, TransferPacket.class);

        assertEquals("play.example.com", decoded.getAddress());
        assertEquals(19132, decoded.getPort());
        assertEquals(true, decoded.isReloadWorld());
        assertNull(decoded.getGatheringsConfigurationJoinInfo());
    }

    @Test
    void soundDataIsATaggedUnionOfIndependentOptionals() {
        // 1.26.40 replaced v1001's single event string with seven independent optionals. Setting one
        // and getting exactly that one back is what proves the reader stayed in step with the writer:
        // a misaligned optional would surface as a neighbouring field coming back non-null.
        ClientboundUpdateSoundDataPacket packet = new ClientboundUpdateSoundDataPacket();
        packet.setServerSoundHandle(0x1122334455667788L);
        packet.setFade(new FadeSoundData(0.25f, 1.5f));

        ClientboundUpdateSoundDataPacket decoded = roundTrip(packet, ClientboundUpdateSoundDataPacket.class);

        assertEquals(0x1122334455667788L, decoded.getServerSoundHandle());
        assertEquals(0.25f, decoded.getFade().getTargetVolume(), 0.0001);
        assertEquals(1.5f, decoded.getFade().getDuration(), 0.0001);
        assertNull(decoded.getStop());
        assertNull(decoded.getVolume());
        assertNull(decoded.getPitch());
        assertNull(decoded.getSeekTo());
        assertNull(decoded.getPause());
        assertNull(decoded.getResume());
    }

    @Test
    void soundDataToleratesEveryCaseBeingAbsent() {
        ClientboundUpdateSoundDataPacket packet = new ClientboundUpdateSoundDataPacket();
        packet.setServerSoundHandle(1L);

        ClientboundUpdateSoundDataPacket decoded = roundTrip(packet, ClientboundUpdateSoundDataPacket.class);

        assertEquals(1L, decoded.getServerSoundHandle());
        assertNull(decoded.getFade());
    }

    @Test
    void commandOutputCarriesTheTrailingDataSetByte() {
        // A local addition beyond upstream CloudburstMC: gophertunnel writes this trailing optional
        // and a captured 1.26.40 output was 76 bytes against the 75 this tree used to produce. The
        // truncated copy reaching the client is what made /gamemode disconnect.
        CommandOutputPacket packet = new CommandOutputPacket();
        packet.setCommandOriginData(new CommandOriginData(
                CommandOriginType.PLAYER, UUID.nameUUIDFromBytes(new byte[]{1}), "", 0L));
        packet.setType(CommandOutputType.ALL_OUTPUT);
        packet.setSuccessCount(1);

        assertEquals(encodedLength(Bedrock_v1001.CODEC, packet) + 1,
                encodedLength(Bedrock_v2168.CODEC, packet),
                "1.26.40 appends exactly the absent-DataSet boolean");
    }

    private static int encodedLength(org.cloudburstmc.protocol.bedrock.codec.BedrockCodec codec,
                                     org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends org.cloudburstmc.protocol.bedrock.packet.BedrockPacket> T roundTrip(
            T packet, Class<T> type) {
        BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(helper, buffer, packet);
            return (T) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(),
                    buffer,
                    Bedrock_v2168.CODEC.getPacketDefinition(type).getId()
            );
        } finally {
            buffer.release();
        }
    }
}
