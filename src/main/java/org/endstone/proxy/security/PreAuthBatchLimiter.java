package org.endstone.proxy.security;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.function.BooleanSupplier;

/**
 * Caps how large a decompressed batch may be while a session is still unauthenticated.
 *
 * <p>{@code bedrock.maxDecompressedBytes} (10 MB) bounds the bytes a batch decompresses to, but not
 * the work those bytes buy. Everything downstream is driven by the batch's <em>content</em>, and
 * two of those consumers amplify it by one to two orders of magnitude:</p>
 *
 * <ul>
 *   <li>the batch decoder turns each byte into a retained slice (~53 bytes of heap each), and</li>
 *   <li>{@code LoginSerializer.readAuthJwt} hands the raw, <em>unverified</em> login JWT straight to
 *       {@code JsonUtil.parseJson} — measured at ~79 bytes of heap per source byte for a nested
 *       payload, before a single signature has been checked.</li>
 * </ul>
 *
 * <p>Zeros deflate about 1000:1, so ~10 KB on the wire reaches the 10 MB cap, and at those
 * amplification factors that is several hundred megabytes of heap on a Netty I/O thread from an
 * unauthenticated client. The batch decoder now caps its packet count, which closes the first; this
 * closes the second, and any future consumer with the same shape, by bounding the input itself for
 * exactly as long as the peer is anonymous.</p>
 *
 * <p>The limit only applies before login completes. A real login batch is tens to a few hundred
 * kilobytes, so the default sits well above any legitimate one; gameplay batches, which are the
 * genuinely large ones, are never measured against it. Raise or lower it with
 * {@code -Dbedrock.maxPreAuthBatchBytes=<bytes>}; 0 disables the check.</p>
 */
public final class PreAuthBatchLimiter extends ChannelInboundHandlerAdapter {
    public static final String NAME = "endstone-preauth-batch-limiter";

    /** Default 1 MiB — roughly ten times the largest login seen in practice. */
    public static final int MAX_PRE_AUTH_BATCH_BYTES =
            Integer.getInteger("bedrock.maxPreAuthBatchBytes", 1024 * 1024);

    private final int maxBytes;
    private final BooleanSupplier authenticated;

    public PreAuthBatchLimiter(BooleanSupplier authenticated) {
        this(MAX_PRE_AUTH_BATCH_BYTES, authenticated);
    }

    public PreAuthBatchLimiter(int maxBytes, BooleanSupplier authenticated) {
        if (authenticated == null) {
            throw new IllegalArgumentException("authenticated cannot be null");
        }
        this.maxBytes = maxBytes;
        this.authenticated = authenticated;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (maxBytes > 0 && msg instanceof BedrockBatchWrapper batch && !authenticated.getAsBoolean()) {
            ByteBuf uncompressed = batch.getUncompressed();
            if (uncompressed != null && uncompressed.readableBytes() > maxBytes) {
                int size = uncompressed.readableBytes();
                // Own the message: nothing downstream will see it, so release it here rather than
                // leak it on the way out through exceptionCaught.
                batch.release();
                throw new TooLongFrameException("Pre-login batch of " + size
                        + " bytes exceeds the maximum of " + maxBytes + " bytes");
            }
        }
        ctx.fireChannelRead(msg);
    }
}
