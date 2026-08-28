package org.cloudburstmc.protocol.bedrock.codec.v2169;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A scoreboard removal crossing between a 1.26.45 client and a 1.26.44 backend.
 *
 * <p>This is the deployment that matters right now: Endstone builds against 1.26.44, so a server
 * that updates its clients to 1.26.45 runs 2169 on the player's side and 2168 on the backend's. The
 * proxy is what makes that pair work, and {@code RemoveScore} is the single field the two protocols
 * disagree about — 1.26.44 writes a constant {@code true} ahead of the objective name's presence
 * flag, 1.26.45 dropped it again.
 *
 * <p>{@code SetScoreRemoveReleaseGateTest} pins the same crossing inside protocol 2168, where the
 * shape is chosen from the peer's Minecraft version. Here it comes from the protocol number instead,
 * so what is pinned is that the two codecs disagree <em>by construction</em> and that relaying
 * between them converts rather than passes through.
 */
class SetScoreRemove2169CrossingTest {
    private static final int SET_SCORE_PACKET_ID = 108;

    /** One removal of scoreboard id 7, naming no objective, as a 1.26.44 backend writes it. */
    private static final String REMOVE_1_26_44 = "01000672656d6f76650e0100";

    /** The same removal as 1.26.45 writes it: one byte shorter, no constant. */
    private static final String REMOVE_1_26_45 = "01000672656d6f76650e00";

    /** A removal that names the objective it clears. */
    private static final String REMOVE_NAMED_1_26_44 = "01000672656d6f76650e0101056d6f6e6579";
    private static final String REMOVE_NAMED_1_26_45 = "01000672656d6f76650e01056d6f6e6579";

    private static BedrockCodecHelper backendHelper() {
        BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();
        ((BedrockCodecHelper_v2168) helper).setRemoveScoreKeyedConstant(true);
        return helper;
    }

    private static BedrockCodecHelper clientHelper() {
        BedrockCodecHelper helper = Bedrock_v2169.CODEC.createHelper();
        assertInstanceOf(BedrockCodecHelper_v2169.class, helper,
                "the 2169 codec must build its own helper; inheriting 2168's would restore the constant");
        return helper;
    }

    private static SetScorePacket decode(BedrockCodec codec, String hex, BedrockCodecHelper helper) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            BedrockPacket packet = codec.tryDecode(helper, buffer, SET_SCORE_PACKET_ID, null);
            assertEquals(0, buffer.readableBytes(), "reader left bytes behind");
            return assertInstanceOf(SetScorePacket.class, packet);
        } finally {
            buffer.release();
        }
    }

    private static String encode(BedrockCodec codec, SetScorePacket packet, BedrockCodecHelper helper) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(helper, buffer, packet);
            return ByteBufUtil.hexDump(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void theRenumberedCodecAnnouncesItself() {
        assertEquals(2169, Bedrock_v2169.CODEC.getProtocolVersion());
        assertEquals("1.26.45", Bedrock_v2169.CODEC.getMinecraftVersion());
    }

    /** What a 1.26.44 backend sends down to a 1.26.45 client: read with the constant, written without. */
    @Test
    void relaysA_1_26_44_removalDownToA_1_26_45_client() {
        SetScorePacket packet = decode(Bedrock_v2168.CODEC, REMOVE_1_26_44, backendHelper());

        assertEquals(1, packet.getInfos().size());
        assertEquals(ScoreInfo.ScorerType.INVALID, packet.getInfos().get(0).getType());
        assertEquals(7, packet.getInfos().get(0).getScoreboardId());
        assertEquals("", packet.getInfos().get(0).getObjectiveId());

        assertEquals(REMOVE_1_26_45, encode(Bedrock_v2169.CODEC, packet, clientHelper()));
    }

    /** And the reverse leg, so the pair is symmetric rather than only correct downstream. */
    @Test
    void relaysA_1_26_45_removalUpToA_1_26_44_backend() {
        SetScorePacket packet = decode(Bedrock_v2169.CODEC, REMOVE_1_26_45, clientHelper());

        assertEquals(1, packet.getInfos().size());
        assertEquals("", packet.getInfos().get(0).getObjectiveId());

        assertEquals(REMOVE_1_26_44, encode(Bedrock_v2168.CODEC, packet, backendHelper()));
    }

    @Test
    void carriesTheObjectiveNameAcrossTheCrossing() {
        assertEquals(REMOVE_NAMED_1_26_45, encode(Bedrock_v2169.CODEC,
                decode(Bedrock_v2168.CODEC, REMOVE_NAMED_1_26_44, backendHelper()), clientHelper()));
        assertEquals(REMOVE_NAMED_1_26_44, encode(Bedrock_v2168.CODEC,
                decode(Bedrock_v2169.CODEC, REMOVE_NAMED_1_26_45, clientHelper()), backendHelper()));
    }

    /**
     * The conversion has to be load-bearing. Relaying a 1.26.44 removal straight through to a 1.26.45
     * client is the corrupt copy: {@code SetScore} is a broadcast, so one bad removal reaches every
     * player at once and each client closes on a bad packet with no reason attached.
     */
    @Test
    void passingTheBackendShapeThroughUnconvertedWouldCorruptIt() {
        String converted = encode(Bedrock_v2169.CODEC,
                decode(Bedrock_v2168.CODEC, REMOVE_1_26_44, backendHelper()), clientHelper());

        assertNotEquals(REMOVE_1_26_44, converted, "a 1.26.45 client must not be handed the 1.26.44 shape");
        assertEquals(REMOVE_1_26_44.length() / 2 - 1, converted.length() / 2,
                "the two shapes differ by exactly the one constant byte");
    }
}
