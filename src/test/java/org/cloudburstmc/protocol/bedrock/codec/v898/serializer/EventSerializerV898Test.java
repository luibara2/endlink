package org.cloudburstmc.protocol.bedrock.codec.v898.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.data.event.EventDataType;
import org.cloudburstmc.protocol.bedrock.data.event.SlashCommandExecutedEventData;
import org.cloudburstmc.protocol.bedrock.packet.EventPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventSerializerV898Test {

    private static EventPacket slashCommandEvent() {
        EventPacket packet = new EventPacket();
        packet.setUniqueEntityId(123L);
        packet.setUsePlayerId(false);
        packet.setPayloadType(4);
        packet.setEventData(new SlashCommandExecutedEventData("say", 1, List.of("test")));
        return packet;
    }

    @Test
    void writesSlashCommandEventWithoutDuplicateEventType() {
        EventPacket packet = slashCommandEvent();

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            EventSerializer_v898.INSTANCE.serialize(buffer, helper, packet);

            assertEquals(123L, VarInts.readLong(buffer));
            assertEquals(EventDataType.SLASH_COMMAND_EXECUTED.ordinal(), VarInts.readInt(buffer));
            assertFalse(buffer.readBoolean());
            // The oneOf payload discriminator sits between the header and the event body.
            assertEquals(4, VarInts.readUnsignedInt(buffer));
            assertEquals(1, VarInts.readInt(buffer));
            assertEquals(1, VarInts.readInt(buffer));
            assertEquals("say", helper.readString(buffer));
            assertEquals("test", helper.readString(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    /**
     * The proxy decodes a backend event and re-encodes it for the client. Dropping the oneOf
     * discriminator from both halves is self-consistent but does not match the wire: the body is then
     * parsed one varint late and the relayed copy leaves the client a byte short. A death is an
     * {@code EventPacket}, which is why this surfaced as "disconnects on death".
     */
    @Test
    void roundTripsThePayloadDiscriminator() {
        EventPacket original = slashCommandEvent();

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf wire = Unpooled.buffer();
        ByteBuf reencoded = Unpooled.buffer();
        try {
            EventSerializer_v898.INSTANCE.serialize(wire, helper, original);
            byte[] wireBytes = new byte[wire.readableBytes()];
            wire.getBytes(wire.readerIndex(), wireBytes);

            EventPacket decoded = new EventPacket();
            EventSerializer_v898.INSTANCE.deserialize(wire, helper, decoded);

            assertEquals(4, decoded.getPayloadType(), "discriminator must survive decoding");
            assertEquals(0, wire.readableBytes(), "reader must consume the whole event");

            EventSerializer_v898.INSTANCE.serialize(reencoded, helper, decoded);
            byte[] reencodedBytes = new byte[reencoded.readableBytes()];
            reencoded.getBytes(reencoded.readerIndex(), reencodedBytes);

            assertArrayEquals(wireBytes, reencodedBytes, "a proxy must relay events byte-for-byte");
        } finally {
            wire.release();
            reencoded.release();
        }
    }
}
