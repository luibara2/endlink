package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayDeque;
import java.util.Queue;

public class BedrockBatchEncoder extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "bedrock-batch-encoder";

    private final Queue<BedrockPacketWrapper> messages = new ArrayDeque<>();

    /**
     * Largest uncompressed batch this encoder will emit, in bytes. Zero (the default) means one batch
     * per flush however large it grows, which is the behaviour this class has always had.
     *
     * <p>Every packet written between two flushes goes into a single batch, so batch size is a direct
     * function of throughput. A proxy makes that worse than it is for a server: it re-batches, so the
     * backend's own grouping is lost and a burst that reached the proxy as several batches leaves as
     * one. Under creative flight the 1.26.40 relay pushes ~200 packets a second, and the resulting
     * batch is compressed and handed to RakNet to fragment.
     *
     * <p>This exists to test whether that is what ends the session. The 1.26.40 disconnect scales with
     * clientbound packet rate and with nothing else that has been found: suppressing ~60% of traffic
     * multiplied survival by 3-10x, while suppressing 12% changed it by about 12%, and no packet in
     * the stream is malformed by any check available. Capping the batch keeps every packet and every
     * byte, changing only how they are grouped — so if a cap fixes it, the cause is the grouping and
     * not the content, which no drop-based experiment can distinguish.
     *
     * <p>Set with {@code -Dbedrock.maxBatchBytes=32768}. If it helps, it is also the fix.
     */
    private static final int MAX_BATCH_BYTES = Integer.getInteger("bedrock.maxBatchBytes", 0);

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof BedrockPacketWrapper)) {
            super.write(ctx, msg, promise);
            return;
        }

        // Accumulate messages to batch
        this.messages.add((BedrockPacketWrapper) msg);
        promise.trySuccess(); // complete write promise here
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (messages.isEmpty()) {
            super.flush(ctx);
            return;
        }

        CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(messages.size() * 2);
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();

        try {
            BedrockPacketWrapper packet;
            while ((packet = messages.poll()) != null) try {
                ByteBuf message = packet.getPacketBuffer();
                if (message == null) {
                    throw new IllegalArgumentException("BedrockPacket is not encoded");
                }

                // Emit before adding, not after: a single packet larger than the cap still has to go
                // out whole, and splitting it is not this handler's job.
                if (MAX_BATCH_BYTES > 0
                        && buf.readableBytes() > 0
                        && buf.readableBytes() + message.readableBytes() > MAX_BATCH_BYTES) {
                    batch.setUncompressed(buf.retain());
                    ctx.write(batch.retain());
                    buf.release();
                    batch.release();
                    buf = ctx.alloc().compositeDirectBuffer(messages.size() * 2 + 2);
                    batch = BedrockBatchWrapper.newInstance();
                }

                ByteBuf header = ctx.alloc().ioBuffer(5);
                VarInts.writeUnsignedInt(header, message.readableBytes());
                buf.addComponent(true, header);
                buf.addComponent(true, message.retain());
                batch.addPacket(packet.retain());
            } finally {
                packet.release();
            }

            batch.setUncompressed(buf.retain());
            ctx.write(batch.retain());
        } finally {
            buf.release();
            batch.release();
        }

        super.flush(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        BedrockPacketWrapper message;
        while ((message = messages.poll()) != null) {
            message.release();
        }
        super.handlerRemoved(ctx);
    }
}
