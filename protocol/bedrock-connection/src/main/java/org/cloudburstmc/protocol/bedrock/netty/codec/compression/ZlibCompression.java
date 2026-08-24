package org.cloudburstmc.protocol.bedrock.netty.codec.compression;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.common.util.Zlib;

public class ZlibCompression implements BatchCompression {
    private final Zlib zlib;

    @Getter @Setter
    private int level = 7;

    public ZlibCompression(Zlib zlib) {
        this.zlib = zlib;
    }

    @Override
    public ByteBuf encode(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        ByteBuf outBuf = ctx.alloc().ioBuffer(msg.readableBytes() << 3);
        try {
            zlib.deflate(msg, outBuf, level);
            return outBuf.retain();
        } finally {
            outBuf.release();
        }
    }

    @Override
    public ByteBuf decode(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        // Per-channel, not per-process: a proxy's two peers do not deserve the same bound.
        // See DecompressionLimit.
        return zlib.inflate(msg, DecompressionLimit.forChannel(ctx));
    }

    @Override
    public CompressionAlgorithm getAlgorithm() {
        return PacketCompressionAlgorithm.ZLIB;
    }
}
