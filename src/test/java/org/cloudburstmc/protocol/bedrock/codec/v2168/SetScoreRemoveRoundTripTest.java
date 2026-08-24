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

/**
 * A 1.26.44 backend's scoreboard removals must relay back out byte-for-byte.
 *
 * <p>A {@code RemoveScore} entry carries a constant {@code true} between its scoreboard id and its
 * optional objective name — {@code "type": "bool", "value": true} in the r26_u4 schema dump, which
 * documents 1.26.44.3 at network version 2168. The serializer used not to write it, and the failure
 * was silent in both directions: the reader took the constant as the optional's presence flag and
 * the {@code 0x00} that follows as a zero-length objective name, so decoding "succeeded" and the
 * relayed copy came out exactly one byte short per removal.
 *
 * <p>{@code SetScorePacket} is a broadcast, so that one byte disconnected every player on the
 * backend at once, with {@code BadPacket} and no disconnect reason — the same shape of bug as
 * {@link ComposterEventRoundTripTest}, but hitting everybody instead of one player.
 */
class SetScoreRemoveRoundTripTest {
    private static final int SET_SCORE_PACKET_ID = 108;

    private final BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();

    /** One removal, no objective name: {@code 1 | Remove | "remove" | id 7 | true | absent}. */
    private static final String REMOVE_WITHOUT_OBJECTIVE = "01000672656d6f76650e0100";

    /** The same removal naming the objective it clears. */
    private static final String REMOVE_WITH_OBJECTIVE = "01000672656d6f76650e0101056d6f6e6579";

    /**
     * A removal followed by a fake-player score in one packet. The second entry only lands where it
     * should if the first consumed the constant, so this is what proves the whole array stays
     * aligned rather than just the single-entry case.
     */
    private static final String REMOVE_THEN_FAKE_PLAYER =
            "02" + "000672656d6f76650e0100"
                    + "03106368616e676566616b65706c617965721005" + "6d6f6e6579" + "03000000" + "03626f62";

    private SetScorePacket decode(String hex) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            BedrockPacket packet = Bedrock_v2168.CODEC.tryDecode(helper, buffer, SET_SCORE_PACKET_ID, null);
            assertEquals(0, buffer.readableBytes(), "reader left bytes behind");
            return assertInstanceOf(SetScorePacket.class, packet);
        } finally {
            buffer.release();
        }
    }

    private String encode(SetScorePacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(helper, buffer, packet);
            return ByteBufUtil.hexDump(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void relaysRemovalWithoutObjectiveUnchanged() {
        SetScorePacket packet = decode(REMOVE_WITHOUT_OBJECTIVE);

        assertEquals(1, packet.getInfos().size());
        ScoreInfo info = packet.getInfos().get(0);
        assertEquals(ScoreInfo.ScorerType.INVALID, info.getType());
        assertEquals(7, info.getScoreboardId());
        assertEquals("", info.getObjectiveId());

        assertEquals(REMOVE_WITHOUT_OBJECTIVE, encode(packet));
    }

    @Test
    void relaysRemovalWithObjectiveUnchanged() {
        SetScorePacket packet = decode(REMOVE_WITH_OBJECTIVE);

        assertEquals(1, packet.getInfos().size());
        ScoreInfo info = packet.getInfos().get(0);
        assertEquals(ScoreInfo.ScorerType.INVALID, info.getType());
        assertEquals(7, info.getScoreboardId());
        assertEquals("money", info.getObjectiveId());

        assertEquals(REMOVE_WITH_OBJECTIVE, encode(packet));
    }

    @Test
    void keepsLaterEntriesAlignedAfterARemoval() {
        SetScorePacket packet = decode(REMOVE_THEN_FAKE_PLAYER);

        assertEquals(2, packet.getInfos().size());
        assertEquals(ScoreInfo.ScorerType.INVALID, packet.getInfos().get(0).getType());

        ScoreInfo fake = packet.getInfos().get(1);
        assertEquals(ScoreInfo.ScorerType.FAKE, fake.getType());
        assertEquals(8, fake.getScoreboardId());
        assertEquals("money", fake.getObjectiveId());
        assertEquals(3, fake.getScore());
        assertEquals("bob", fake.getName());

        assertEquals(REMOVE_THEN_FAKE_PLAYER, encode(packet));
    }
}
