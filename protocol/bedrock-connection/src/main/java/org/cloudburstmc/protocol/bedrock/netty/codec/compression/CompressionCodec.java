package org.cloudburstmc.protocol.bedrock.netty.codec.compression;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.List;

public class CompressionCodec extends MessageToMessageCodec<BedrockBatchWrapper, BedrockBatchWrapper> {
    public static final String NAME = "compression-codec";

    private final CompressionStrategy strategy;
    private final boolean prefixed;

    public CompressionCodec(CompressionStrategy strategy, boolean prefixed) {
        this.strategy = strategy;
        this.prefixed = prefixed;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        if (msg.getCompressed() == null && msg.getUncompressed() == null) {
            throw new IllegalStateException("Batch was not encoded before");
        }

        if (msg.getCompressed() != null && !msg.isModified()) {
            this.onPassedThrough(ctx, msg);
            out.add(msg.retain());
            return;
        }

        BatchCompression compression = this.strategy.getCompression(msg);
        if (!this.prefixed && this.strategy.getDefaultCompression().getAlgorithm() != compression.getAlgorithm()) {
            throw new IllegalStateException("Non-default compression algorithm used without prefixing");
        }

        // Read the uncompressed size before compressing: compression.encode consumes the buffer's
        // readable bytes, so asking afterwards reports 0. The first capture with this trace did
        // exactly that and lost the one number a client-side decompressed-size limit is measured
        // against.
        int uncompressedBytes = TRACE_BATCHES && msg.getUncompressed() != null
                ? msg.getUncompressed().readableBytes()
                : -1;
        ByteBuf compressed = compression.encode(ctx, msg.getUncompressed());
        try {
            ByteBuf outBuf;
            if (this.prefixed) {
                // Do not use a composite buffer as encryption does not like it
                outBuf = ctx.alloc().ioBuffer(1 + compressed.readableBytes());
                outBuf.writeByte(this.getCompressionHeader(compression.getAlgorithm()));
                outBuf.writeBytes(compressed);
            } else {
                outBuf = compressed.retain();
            }

            msg.setCompressed(outBuf, compression.getAlgorithm());
        } finally {
            compressed.release();
        }

        traceBatch(ctx, msg, uncompressedBytes);
        this.onCompressed(ctx, msg);
        out.add(msg.retain());
    }

    /**
     * Per-batch outbound sizes, from {@code -Dbedrock.traceBatches=true}.
     *
     * <p>Nothing has ever measured a batch. Every conclusion drawn about batching on the 1.26.40
     * disconnect has been inferred from packet counts: {@code -Dbedrock.maxBatchBytes=32768} was run,
     * changed nothing, and was recorded as "batch grouping is not the cause" — but a cap only does
     * anything to batches that exceed it, and nothing logged whether any batch ever did. If the
     * batches during flight are a few hundred bytes, that run tested nothing at all and the
     * conclusion drawn from it has to be withdrawn.
     *
     * <p>Prints the packet count, the uncompressed and compressed size and the algorithm, so the
     * distribution can be read off a capture directly and compared against the RakNet MTU — the point
     * above which a batch is fragmented into multiple datagrams.
     */
    private static void traceBatch(ChannelHandlerContext ctx, BedrockBatchWrapper msg, int uncompressedBytes) {
        if (!TRACE_BATCHES) {
            return;
        }
        ByteBuf compressed = msg.getCompressed();
        System.out.printf(
                "  Batch out remote=%s packets=%d uncompressed=%d compressed=%d algorithm=%s.%n",
                ctx.channel().remoteAddress(),
                msg.getPackets() == null ? -1 : msg.getPackets().size(),
                uncompressedBytes,
                compressed == null ? -1 : compressed.readableBytes(),
                msg.getAlgorithm()
        );
    }

    private static final boolean TRACE_BATCHES = Boolean.getBoolean("bedrock.traceBatches");

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        ByteBuf compressed = msg.getCompressed().slice();

        BatchCompression compression;
        if (this.prefixed) {
            CompressionAlgorithm algorithm = this.getCompressionAlgorithm(compressed.readByte());
            compression = this.strategy.getCompression(algorithm);
        } else {
            compression = this.strategy.getDefaultCompression();
        }

        msg.setAlgorithm(compression.getAlgorithm());
        msg.setUncompressed(compression.decode(ctx, compressed.slice()));
        this.onDecompressed(ctx, msg);
        out.add(msg.retain());
    }

    protected void onPassedThrough(ChannelHandlerContext ctx, BedrockBatchWrapper msg) {
    }

    protected void onCompressed(ChannelHandlerContext ctx, BedrockBatchWrapper msg) {
    }

    protected void onDecompressed(ChannelHandlerContext ctx, BedrockBatchWrapper msg) {
    }

    protected final byte getCompressionHeader(CompressionAlgorithm algorithm) {
        if (algorithm.equals(PacketCompressionAlgorithm.NONE)) {
            return (byte) 0xff;
        } else if (algorithm.equals(PacketCompressionAlgorithm.ZLIB)) {
            return 0x00;
        } else if (algorithm.equals(PacketCompressionAlgorithm.SNAPPY)) {
            return 0x01;
        }

        byte header = this.getCompressionHeader0(algorithm);
        if (header == -1) {
            throw new IllegalArgumentException("Unknown compression algorithm " + algorithm);
        }
        return header;
    }

    protected final CompressionAlgorithm getCompressionAlgorithm(byte header) {
        switch (header) {
            case 0x00:
                return PacketCompressionAlgorithm.ZLIB;
            case 0x01:
                return PacketCompressionAlgorithm.SNAPPY;
            case (byte) 0xff:
                return PacketCompressionAlgorithm.NONE;
        }

        CompressionAlgorithm algorithm = this.getCompressionAlgorithm0(header);
        if (algorithm == null) {
            throw new IllegalArgumentException("Unknown compression algorithm " + header);
        }
        return algorithm;
    }

    protected byte getCompressionHeader0(CompressionAlgorithm algorithm) {
        return -1;
    }

    protected CompressionAlgorithm getCompressionAlgorithm0(byte header) {
        return null;
    }

    public CompressionStrategy getStrategy() {
        return this.strategy;
    }
}
