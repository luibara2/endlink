package org.cloudburstmc.protocol.bedrock.netty.codec.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;

import java.util.List;

import static java.util.Objects.requireNonNull;

public abstract class BedrockPacketCodec extends MessageToMessageCodec<ByteBuf, BedrockPacketWrapper> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(BedrockPacketCodec.class);
    public static final String NAME = "bedrock-packet-codec";

    /**
     * When enabled, every successfully decoded packet is immediately re-encoded and compared
     * byte-for-byte against what actually arrived on the wire.
     *
     * <p>A proxy re-encodes what it decoded, so any serializer whose read and write halves disagree
     * silently corrupts the relayed stream: decoding "succeeds", nothing throws, and the far end
     * receives a packet that is subtly wrong or the wrong length. That failure mode has already cost
     * this project two long debugging cycles (the v975 biome definition list read/wrote a different
     * field set, twice). Nothing in the normal pipeline detects it, because both halves of the
     * asymmetry live in the same serializer and are self-consistent.
     *
     * <p>Off by default — it doubles per-packet codec work. Enable with
     * {@code -Dbedrock.verifyReencode=true}.
     */
    private static final boolean VERIFY_REENCODE = Boolean.getBoolean("bedrock.verifyReencode");

    /**
     * When enabled, every packet this codec encodes is immediately decoded again with the same codec
     * and reported if it does not read back cleanly.
     *
     * <p>{@link #VERIFY_REENCODE} checks the leg this proxy <em>receives</em>: it proves a serializer
     * can faithfully re-encode what it just decoded, using the sending side's codec. It says nothing
     * about what is written to the far end, and on a cross-version relay those are different codecs.
     * A packet decoded with the backend's codec and encoded with the client's can be perfectly
     * symmetric on the backend side and still produce bytes the client cannot parse — the asymmetry
     * lives <em>between</em> the two versions, where neither codec is looking.
     *
     * <p>That is the failure this catches: the receiving codec's own reader is the closest stand-in
     * available for the real client's parser, so a packet that its own version cannot read back is
     * one the client will not read either. Trailing bytes are reported as well as throws, because a
     * writer that emits more than its reader consumes leaves the client's parser mid-stream — which
     * presents as an unexplained clean disconnect rather than an error.
     *
     * <p>Off by default — it doubles per-packet codec work, like {@link #VERIFY_REENCODE}, and the
     * two are independent. Enable with {@code -Dbedrock.verifyEncode=true}.
     */
    private static final boolean VERIFY_ENCODE = Boolean.getBoolean("bedrock.verifyEncode");

    /**
     * Packets larger than this are encoded without being verified.
     *
     * <p>Verification has to be cheap enough to join with, or it cannot be used to reproduce
     * anything. {@code -Dbedrock.verifyReencode=true} is not: it re-encodes the 320KB
     * {@code BiomeDefinitionList} and 350KB {@code AvailableCommands} along with everything else, and
     * the observed effect is packets arriving 100-250ms apart and the client timing out on the
     * loading screen at +28s — so the run that finds the bug and the run that shows whether the game
     * works cannot be the same run.
     *
     * <p>Those giants are all join-time packets, and the mid-session stream this exists to watch is
     * nowhere near the cap: the largest {@code LevelChunk} in the capture is 7.2KB and a full
     * {@code SubChunk} batch around 20KB. Capping by size therefore keeps the whole of ordinary play
     * covered while letting the join through at close to full speed.
     *
     * <p>Raise it with {@code -Dbedrock.verifyEncodeMaxBytes=N} to cover the join sequence too,
     * accepting the loading-screen cost; 0 removes the cap.
     */
    private static final int VERIFY_ENCODE_MAX_BYTES = Integer.getInteger("bedrock.verifyEncodeMaxBytes", 65536);

    /**
     * Read-back limits for {@link #VERIFY_ENCODE}. Zero disables each check; see the note in
     * {@code verifyEncode} for why the configured receive limits are the wrong ones to apply to
     * bytes this process just produced.
     */
    private static final EncodingSettings UNLIMITED_FOR_VERIFICATION = EncodingSettings.builder()
            .maxListSize(0)
            .maxByteArraySize(0)
            .maxNetworkNBTSize(0)
            .maxItemNBTSize(0)
            .maxStringLength(0)
            .maxGeometryDataSize(0)
            .maxItemStackTagLength(0)
            .maxInventoryActionsOrRequests(0)
            .build();

    /**
     * Restores the old behaviour of closing the connection when a packet cannot be encoded.
     *
     * <p>Off by default: a proxy relaying between two protocol versions will occasionally meet a
     * packet the receiving side's codec cannot express, and losing the player is a far worse outcome
     * than losing the packet. Turn it on with {@code -Dbedrock.strictEncode=true} when you want the
     * failure to be impossible to ignore rather than merely logged.</p>
     */
    private static final boolean STRICT_ENCODE = Boolean.getBoolean("bedrock.strictEncode");

    private BedrockCodec codec = BedrockCompat.CODEC;
    private BedrockCodecHelper helper = codec.createHelper();

    private PacketRecipient inboundRecipient;
    private PacketRecipient outboundRecipient;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        PacketDirection attribute = ctx.channel().attr(PacketDirection.ATTRIBUTE).get();
        if (attribute != null) {
            this.inboundRecipient = attribute.getInbound();
            this.outboundRecipient = attribute.getOutbound();
        }
    }

    @Override
    protected final void encode(ChannelHandlerContext ctx, BedrockPacketWrapper msg, List<Object> out) throws Exception {
        if (msg.getPacketBuffer() != null) {
            // We have a pre-encoded packet buffer, just use that.
            out.add(msg.retain());
        } else {
            ByteBuf buf = ctx.alloc().buffer(128);
            try {
                BedrockPacket packet = msg.getPacket();
                msg.setPacketId(getPacketId(packet));
                encodeHeader(buf, msg);
                int payloadStart = buf.writerIndex();
                this.codec.tryEncode(helper, buf, packet);
                if (VERIFY_ENCODE) {
                    verifyEncode(ctx, packet, msg.getPacketId(), buf.slice(payloadStart, buf.writerIndex() - payloadStart));
                }

                msg.setPacketBuffer(buf.retain());
                out.add(msg.retain());
            } catch (Throwable t) {
                // One packet that will not encode must not cost the player their session.
                //
                // Rethrowing here propagates up the Netty pipeline and closes the connection, which
                // is how a single unrelayable packet turned into "it disconnected me after a few
                // seconds" with nothing useful in the log — the detail went to stderr, which is not
                // where this proxy's output is read, and the player just saw a drop. Dropping the
                // packet instead degrades one effect; dropping the connection ends the session.
                //
                // The summary goes to stdout so it lands in the same log as everything else, and
                // names the packet, which is the one fact needed to fix the underlying cause. The
                // stack trace still goes to stderr for anyone capturing it.
                System.out.printf(
                        "DROPPED UNENCODABLE PACKET %s id=%d protocol=%d recipient=%s remote=%s: %s%n",
                        msg.getPacket() == null ? null : msg.getPacket().getClass().getSimpleName(),
                        msg.getPacketId(),
                        this.codec.getProtocolVersion(),
                        this.inboundRecipient,
                        ctx.channel().remoteAddress(),
                        t
                );
                t.printStackTrace(System.err);
                if (STRICT_ENCODE) {
                    throw t;
                }
            } finally {
                buf.release();
            }
        }
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        BedrockPacketWrapper wrapper = BedrockPacketWrapper.create();
        wrapper.setPacketBuffer(msg.retainedSlice());
        try {
            int index = msg.readerIndex();
            this.decodeHeader(msg, wrapper);
            wrapper.setHeaderLength(msg.readerIndex() - index);
            try {
                wrapper.setPacket(this.codec.tryDecode(helper, msg, wrapper.getPacketId(), this.inboundRecipient));
                if (VERIFY_REENCODE) {
                    verifyReencode(ctx, wrapper);
                }
            } catch (Throwable t) {
                // Also on stdout: an undecodable packet is forwarded as raw bytes, which is harmless
                // between matching versions and silently corrupting across them, so it must be
                // visible in the ordinary log rather than only in stderr.
                System.out.printf(
                        "UNDECODABLE PACKET id=%d protocol=%d forwarded as raw payload: %s%n",
                        wrapper.getPacketId(),
                        this.codec.getProtocolVersion(),
                        t
                );
                t.printStackTrace(System.err);
                UnknownPacket unknownPacket = new UnknownPacket();
                unknownPacket.setPacketId(wrapper.getPacketId());
                unknownPacket.setPayload(wrapper.getPacketBuffer()
                        .retainedSlice()
                        .skipBytes(wrapper.getHeaderLength()));
                wrapper.setPacket(unknownPacket);
            }
            out.add(wrapper.retain());
        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to decode packet", t);
            }
            throw t;
        } finally {
            wrapper.release();
        }
    }

    /**
     * Re-encodes a freshly decoded packet and reports any divergence from the original wire bytes.
     * See {@link #VERIFY_REENCODE}. Diagnostic only — never alters the decoded packet or the buffer
     * the pipeline goes on to use.
     */
    /**
     * 64 bytes identifies the packet but rarely reaches the divergence — the two dumps are usually
     * identical for the whole prefix and differ somewhere past it, which says nothing about where.
     * Raise with {@code -Dbedrock.reencodeDumpBytes=N}.
     */
    private static final int REPORT_DUMP_BYTES = Integer.getInteger("bedrock.reencodeDumpBytes", 512);

    private void verifyReencode(ChannelHandlerContext ctx, BedrockPacketWrapper wrapper) {
        BedrockPacket packet = wrapper.getPacket();
        if (packet == null || packet instanceof UnknownPacket) {
            return;
        }
        ByteBuf original = wrapper.getPacketBuffer().slice()
                .skipBytes(wrapper.getHeaderLength());
        ByteBuf reencoded = ctx.alloc().buffer(Math.max(128, original.readableBytes()));
        try {
            this.codec.tryEncode(helper, reencoded, packet);
            if (ByteBufUtil.equals(original, reencoded)) {
                return;
            }
            System.err.printf(
                    "ASYMMETRIC SERIALIZER: %s (id=%d, protocol=%d) re-encoded to %d bytes but arrived as %d. "
                            + "The relayed copy of this packet is corrupt.%n  wire: %s%n  ours: %s%n",
                    packet.getClass().getSimpleName(),
                    wrapper.getPacketId(),
                    this.codec.getProtocolVersion(),
                    reencoded.readableBytes(),
                    original.readableBytes(),
                    ByteBufUtil.hexDump(original, original.readerIndex(), Math.min(REPORT_DUMP_BYTES, original.readableBytes())),
                    ByteBufUtil.hexDump(reencoded, reencoded.readerIndex(), Math.min(REPORT_DUMP_BYTES, reencoded.readableBytes()))
            );
        } catch (Throwable t) {
            System.err.printf(
                    "ASYMMETRIC SERIALIZER: %s (id=%d, protocol=%d) decoded but failed to re-encode: %s%n",
                    packet.getClass().getSimpleName(),
                    wrapper.getPacketId(),
                    this.codec.getProtocolVersion(),
                    t
            );
        } finally {
            reencoded.release();
        }
    }

    /**
     * Decodes a just-encoded packet again with this codec and reports anything the reader cannot
     * account for. See {@link #VERIFY_ENCODE}. Diagnostic only — the pipeline goes on to use the
     * buffer that was written, untouched.
     */
    private void verifyEncode(ChannelHandlerContext ctx, BedrockPacket packet, int packetId, ByteBuf payload) {
        if (packet instanceof UnknownPacket) {
            // Raw bytes this codec never parsed; its reader is not the right judge of them.
            return;
        }
        if (VERIFY_ENCODE_MAX_BYTES > 0 && payload.readableBytes() > VERIFY_ENCODE_MAX_BYTES) {
            return;
        }
        ByteBuf readBack = payload.duplicate();
        BedrockPacket decoded = null;
        // The helper's encoding settings are *receive* limits — they exist so a hostile peer cannot
        // make this process allocate an unbounded list. Applied to our own outbound bytes they are
        // simply wrong: a legitimate CreativeContent carries ~1875 groups against a maxListSize of
        // 1536, and verification reported it as unreadable when the only thing it had proved was
        // that the proxy sends more than it is willing to receive. Lift them for the read-back and
        // put them straight back; the bytes being checked are ones this codec just wrote.
        EncodingSettings configured = helper.getEncodingSettings();
        try {
            helper.setEncodingSettings(UNLIMITED_FOR_VERIFICATION);
            decoded = this.codec.tryDecode(helper, readBack, packetId, this.outboundRecipient);
            int trailing = readBack.readableBytes();
            if (trailing == 0) {
                return;
            }
            System.out.printf(
                    "UNREADABLE OUTBOUND PACKET %s id=%d protocol=%d recipient=%s remote=%s: wrote %d bytes but its own"
                            + " reader consumed only %d, leaving %d trailing. The receiving client's parser will be"
                            + " left mid-stream.%n  ours: %s%n",
                    packet.getClass().getSimpleName(),
                    packetId,
                    this.codec.getProtocolVersion(),
                    this.outboundRecipient,
                    ctx.channel().remoteAddress(),
                    payload.readableBytes(),
                    payload.readableBytes() - trailing,
                    trailing,
                    ByteBufUtil.hexDump(payload, payload.readerIndex(), Math.min(REPORT_DUMP_BYTES, payload.readableBytes()))
            );
        } catch (Throwable t) {
            System.out.printf(
                    "UNREADABLE OUTBOUND PACKET %s id=%d protocol=%d recipient=%s remote=%s: encoded %d bytes that this"
                            + " codec cannot decode again: %s%n  ours: %s%n",
                    packet.getClass().getSimpleName(),
                    packetId,
                    this.codec.getProtocolVersion(),
                    this.outboundRecipient,
                    ctx.channel().remoteAddress(),
                    payload.readableBytes(),
                    t,
                    ByteBufUtil.hexDump(payload, payload.readerIndex(), Math.min(REPORT_DUMP_BYTES, payload.readableBytes()))
            );
            t.printStackTrace(System.err);
        } finally {
            helper.setEncodingSettings(configured);
            // Readers take retained slices of the payload (LevelChunk, SubChunk, item user data), so
            // the throwaway copy has to be released or verification leaks the buffer it inspected.
            ReferenceCountUtil.release(decoded);
        }
    }

    public abstract void encodeHeader(ByteBuf buf, BedrockPacketWrapper msg);

    public abstract void decodeHeader(ByteBuf buf, BedrockPacketWrapper msg);

    public final int getPacketId(BedrockPacket packet) {
        if (packet instanceof UnknownPacket) {
            return ((UnknownPacket) packet).getPacketId();
        }
        return this.codec.getPacketDefinition(packet.getClass()).getId();
    }

    public final void setCodec(BedrockCodec codec) {
        this.codec = requireNonNull(codec, "Codec cannot be null");
        this.helper = codec.createHelper();
    }

    public final BedrockCodec getCodec() {
        return codec;
    }

    public BedrockCodecHelper getHelper() {
        return helper;
    }
}
