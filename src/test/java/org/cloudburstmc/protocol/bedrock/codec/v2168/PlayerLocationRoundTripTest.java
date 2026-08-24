package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.PacketValidationException;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerLocationPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The locator-bar update, whose hide variant used to be encoded two different ways and neither of
 * them right.
 *
 * <p>{@code PlayerLocationPacket} writes its variant twice in two <em>different</em> encodings —
 * {@code uvarint32}, then zigzag {@code varint32}. So {@code HIDE} is {@code 01 02} and
 * {@code COORDINATES} is {@code 00 00}. The {@code r26_u4} dump labels both fields {@code varint32},
 * and believing it gives {@code 02 02}, which the client refuses; gophertunnel's
 * {@code player_location.go} has the real pair.
 *
 * <p>The second field used to be written as a hardcoded zero. A decoder assigns the variant from the
 * first tag and then overwrites it from the second, so a hide announced itself and immediately
 * claimed to be coordinates — and the recipient went looking for a Vec3 that was never written, ran
 * off the end of the packet, and closed the connection with {@code BadPacket} and no message.
 *
 * <p>{@code COORDINATES} is 0 in both encodings, and the old hardcoded zero was also 0, which is why
 * the common case always worked and only hiding a player from the locator bar ever broke.
 */
class PlayerLocationRoundTripTest {
    private static final int PLAYER_LOCATION_PACKET_ID = 326;

    private final BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();

    /** actor id 7, HIDE: {@code 0e | 01 | 02} - uvarint32 1, then zigzag varint32 1. */
    private static final String HIDE_WIRE = "0e0102";

    /** actor id 7, COORDINATES at (1, 2, 3): {@code 0e | 00 | 00 | three LE floats}. */
    private static final String COORDINATES_WIRE = "0e0000" + "0000803f" + "00000040" + "00004040";

    private PlayerLocationPacket decode(String hex) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            BedrockPacket packet =
                    Bedrock_v2168.CODEC.tryDecode(helper, buffer, PLAYER_LOCATION_PACKET_ID, null);
            assertEquals(0, buffer.readableBytes(), "reader left bytes behind");
            return assertInstanceOf(PlayerLocationPacket.class, packet);
        } finally {
            buffer.release();
        }
    }

    private String encode(PlayerLocationPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(helper, buffer, packet);
            return ByteBufUtil.hexDump(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void relaysAHideUnchanged() {
        PlayerLocationPacket packet = decode(HIDE_WIRE);

        assertEquals(PlayerLocationPacket.Type.HIDE, packet.getType());
        assertEquals(7, packet.getTargetEntityId());

        assertEquals(HIDE_WIRE, encode(packet));
    }

    @Test
    void relaysCoordinatesUnchanged() {
        PlayerLocationPacket packet = decode(COORDINATES_WIRE);

        assertEquals(PlayerLocationPacket.Type.COORDINATES, packet.getType());
        assertEquals(7, packet.getTargetEntityId());
        assertEquals(1.0f, packet.getPosition().getX());
        assertEquals(2.0f, packet.getPosition().getY());
        assertEquals(3.0f, packet.getPosition().getZ());

        assertEquals(COORDINATES_WIRE, encode(packet));
    }

    /**
     * The bug this fixes: the second field was a hardcoded zero, so a hide announced itself and then
     * said "coordinates". Pinned by bytes so it cannot come back.
     */
    @Test
    void neverEmitsAHideWithAZeroedSecondField() {
        PlayerLocationPacket hide = new PlayerLocationPacket();
        hide.setTargetEntityId(7);
        hide.setType(PlayerLocationPacket.Type.HIDE);

        String encoded = encode(hide);

        assertEquals(HIDE_WIRE, encoded);
        assertTrue(!"0e0100".equals(encoded), "the hardcoded-zero second field is back");
    }

    /**
     * {@code 01 00} - the exact bytes seen on the wire from a plugin that hand-assembles this packet.
     * A decoder takes the second field as authoritative, so this announces HIDE and then claims
     * COORDINATES, and the recipient goes looking for a Vec3 that was never written. It must be
     * refused here rather than passed on to end someone's session.
     */
    @Test
    void refusesAHideWhoseSecondFieldSaysCoordinates() {
        Exception thrown = assertThrows(Exception.class, () -> decode("0e0100"));

        assertTrue(PacketValidationException.isValidationFailure(thrown),
                "a mismatched double encoding is a validation failure, not an unmodelled packet: " + thrown);
    }

    /**
     * {@code 02 02} - what taking the schema dump literally produces, since it labels both fields
     * {@code varint32}. The first is unsigned, so 2 is simply out of range.
     */
    @Test
    void refusesAZigzaggedFirstTag() {
        Exception thrown = assertThrows(Exception.class, () -> decode("0e0202"));

        assertTrue(PacketValidationException.isValidationFailure(thrown), String.valueOf(thrown));
    }

    /** Both directions of the round trip, so an encoder change cannot drift from the decoder. */
    @Test
    void everyVariantSurvivesARoundTrip() {
        for (PlayerLocationPacket.Type type : PlayerLocationPacket.Type.values()) {
            PlayerLocationPacket packet = new PlayerLocationPacket();
            packet.setTargetEntityId(-4157528159026L);
            packet.setType(type);
            if (type == PlayerLocationPacket.Type.COORDINATES) {
                packet.setPosition(org.cloudburstmc.math.vector.Vector3f.from(1.5f, -2.5f, 3.5f));
            }

            PlayerLocationPacket decoded = decode(encode(packet));

            assertEquals(type, decoded.getType());
            assertEquals(-4157528159026L, decoded.getTargetEntityId());
            if (type == PlayerLocationPacket.Type.COORDINATES) {
                assertEquals(packet.getPosition(), decoded.getPosition());
            }
        }
    }
}
