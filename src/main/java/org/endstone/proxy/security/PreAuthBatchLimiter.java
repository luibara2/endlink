package org.endstone.proxy.security;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.TooLongFrameException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

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
 * <p>The limit only applies before login completes. Gameplay batches, which are the genuinely large
 * ones, are never measured against it. Raise or lower it with
 * {@code -Dbedrock.maxPreAuthBatchBytes=<bytes>}; 0 disables the check.</p>
 *
 * <h2>The login batch</h2>
 *
 * <p>A login normally fits in a few hundred kilobytes, but the client-data JWT carries the player's
 * skin, and a custom skin with its own geometry pushes it past 2 MB. Those players were being
 * dropped with {@code disconnect.lost}. So one batch per session may go up to
 * {@code -Dbedrock.maxPreAuthLoginBytes} (default 5 MiB), and only when it is exactly one
 * {@code LoginPacket} and nothing else. That keeps the extra room from buying anything the
 * attack above needs:</p>
 *
 * <ul>
 *   <li>it is one packet, so the slice amplification does not apply;</li>
 *   <li>the only part parsed before any signature check is the auth JWT, which the login
 *       serializer caps on its own ({@code bedrock.maxLoginAuthJwtBytes});</li>
 *   <li>the skin JWT is not touched until the chain has verified, and even then its JWS header and
 *       JSON shape are bounded by {@code ClientLoginAuthenticator} before they are parsed;</li>
 *   <li>it is granted once per session, so a peer cannot keep feeding large batches while
 *       anonymous. Decompressing the batch was already possible under the 10 MB cap.</li>
 * </ul>
 */
public final class PreAuthBatchLimiter extends ChannelInboundHandlerAdapter {
    public static final String NAME = "endstone-preauth-batch-limiter";

    /** Default 1 MiB — roughly ten times the largest skinless login seen in practice. */
    public static final int MAX_PRE_AUTH_BATCH_BYTES =
            Integer.getInteger("bedrock.maxPreAuthBatchBytes", 1024 * 1024);

    /**
     * Default 5 MiB. Custom skins with geometry have been seen at ~2 MB; the largest seen anywhere
     * was just under 5 MB. Only a batch holding a single {@code LoginPacket} may use it.
     */
    public static final int MAX_PRE_AUTH_LOGIN_BYTES =
            Integer.getInteger("bedrock.maxPreAuthLoginBytes", 5 * 1024 * 1024);

    private static final int LOGIN_PACKET_ID = 1;
    private static final int PACKET_ID_MASK = 0x3ff;

    private final int maxBytes;
    private final int maxLoginBytes;
    private final BooleanSupplier authenticated;
    private boolean loginAllowanceUsed;

    public PreAuthBatchLimiter(BooleanSupplier authenticated) {
        this(MAX_PRE_AUTH_BATCH_BYTES, MAX_PRE_AUTH_LOGIN_BYTES, authenticated);
    }

    public PreAuthBatchLimiter(int maxBytes, BooleanSupplier authenticated) {
        this(maxBytes, maxBytes, authenticated);
    }

    public PreAuthBatchLimiter(int maxBytes, int maxLoginBytes, BooleanSupplier authenticated) {
        if (authenticated == null) {
            throw new IllegalArgumentException("authenticated cannot be null");
        }
        this.maxBytes = maxBytes;
        this.maxLoginBytes = Math.max(maxBytes, maxLoginBytes);
        this.authenticated = authenticated;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (maxBytes > 0 && msg instanceof BedrockBatchWrapper batch && !authenticated.getAsBoolean()) {
            ByteBuf uncompressed = batch.getUncompressed();
            if (uncompressed != null && uncompressed.readableBytes() > maxBytes) {
                int size = uncompressed.readableBytes();
                if (size <= maxLoginBytes && !loginAllowanceUsed && isSingleLoginPacket(uncompressed)) {
                    loginAllowanceUsed = true;
                } else {
                    // Own the message: nothing downstream will see it, so release it here rather
                    // than leak it on the way out through exceptionCaught.
                    batch.release();
                    throw new TooLongFrameException("Pre-login batch of " + size
                            + " bytes exceeds the maximum of "
                            + (size <= maxLoginBytes ? maxBytes : maxLoginBytes) + " bytes");
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * Whether the batch is one length-prefixed packet spanning the whole buffer, with the
     * {@code LoginPacket} id. Reads a duplicate, so the batch itself is left untouched.
     */
    private static boolean isSingleLoginPacket(ByteBuf uncompressed) {
        ByteBuf buffer = uncompressed.duplicate();
        try {
            int length = VarInts.readUnsignedInt(buffer);
            if (length <= 0 || length != buffer.readableBytes()) {
                return false;
            }
            int header = VarInts.readUnsignedInt(buffer);
            return (header & PACKET_ID_MASK) == LOGIN_PACKET_ID;
        } catch (RuntimeException malformed) {
            return false;
        }
    }
}
