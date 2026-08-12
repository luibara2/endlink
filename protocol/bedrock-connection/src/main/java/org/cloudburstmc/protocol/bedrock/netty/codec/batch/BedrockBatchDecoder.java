package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

@Sharable
public class BedrockBatchDecoder extends MessageToMessageDecoder<BedrockBatchWrapper> {

    public static final String NAME = "bedrock-batch-decoder";

    /**
     * Packets a single batch may carry, from {@code -Dbedrock.maxPacketsPerBatch}.
     *
     * <p>A decompressed batch is bounded by {@code bedrock.maxDecompressedBytes} (10 MB), but that
     * bounds <em>bytes</em>, not work. A packet costs as little as one byte on the wire — a varint
     * length of zero — so the byte cap alone permits about 10.5 million retained slices from one
     * batch. Measured, that is ~53 bytes of heap per slice, so ~555 MB, produced from roughly 10 KB
     * of compressed input (zeros deflate about 1000:1) that an unauthenticated client can send
     * before it logs in. Every slice is also one more downstream handler invocation.
     *
     * <p>The default is far above any real batch — Bedrock clients send a handful of packets per
     * batch and the server's largest bursts are size-bound long before they are count-bound — so
     * this rejects the attack shape without touching legitimate traffic. Operators who genuinely
     * batch more can raise it.
     */
    private static final int MAX_PACKETS_PER_BATCH = Integer.getInteger("bedrock.maxPacketsPerBatch", 8192);

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        if (msg.getUncompressed() == null) {
            throw new IllegalStateException("Batch packet was not decompressed");
        }

        ByteBuf buffer = msg.getUncompressed().slice();
        int packets = 0;
        while (buffer.isReadable()) {
            if (++packets > MAX_PACKETS_PER_BATCH) {
                throw new TooLongFrameException(
                        "Batch carries more than " + MAX_PACKETS_PER_BATCH + " packets");
            }
            int packetLength = VarInts.readUnsignedInt(buffer);
            ByteBuf packetBuf = buffer.readRetainedSlice(packetLength);
            out.add(packetBuf);
        }
    }
}
