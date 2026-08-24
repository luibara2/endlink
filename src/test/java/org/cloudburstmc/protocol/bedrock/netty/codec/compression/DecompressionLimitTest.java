package org.cloudburstmc.protocol.bedrock.netty.codec.compression;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.common.util.Zlib;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decompression cap is per-channel, and a backend's pipeline is allowed a larger one.
 *
 * <p>It used to be one static value shared by every connection in the process. That silently made
 * the anti-zip-bomb bound — which exists for anonymous clients — apply to backends too, and a
 * heavily modded backend's join batch goes past it. The batch threw out of the decoder, the backend
 * connection died mid-join, and the player was failed over with {@code disconnect.lost} and nothing
 * to explain it. These tests pin both halves: the bound still holds where it is meant to, and it can
 * be lifted where it is not.
 */
class DecompressionLimitTest {

    private static EmbeddedChannel channelWithHandler() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    private static ChannelHandlerContext contextOf(EmbeddedChannel channel) {
        return channel.pipeline().firstContext();
    }

    /** Raw DEFLATE, which is what {@link ZlibCompression} feeds {@link Zlib#RAW}. */
    private static ByteBuf rawDeflate(byte[] payload) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(payload);
            deflater.finish();
            byte[] out = new byte[Math.max(64, payload.length)];
            int length = 0;
            while (!deflater.finished() && length < out.length) {
                length += deflater.deflate(out, length, out.length - length);
            }
            return Unpooled.wrappedBuffer(out, 0, length);
        } finally {
            deflater.end();
        }
    }

    @Test
    void aChannelNobodyToldGetsTheTightDefault() {
        EmbeddedChannel channel = channelWithHandler();
        try {
            assertEquals(
                    DecompressionLimit.DEFAULT_MAX_DECOMPRESSED_BYTES,
                    DecompressionLimit.forChannel(contextOf(channel)),
                    "an unclassified channel is the untrusted one and must keep the tight bound");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aChannelThatWasToldGetsWhatItWasTold() {
        EmbeddedChannel channel = channelWithHandler();
        try {
            DecompressionLimit.set(channel, 123_456);
            assertEquals(123_456, DecompressionLimit.forChannel(contextOf(channel)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void toleratesAnAbsentChannel() {
        assertEquals(DecompressionLimit.DEFAULT_MAX_DECOMPRESSED_BYTES, DecompressionLimit.forChannel(null));
        DecompressionLimit.set(null, 1);
    }

    /**
     * The cap still fires on a channel that kept the default — this is the pre-auth zip-bomb
     * defence, and lifting it for backends must not have lifted it here.
     */
    @Test
    void zlibStillRefusesAnOversizedBatchOnADefaultChannel() {
        EmbeddedChannel channel = channelWithHandler();
        ByteBuf compressed = rawDeflate(new byte[64 * 1024]);
        try {
            DecompressionLimit.set(channel, 4096);
            ZlibCompression compression = new ZlibCompression(Zlib.RAW);

            Exception thrown = assertThrows(Exception.class,
                    () -> compression.decode(contextOf(channel), compressed));

            assertTrue(rootCause(thrown) instanceof DataFormatException,
                    "expected the decompression cap to fire, got " + rootCause(thrown));
        } finally {
            ReferenceCountUtil.release(compressed);
            channel.finishAndReleaseAll();
        }
    }

    /** And the same batch goes through once the channel is told it may. */
    @Test
    void zlibAcceptsTheSameBatchOnceTheChannelIsAllowedIt() throws Exception {
        EmbeddedChannel channel = channelWithHandler();
        ByteBuf compressed = rawDeflate(new byte[64 * 1024]);
        ByteBuf inflated = null;
        try {
            DecompressionLimit.set(channel, 1024 * 1024);
            inflated = new ZlibCompression(Zlib.RAW).decode(contextOf(channel), compressed);
            assertEquals(64 * 1024, inflated.readableBytes());
        } finally {
            ReferenceCountUtil.release(inflated);
            ReferenceCountUtil.release(compressed);
            channel.finishAndReleaseAll();
        }
    }

    /** A non-positive limit means no limit, which is what an explicitly trusted peer gets. */
    @Test
    void zeroLiftsTheCapEntirely() throws Exception {
        EmbeddedChannel channel = channelWithHandler();
        ByteBuf compressed = rawDeflate(new byte[64 * 1024]);
        ByteBuf inflated = null;
        try {
            DecompressionLimit.set(channel, 0);
            inflated = new ZlibCompression(Zlib.RAW).decode(contextOf(channel), compressed);
            assertEquals(64 * 1024, inflated.readableBytes());
        } finally {
            ReferenceCountUtil.release(inflated);
            ReferenceCountUtil.release(compressed);
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The refused size belongs in the message. "Over the limit" cannot tell a bomb from a batch that
     * has outgrown the bound, and that is the call an operator reading the log has to make.
     */
    @Test
    void theBreachMessageNamesBothNumbers() {
        String message = DecompressionLimit.breachMessage(12_345_678L, 10_485_760);
        assertTrue(message.contains("12345678"), message);
        assertTrue(message.contains("10485760"), message);
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
