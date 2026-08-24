package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A scoreboard removal relayed between two peers that are on protocol 2168 but not on the same
 * Minecraft release.
 *
 * <p>Mojang changed {@code RemoveScore} at 1.26.44 — the objective name gained the
 * {@code isKeyedSetterGetter} trait, so cereal now writes a constant {@code true} in front of the
 * optional's own presence flag — and left the network version at 2168. Five releases, 1.26.40
 * through 1.26.44, therefore negotiate the same number and write two different byte layouts, and a
 * proxy is the one participant that routinely has one of each on its two ends.
 *
 * <p>{@link SetScoreRemoveRoundTripTest} pins each shape against a peer that agrees with it. This
 * pins the crossing, which is the case that actually broke: a 1.26.44 backend feeding a 1.26.40
 * client, and the reverse.
 */
class SetScoreRemoveReleaseGateTest {
    private static final int SET_SCORE_PACKET_ID = 108;

    /** One removal of scoreboard id 7, naming no objective, as 1.26.44 writes it. */
    private static final String REMOVE_1_26_44 = "01000672656d6f76650e0100";

    /** The same removal as 1.26.40 through 1.26.43 write it: one byte shorter, no constant. */
    private static final String REMOVE_1_26_40 = "01000672656d6f76650e00";

    /** A removal that names the objective it clears, in both shapes. */
    private static final String REMOVE_NAMED_1_26_44 = "01000672656d6f76650e0101056d6f6e6579";
    private static final String REMOVE_NAMED_1_26_40 = "01000672656d6f76650e01056d6f6e6579";

    /**
     * A removal followed by a fake-player score. The second entry only lands where it should if the
     * first consumed exactly the right number of bytes, so this is what proves a mis-set gate
     * derails the whole array rather than just shifting one field.
     */
    private static final String REMOVE_THEN_FAKE_1_26_44 =
            "02000672656d6f76650e0100"
                    + "03106368616e676566616b65706c617965721005" + "6d6f6e6579" + "03000000" + "03626f62";
    private static final String REMOVE_THEN_FAKE_1_26_40 =
            "02000672656d6f76650e00"
                    + "03106368616e676566616b65706c617965721005" + "6d6f6e6579" + "03000000" + "03626f62";

    private static BedrockCodecHelper helperFor(boolean keyedConstant) {
        BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();
        assertInstanceOf(BedrockCodecHelper_v2168.class, helper,
                "the gate lives on the v2168 helper; a different helper silently disables it");
        ((BedrockCodecHelper_v2168) helper).setRemoveScoreKeyedConstant(keyedConstant);
        return helper;
    }

    private static SetScorePacket decode(String hex, BedrockCodecHelper helper) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            BedrockPacket packet = Bedrock_v2168.CODEC.tryDecode(helper, buffer, SET_SCORE_PACKET_ID, null);
            assertEquals(0, buffer.readableBytes(), "reader left bytes behind");
            return assertInstanceOf(SetScorePacket.class, packet);
        } finally {
            buffer.release();
        }
    }

    private static String encode(SetScorePacket packet, BedrockCodecHelper helper) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(helper, buffer, packet);
            return ByteBufUtil.hexDump(buffer);
        } finally {
            buffer.release();
        }
    }

    /** What a 1.26.44 backend sends to a 1.26.40 client: read with the constant, written without. */
    @Test
    void relaysA_1_26_44_removalDownToA_1_26_40_client() {
        BedrockCodecHelper backend = helperFor(true);
        BedrockCodecHelper client = helperFor(false);

        SetScorePacket packet = decode(REMOVE_1_26_44, backend);
        assertEquals(1, packet.getInfos().size());
        assertEquals(ScoreInfo.ScorerType.INVALID, packet.getInfos().get(0).getType());
        assertEquals(7, packet.getInfos().get(0).getScoreboardId());
        assertEquals("", packet.getInfos().get(0).getObjectiveId());

        assertEquals(REMOVE_1_26_40, encode(packet, client));
    }

    /** And the reverse: a 1.26.40 backend feeding a 1.26.44 client, which is what live ran into. */
    @Test
    void relaysA_1_26_40_removalUpToA_1_26_44_client() {
        BedrockCodecHelper backend = helperFor(false);
        BedrockCodecHelper client = helperFor(true);

        SetScorePacket packet = decode(REMOVE_1_26_40, backend);
        assertEquals(1, packet.getInfos().size());
        assertEquals("", packet.getInfos().get(0).getObjectiveId());

        assertEquals(REMOVE_1_26_44, encode(packet, client));
    }

    @Test
    void carriesTheObjectiveNameAcrossTheCrossing() {
        assertEquals(REMOVE_NAMED_1_26_40,
                encode(decode(REMOVE_NAMED_1_26_44, helperFor(true)), helperFor(false)));
        assertEquals(REMOVE_NAMED_1_26_44,
                encode(decode(REMOVE_NAMED_1_26_40, helperFor(false)), helperFor(true)));

        SetScorePacket packet = decode(REMOVE_NAMED_1_26_44, helperFor(true));
        assertEquals("money", packet.getInfos().get(0).getObjectiveId());
    }

    @Test
    void keepsLaterEntriesAlignedAcrossTheCrossing() {
        SetScorePacket packet = decode(REMOVE_THEN_FAKE_1_26_44, helperFor(true));

        assertEquals(2, packet.getInfos().size());
        ScoreInfo fake = packet.getInfos().get(1);
        assertEquals(ScoreInfo.ScorerType.FAKE, fake.getType());
        assertEquals(8, fake.getScoreboardId());
        assertEquals("money", fake.getObjectiveId());
        assertEquals(3, fake.getScore());
        assertEquals("bob", fake.getName());

        assertEquals(REMOVE_THEN_FAKE_1_26_40, encode(packet, helperFor(false)));
    }

    /**
     * The gate has to be load-bearing, or the tests above would pass with it stubbed out. Relaying
     * with both helpers on the same setting leaves the removal in the sender's shape, which is
     * precisely the corrupt copy that disconnected everyone: {@code SetScore} is a broadcast, so one
     * removal reached every player on the backend at once, and the client closes on a bad packet
     * with no reason attached.
     */
    @Test
    void anUngatedRelayReproducesTheCorruption() {
        BedrockCodecHelper ungated = helperFor(true);

        String relayed = encode(decode(REMOVE_1_26_44, ungated), ungated);

        assertEquals(REMOVE_1_26_44, relayed);
        assertNotEquals(REMOVE_1_26_40, relayed,
                "a 1.26.40 client must not be handed the 1.26.44 shape");
        assertEquals(REMOVE_1_26_44.length() / 2 - 1, REMOVE_1_26_40.length() / 2,
                "the two shapes differ by exactly the one constant byte");
    }

    /** A helper nobody told defaults to the current release rather than to the older shape. */
    @Test
    void defaultsToTheCurrentRelease() {
        BedrockCodecHelper untouched = Bedrock_v2168.CODEC.createHelper();
        assertEquals(REMOVE_1_26_44, encode(decode(REMOVE_1_26_44, untouched), untouched));
    }
}
