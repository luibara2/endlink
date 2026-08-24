package org.cloudburstmc.protocol.bedrock.netty.codec.compression;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

/**
 * How large a batch on this channel may decompress to.
 *
 * <p>The cap exists to stop a zip bomb: a few kilobytes of deflated zeros expand about 1000:1, so
 * without a bound an unauthenticated peer can make an I/O thread allocate arbitrary heap before a
 * single signature has been checked. It is enforced by {@link ZlibCompression} and
 * {@link SnappyCompression} <em>before</em> the output buffer is sized, which is the only place it
 * is worth anything.
 *
 * <p>It used to be one static value for every channel in the process, and that is wrong in a proxy,
 * which holds two very different peers. An anonymous client on the listener deserves a tight bound.
 * A backend the operator configured and pointed at by hand does not, and giving it one has a cost
 * that is not theoretical: a heavily modded server's join batch — item registry, creative content
 * and crafting data all landing in one tick — genuinely exceeds ten megabytes, and capping it there
 * turned every join to that backend into a {@code disconnect.lost} with no explanation attached.
 *
 * <p>So the limit is per-channel, set on the pipeline that knows which kind of peer it is talking
 * to, and falls back to {@link #DEFAULT_MAX_DECOMPRESSED_BYTES} for a channel nobody has told. The
 * fallback stays tight, because the peer a channel forgets to classify is the untrusted one.
 */
public final class DecompressionLimit {

    /**
     * Per-channel cap in bytes. A non-positive value means no limit — only ever appropriate for a
     * peer whose bytes the operator already trusts.
     */
    public static final AttributeKey<Integer> ATTRIBUTE =
            AttributeKey.valueOf("bedrock-max-decompressed-bytes");

    /** 10 MB, overridable with {@code -Dbedrock.maxDecompressedBytes=<bytes>}. */
    public static final int DEFAULT_MAX_DECOMPRESSED_BYTES =
            Integer.getInteger("bedrock.maxDecompressedBytes", 1024 * 1024 * 10);

    private DecompressionLimit() {
    }

    /** The cap for {@code ctx}'s channel, or the default when none was set. */
    public static int forChannel(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return DEFAULT_MAX_DECOMPRESSED_BYTES;
        }
        Integer limit = ctx.channel().attr(ATTRIBUTE).get();
        return limit == null ? DEFAULT_MAX_DECOMPRESSED_BYTES : limit;
    }

    /**
     * Applies {@code limit} to {@code channel}. Pass a non-positive value to lift the cap entirely.
     *
     * @param channel the channel to bound; ignored when null
     */
    public static void set(io.netty.channel.Channel channel, int limit) {
        if (channel != null) {
            channel.attr(ATTRIBUTE).set(limit);
        }
    }

    /**
     * The message a breach reports. Names the size that was refused as well as the cap, because
     * "over the limit" alone cannot tell a legitimate oversized batch from a bomb, and the number is
     * the only thing that can.
     */
    public static String breachMessage(long actualBytes, int limit) {
        return "Inflated data exceeds maximum size: " + actualBytes + " bytes > " + limit
                + " (raise with -Dbedrock.maxDecompressedBytes)";
    }
}
