package org.endstone.proxy.security;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the pre-auth allocation budget.
 *
 * <p>The 10 MB decompression cap bounds a batch's bytes but not the work they buy: the login
 * serializer hands the raw, unverified auth JWT to {@code JsonUtil.parseJson}, measured at ~79 bytes
 * of heap per source byte. ~10 KB of deflated zeros reaches the 10 MB cap, so an unauthenticated
 * client could turn one batch into several hundred megabytes of heap on an I/O thread. The limiter
 * bounds the input for as long as the peer is anonymous, and lifts once the login succeeds.
 */
class PreAuthBatchLimiterTest {

    private static final int LIMIT = 4096;

    private static BedrockBatchWrapper batchOf(int uncompressedBytes) {
        ByteBuf uncompressed = Unpooled.buffer(uncompressedBytes);
        uncompressed.writeZero(uncompressedBytes);
        return BedrockBatchWrapper.newInstance(null, uncompressed);
    }

    /** One length-prefixed packet with the given id, padded so the whole batch is {@code totalBytes}. */
    private static BedrockBatchWrapper singlePacketBatch(int packetId, int totalBytes) {
        ByteBuf uncompressed = Unpooled.buffer(totalBytes);
        int bodyLength = totalBytes - 3; // three-byte length varint for every size these tests use
        VarInts.writeUnsignedInt(uncompressed, bodyLength);
        if (uncompressed.writerIndex() != 3) {
            throw new IllegalArgumentException("pick a size whose length prefix is three bytes");
        }
        VarInts.writeUnsignedInt(uncompressed, packetId);
        uncompressed.writeZero(totalBytes - uncompressed.writerIndex());
        return BedrockBatchWrapper.newInstance(null, uncompressed);
    }

    private static void assertRefused(EmbeddedChannel channel, BedrockBatchWrapper batch) {
        Throwable thrown = assertThrows(Throwable.class, () -> channel.writeInbound(batch));
        assertInstanceOf(TooLongFrameException.class, thrown, "expected a refusal, got " + thrown);
        assertEquals(0, batch.refCnt(), "the refused batch must be released, not leaked");
    }

    private static final int LOGIN_LIMIT = LIMIT * 16;

    @Test
    void allowsOneLargeLoginBatch() {
        // Custom skins with geometry make the login batch several megabytes.
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, LOGIN_LIMIT, () -> false));
        assertTrue(channel.writeInbound(singlePacketBatch(1, LOGIN_LIMIT)), "a large login must pass");
        BedrockBatchWrapper forwarded = channel.readInbound();
        assertEquals(LOGIN_LIMIT, forwarded.getUncompressed().readableBytes());
        forwarded.release();

        // Once only: a second large batch while still anonymous is the attack shape again.
        assertRefused(channel, singlePacketBatch(1, LOGIN_LIMIT));
        channel.finishAndReleaseAll();
    }

    @Test
    void largeAllowanceIsForLoginPacketsOnly() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, LOGIN_LIMIT, () -> false));
        assertRefused(channel, singlePacketBatch(94, LOGIN_LIMIT)); // SubClientLogin
        assertRefused(channel, singlePacketBatch(193, LOGIN_LIMIT)); // RequestNetworkSettings
        channel.finishAndReleaseAll();
    }

    @Test
    void largeAllowanceIsForASingleLoginPacketOnly() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, LOGIN_LIMIT, () -> false));
        // A login followed by anything else: the length prefix no longer spans the batch.
        BedrockBatchWrapper padded = singlePacketBatch(1, LOGIN_LIMIT - 64);
        padded.getUncompressed().writeZero(64);
        assertRefused(channel, padded);
        channel.finishAndReleaseAll();
    }

    @Test
    void loginAboveTheLoginLimitIsRefused() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, LOGIN_LIMIT, () -> false));
        assertRefused(channel, singlePacketBatch(1, LOGIN_LIMIT + 1));
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsAnOversizedBatchBeforeLogin() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, () -> false));
        BedrockBatchWrapper batch = batchOf(LIMIT + 1);

        Throwable thrown = assertThrows(Throwable.class, () -> channel.writeInbound(batch));
        assertInstanceOf(TooLongFrameException.class, thrown,
                "an oversized pre-login batch must be refused, got " + thrown);
        assertEquals(0, batch.refCnt(), "the refused batch must be released, not leaked");
        assertFalse(channel.finish(), "nothing may reach the decoder");
    }

    @Test
    void allowsAnOrdinaryLoginSizedBatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, () -> false));
        assertTrue(channel.writeInbound(batchOf(LIMIT)), "a login-sized batch must pass");
        BedrockBatchWrapper forwarded = channel.readInbound();
        assertEquals(LIMIT, forwarded.getUncompressed().readableBytes());
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void limitLiftsOnceTheSessionIsAuthenticated() {
        AtomicBoolean authenticated = new AtomicBoolean(false);
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(LIMIT, authenticated::get));

        // Gameplay batches are the genuinely large ones and must never be measured against the
        // pre-auth budget.
        authenticated.set(true);
        assertTrue(channel.writeInbound(batchOf(LIMIT * 64)), "a post-login batch must pass");
        BedrockBatchWrapper forwarded = channel.readInbound();
        assertEquals(LIMIT * 64, forwarded.getUncompressed().readableBytes());
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void zeroDisablesTheCheck() {
        EmbeddedChannel channel = new EmbeddedChannel(new PreAuthBatchLimiter(0, () -> false));
        assertTrue(channel.writeInbound(batchOf(LIMIT * 64)));
        BedrockBatchWrapper forwarded = channel.readInbound();
        forwarded.release();
        channel.finishAndReleaseAll();
    }
}
