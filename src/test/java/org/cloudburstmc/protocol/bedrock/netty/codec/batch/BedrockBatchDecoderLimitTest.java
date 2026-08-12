package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the pre-auth batch packet-count amplification.
 *
 * <p>{@code bedrock.maxDecompressedBytes} caps a batch at 10 MB, but a packet costs one byte on the
 * wire — a varint length of zero — so that cap alone allowed ~10.5 million retained slices from a
 * single batch. Measured at ~53 bytes of heap each, that is ~555 MB, produced from ~10 KB of
 * compressed input (zeros deflate about 1000:1) that an unauthenticated client can send before it
 * logs in. These tests assert the count is bounded, and that a batch of ordinary size still decodes.
 */
class BedrockBatchDecoderLimitTest {

    /** Reads the cap the decoder was built with, so the test tracks the default rather than pinning it. */
    private static final int MAX_PACKETS = Integer.getInteger("bedrock.maxPacketsPerBatch", 8192);

    private static ByteBuf zeroLengthPackets(int count) {
        ByteBuf buffer = Unpooled.buffer(count);
        buffer.writeZero(count); // each 0x00 is a varint length of 0: one packet per byte
        return buffer;
    }

    private static BedrockBatchWrapper batchOf(ByteBuf uncompressed) {
        return BedrockBatchWrapper.newInstance(null, uncompressed);
    }

    @Test
    void decodeRejectsABatchWithTooManyPackets() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchDecoder());
        BedrockBatchWrapper batch = batchOf(zeroLengthPackets(MAX_PACKETS + 1));

        Throwable thrown = assertThrows(
                Throwable.class,
                () -> channel.writeInbound(batch),
                "a batch over the packet cap must be refused, not turned into one slice per byte"
        );
        // Netty wraps a decoder throw in DecoderException; TooLongFrameException is one.
        assertInstanceOf(TooLongFrameException.class, thrown,
                "expected a TooLongFrameException, got " + thrown);

        // The slices produced before the cap tripped are still handed downstream by Netty rather
        // than leaked; drain them so the buffer's refcount can return to zero.
        Object message;
        int fired = 0;
        while ((message = channel.readInbound()) != null) {
            fired++;
            ((ByteBuf) message).release();
        }
        assertEquals(MAX_PACKETS, fired, "the decoder must stop at the cap");
        channel.finishAndReleaseAll();
    }

    @Test
    void decodeStillAcceptsAnOrdinaryBatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchDecoder());
        ByteBuf uncompressed = Unpooled.buffer();
        int packets = 16;
        for (int i = 0; i < packets; i++) {
            uncompressed.writeByte(2);          // varint length 2
            uncompressed.writeByte(0x8f);       // a plausible packet id
            uncompressed.writeByte(i);
        }

        assertTrue(channel.writeInbound(batchOf(uncompressed)), "a normal batch must decode");

        int decoded = 0;
        Object message;
        while ((message = channel.readInbound()) != null) {
            ByteBuf packet = (ByteBuf) message;
            assertEquals(2, packet.readableBytes());
            packet.release();
            decoded++;
        }
        assertEquals(packets, decoded, "every packet in a legitimate batch must survive");
        channel.finishAndReleaseAll();
    }

    @Test
    void decodeAcceptsExactlyTheCap() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchDecoder());
        assertTrue(channel.writeInbound(batchOf(zeroLengthPackets(MAX_PACKETS))),
                "the cap itself must be allowed, not an off-by-one rejection");

        int decoded = 0;
        Object message;
        while ((message = channel.readInbound()) != null) {
            ((ByteBuf) message).release();
            decoded++;
        }
        assertEquals(MAX_PACKETS, decoded);
        channel.finishAndReleaseAll();
    }
}
