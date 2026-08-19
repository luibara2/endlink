package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockInteractionType;
import org.cloudburstmc.protocol.bedrock.data.event.ComposterInteractEventData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.EventPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A 1.26.40 backend's composter events, captured off the wire, must relay back out byte-for-byte.
 *
 * <p>Both payloads below kicked the interacting player and nobody else. The fill threw on decode and
 * was forwarded raw, which was survivable; the bone meal recovery decoded "successfully", re-encoded
 * one byte short, and the client dropped the connection with no reason. See
 * {@code EventPacket#trailingPayload} and {@code EventSerializer_v354#readCauldronInteract}.
 */
class ComposterEventRoundTripTest {
    private static final int EVENT_PACKET_ID = 65;

    private final BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();

    /** Taken from the ASYMMETRIC SERIALIZER report: 13 bytes in, 12 bytes back out. */
    private static final String RECOVERED_BONEMEAL_WIRE = "fdffffffdf800120010b141801";

    /** The same event shape with the fill's interaction type, 19, which the zigzag reader made -10. */
    private static final String COMPOST_ITEM_PLACE_WIRE = "fdffffffdf800120010b131801";

    private EventPacket decode(String hex) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            BedrockPacket packet = Bedrock_v2168.CODEC.tryDecode(helper, buffer, EVENT_PACKET_ID, null);
            return assertInstanceOf(EventPacket.class, packet);
        } finally {
            buffer.release();
        }
    }

    private String encode(EventPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(helper, buffer, packet);
            return ByteBufUtil.hexDump(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void relaysBoneMealRecoveryUnchanged() {
        EventPacket packet = decode(RECOVERED_BONEMEAL_WIRE);

        ComposterInteractEventData data =
                assertInstanceOf(ComposterInteractEventData.class, packet.getEventData());
        assertEquals(BlockInteractionType.RECOVERED_BONEMEAL, data.getBlockInteractionType());
        assertEquals(20, data.getRawBlockInteractionType());
        // The one byte the payload reader does not account for, and the whole bug.
        assertArrayEquals(new byte[]{0x01}, packet.getTrailingPayload());

        assertEquals(RECOVERED_BONEMEAL_WIRE, encode(packet));
    }

    @Test
    void relaysCompostItemPlaceUnchanged() {
        EventPacket packet = decode(COMPOST_ITEM_PLACE_WIRE);

        ComposterInteractEventData data =
                assertInstanceOf(ComposterInteractEventData.class, packet.getEventData());
        assertEquals(BlockInteractionType.COMPOST_ITEM_PLACE, data.getBlockInteractionType());
        assertEquals(19, data.getRawBlockInteractionType());

        assertEquals(COMPOST_ITEM_PLACE_WIRE, encode(packet));
    }

    /**
     * An interaction type this build has no constant for still has to reach the client intact.
     */
    @Test
    void relaysUnknownInteractionTypeUnchanged() {
        String wire = "fdffffffdf800120010b7f1801";

        EventPacket packet = decode(wire);

        ComposterInteractEventData data =
                assertInstanceOf(ComposterInteractEventData.class, packet.getEventData());
        assertEquals(BlockInteractionType.NONE, data.getBlockInteractionType());
        assertEquals(127, data.getRawBlockInteractionType());

        assertEquals(wire, encode(packet));
    }
}
