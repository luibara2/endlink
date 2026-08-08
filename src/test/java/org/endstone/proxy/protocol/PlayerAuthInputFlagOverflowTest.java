package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 1.26.40 client moving on a 1.26.30 backend was disconnected within seconds — immediately on
 * moving, and in creative it looked like flying.
 *
 * <p>Pre-1.26.40 the input flags are packed into a single 64-bit word, one bit per enum ordinal.
 * 1.26.40 grew {@link PlayerAuthInputData} to 66 constants, and Java defines {@code 1L << 64} as
 * {@code 1L << 0} rather than zero. So {@code SNEAK_CURRENT_RAW} (ordinal 64) set {@code ASCEND} and
 * {@code INTERNAL_UPDATE} (65) set {@code DESCEND}. The {@code *_RAW} flags are sent every tick by a
 * modern client, so the backend was told the player was ascending and descending continuously from
 * the first moment they moved.</p>
 */
class PlayerAuthInputFlagOverflowTest {

    @Test
    void theEnumHasOutgrownASingleWordSoTheGuardIsLoadBearing() {
        // If this ever fails, the overflow guard has become unnecessary — but until then, removing
        // it silently reintroduces phantom ascend/descend input.
        assertTrue(PlayerAuthInputData.values().length > Long.SIZE,
                "PlayerAuthInputData fits in 64 bits again; re-check the bitset serializers");
        assertTrue(PlayerAuthInputData.SNEAK_CURRENT_RAW.ordinal() >= Long.SIZE);
    }

    @Test
    void highFlagsAreDroppedRatherThanWrappingOntoAscendAndDescend() {
        PlayerAuthInputPacket packet = movementInput();
        packet.getInputData().add(PlayerAuthInputData.SNEAK_CURRENT_RAW); // ordinal 64 -> would be ASCEND
        packet.getInputData().add(PlayerAuthInputData.INTERNAL_UPDATE);   // ordinal 65 -> would be DESCEND

        PlayerAuthInputPacket relayed = hop(packet);

        assertFalse(relayed.getInputData().contains(PlayerAuthInputData.ASCEND),
                "a raw sneak flag must not arrive at the backend as ASCEND");
        assertFalse(relayed.getInputData().contains(PlayerAuthInputData.DESCEND),
                "INTERNAL_UPDATE must not arrive at the backend as DESCEND");
    }

    @Test
    void flagsThatDoFitStillSurviveTheHop() {
        PlayerAuthInputPacket packet = movementInput();
        packet.getInputData().add(PlayerAuthInputData.UP);
        packet.getInputData().add(PlayerAuthInputData.SNEAKING);

        PlayerAuthInputPacket relayed = hop(packet);

        assertTrue(relayed.getInputData().contains(PlayerAuthInputData.UP));
        assertTrue(relayed.getInputData().contains(PlayerAuthInputData.SNEAKING));
    }

    /**
     * Decoding a 1.26.30 packet must not invent the high flags either: testing {@code 1L << 64} reads
     * bit 0, so every packet that merely set ASCEND used to come back carrying SNEAK_CURRENT_RAW.
     */
    @Test
    void decodingAscendDoesNotInventARawSneakFlag() {
        PlayerAuthInputPacket packet = movementInput();
        packet.getInputData().add(PlayerAuthInputData.ASCEND);

        PlayerAuthInputPacket decoded = roundTrip(Bedrock_v1001.CODEC, packet);

        assertTrue(decoded.getInputData().contains(PlayerAuthInputData.ASCEND));
        assertFalse(decoded.getInputData().contains(PlayerAuthInputData.SNEAK_CURRENT_RAW),
                "bit 0 must not also register as ordinal 64");
    }

    /**
     * The 1.26.30 field is a {@code std::bitset<65>}: ordinals 0-64, which gophertunnel pins as
     * {@code PlayerAuthInputBitsetSize = 65} and its 1.26.40 branch deletes. So ordinal 64
     * {@code SNEAK_CURRENT_RAW} is part of that wire and must survive the hop, while ordinal 65
     * {@code INTERNAL_UPDATE} is 1.26.40-only and must not.
     *
     * <p>Note what this pins that the tests above do not. They assert only that {@code ASCEND} and
     * {@code DESCEND} are not invented, which the 1001 path satisfies for free: it does not pack into
     * a 64-bit word at all, it goes through {@code writeLargeVarIntFlags} and a {@code BigInteger},
     * so nothing there can ever wrap onto bit 0. The word-overflow guard those tests describe lives
     * in {@code PlayerAuthInputSerializer_v748}, which protocol 1001 does not use. What the 1001 path
     * really gets wrong is the opposite end: {@code writeLargeVarIntFlags} has no upper bound, so it
     * sizes the bitset by however many constants the enum has today rather than by the version being
     * written to, and one out-of-range bit makes the backend reject the whole packet.
     */
    @Test
    void theBackendGetsEveryFlagItsBitsetDefinesAndNoneItDoesNot() {
        PlayerAuthInputPacket packet = movementInput();
        packet.getInputData().add(PlayerAuthInputData.SNEAK_CURRENT_RAW); // ordinal 64 — within bitset<65>
        packet.getInputData().add(PlayerAuthInputData.INTERNAL_UPDATE);   // ordinal 65 — 1.26.40 only

        PlayerAuthInputPacket relayed = hop(packet);

        assertTrue(relayed.getInputData().contains(PlayerAuthInputData.SNEAK_CURRENT_RAW),
                "ordinal 64 is inside the bitset<65> a 1.26.30 backend reads, so withholding it hides "
                        + "the player's raw sneak state — which drives collision height and speed, the "
                        + "things a server-authoritative client reconciles against");
        assertFalse(relayed.getInputData().contains(PlayerAuthInputData.INTERNAL_UPDATE),
                "ordinal 65 does not exist before 1.26.40; sending it makes the bitset 66 bits wide "
                        + "and the backend rejects the packet outright");
    }

    /**
     * The property the fix actually rests on, asserted on the bytes rather than on a round trip
     * through our own reader — which is lenient about width and would not notice.
     */
    @Test
    void theEncodedBitsetNeverRunsPastTheSixtyFiveBitsThisVersionDefines() {
        PlayerAuthInputPacket packet = movementInput();
        for (PlayerAuthInputData flag : PlayerAuthInputData.values()) {
            // Every flag at once, including the ones that gate optional trailing fields, is not a
            // shape worth encoding; take the flags that only affect the bitset's width.
            if (flag.ordinal() >= 60) {
                packet.getInputData().add(flag);
            }
        }

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(Bedrock_v1001.CODEC.createHelper(), buffer, packet);
            // Skip the packet id, then the six floats of rotation/position/motion that precede it.
            org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
            buffer.skipBytes(Float.BYTES * 8);
            int bits = 0;
            while (true) {
                int read = buffer.readUnsignedByte();
                bits += 7;
                if ((read & 0x80) == 0) {
                    // The final group carries at most Integer.SIZE - numberOfLeadingZeros bits.
                    bits = bits - 7 + (32 - Integer.numberOfLeadingZeros(read & 0x7f));
                    break;
                }
            }
            assertTrue(bits <= 65,
                    "the input bitset encoded to " + bits + " bits; a 1.26.30 reader rejects anything "
                            + "past 65 and drops the whole PlayerAuthInput");
        } finally {
            buffer.release();
        }
    }

    /** Encodes as a 1.26.40 client would, then re-encodes for a 1.26.30 backend and reads it back. */
    private static PlayerAuthInputPacket hop(PlayerAuthInputPacket packet) {
        PlayerAuthInputPacket fromClient = roundTrip(Bedrock_v2168.CODEC, packet);
        return roundTrip(Bedrock_v1001.CODEC, fromClient);
    }

    private static PlayerAuthInputPacket roundTrip(BedrockCodec codec, PlayerAuthInputPacket packet) {
        int id = codec.getPacketDefinition(PlayerAuthInputPacket.class).getId();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return (PlayerAuthInputPacket) codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static PlayerAuthInputPacket movementInput() {
        PlayerAuthInputPacket packet = new PlayerAuthInputPacket();
        packet.setRotation(Vector3f.from(1f, 2f, 3f));
        packet.setPosition(Vector3f.from(10f, 70f, 20f));
        packet.setMotion(Vector2f.from(0.1f, 0.2f));
        packet.setInputMode(InputMode.MOUSE);
        packet.setPlayMode(ClientPlayMode.NORMAL);
        packet.setInputInteractionModel(InputInteractionModel.CLASSIC);
        packet.setInteractRotation(Vector2f.from(0f, 0f));
        packet.setTick(1234L);
        packet.setDelta(Vector3f.ZERO);
        packet.setAnalogMoveVector(Vector2f.ZERO);
        packet.setCameraOrientation(Vector3f.ZERO);
        packet.setRawMoveVector(Vector2f.ZERO);
        return packet;
    }
}
