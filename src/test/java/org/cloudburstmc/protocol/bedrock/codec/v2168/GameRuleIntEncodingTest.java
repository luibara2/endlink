package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.26.40 writes an integer game rule as a fixed 4-byte little-endian int32; 1.26.30 wrote a zigzag
 * varint. Booleans and floats are unchanged.
 *
 * <p>This one bug took out StartGame completely — the gamerule array sits early in the level
 * settings, so the desync corrupted every field after it. It stayed hidden until an integer rule
 * appeared: in a vanilla world the first seventeen rules are all booleans.</p>
 */
class GameRuleIntEncodingTest {

    /**
     * The first integer rule from a real 1.26.40 StartGame: name, editable, type 2, then the value.
     * As int32 this is {@code playerWaypoints = 1} followed by the next rule's 10-byte name
     * {@code locatorbar}; read as a varint it yields -1 and lands three bytes short, which is
     * exactly how the live decode failed.
     */
    private static final String PLAYER_WAYPOINTS_THEN_LOCATORBAR =
            "0f706c61796572576179706f696e747301" + "02" + "01000000"
                    + "0a6c6f6361746f7262617201" + "01" + "01";

    private static BedrockCodecHelper helper() {
        return Bedrock_v2168.CODEC.createHelper();
    }

    @Test
    void readsAnIntegerRuleAsFourBytes() {
        ByteBuf buffer = Unpooled.wrappedBuffer(hex(PLAYER_WAYPOINTS_THEN_LOCATORBAR));
        try {
            GameRuleData<?> waypoints = helper().readGameRuleInStartGame(buffer);

            assertEquals("playerWaypoints", waypoints.getName());
            assertEquals(1, waypoints.getValue());
            // Landing on the next rule is the real assertion: a varint read would stop three bytes
            // early and the following name would decode as garbage.
            GameRuleData<?> next = helper().readGameRuleInStartGame(buffer);
            assertEquals("locatorbar", next.getName());
            assertEquals(true, next.getValue());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void v1001StillReadsTheOldVarintForm() {
        // The change is version-scoped, not a correction of a long-standing bug: 1.26.30 backends
        // are still on the wire and must keep working.
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.createHelper().writeGameRuleInStartGame(
                    buffer, new GameRuleData<>("randomTickSpeed", true, 1));
            // varint 1 is one byte; int32 would be four.
            int v1001Length = buffer.readableBytes();

            ByteBuf modern = Unpooled.buffer();
            try {
                helper().writeGameRuleInStartGame(modern, new GameRuleData<>("randomTickSpeed", true, 1));
                assertEquals(v1001Length + 3, modern.readableBytes());
            } finally {
                modern.release();
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsEveryValueType() {
        for (GameRuleData<?> rule : new GameRuleData<?>[]{
                new GameRuleData<>("keepInventory", true, false),
                new GameRuleData<>("maxCommandChainLength", true, 65535),
                new GameRuleData<>("someFloatRule", true, 0.5f)
        }) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                helper().writeGameRuleInStartGame(buffer, rule);
                GameRuleData<?> decoded = helper().readGameRuleInStartGame(buffer);

                assertEquals(rule.getName(), decoded.getName());
                assertEquals(rule.getValue(), decoded.getValue());
                assertEquals(0, buffer.readableBytes(), rule.getName() + " left bytes behind");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void namesTheOffendingRuleWhenTheTypeIsUnknown() {
        // The old message was a bare "Invalid gamerule type received", which said nothing about
        // where in a 39-rule array the decode went wrong.
        ByteBuf buffer = Unpooled.wrappedBuffer(hex("046a756e6b01" + "07"));
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> helper().readGameRuleInStartGame(buffer));
            assertTrue(failure.getMessage().contains("junk"), failure.getMessage());
            assertTrue(failure.getMessage().contains("7"), failure.getMessage());
        } finally {
            buffer.release();
        }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
