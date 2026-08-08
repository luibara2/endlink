package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks that what the proxy writes across the hop can be read back by the codec of the side it is
 * written for.
 *
 * <p>{@link CrossProtocolPacketSweepTest} stops at "the destination codec encoded it without
 * throwing". That is a weaker claim than it looks: a serializer that writes the wrong number of
 * fields, or writes them in an order its own reader does not expect, throws nothing at all. It
 * produces bytes. The sweep passes, the packet is relayed, and the receiving client's parser walks
 * off the end of it.
 *
 * <p>This is not hypothetical for a proxy specifically. Between two versions the write side is
 * reached with an object the destination codec never decoded — fields the source codec never
 * populated sit at their defaults, and fields the destination writes unconditionally may never have
 * been read. Nothing else in the tree exercises that combination:
 * {@code -Dbedrock.verifyReencode=true} verifies the leg the proxy <em>receives</em>, using the
 * sending side's codec, so the outbound leg has no coverage at all.
 *
 * <p>The destination codec's own reader is the closest available stand-in for the real client's
 * parser. A packet it cannot read back is one the client will not read either, and the symptom of
 * that is a clean disconnect with nothing logged — the hardest kind of bug to attribute to a packet.
 *
 * <p>Calibrated the same way as the sweep, and in both directions: a packet is only reported if the
 * destination codec can read back its <em>own</em> populated instance. That keeps the finding
 * specific to the hop rather than to a packet that simply cannot round-trip anywhere.
 */
class CrossProtocolOutboundReadbackTest {

    private static final int MAX_PACKET_ID = 512;

    /**
     * Dropped in {@link org.endstone.proxy.protocol.ModernClientTo1001Translator} rather than
     * relayed, for the reason {@link CrossProtocolPacketSweepTest} records: the two shapes share no
     * field, so it is never written across the edge and its readback is not a real journey.
     */
    private static final Set<String> DROPPED_ACROSS_THE_EDGE = Set.of("ClientboundUpdateSoundDataPacket");

    @Test
    void whatIsWrittenForA1_26_40ClientCanBeReadBackByA1_26_40Client() {
        List<String> broken = sweep(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, PacketRecipient.CLIENT);
        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    These packets are decoded from a 1.26.30 backend and encoded for a 1.26.40 client \
                    without error, but a 1.26.40 reader cannot consume the result. The client receives \
                    them mid-session and closes the connection with no message:
                      """ + String.join("\n  ", broken));
        }
    }

    @Test
    void whatIsWrittenForA1_26_30BackendCanBeReadBackByA1_26_30Backend() {
        List<String> broken = sweep(Bedrock_v2168.CODEC, Bedrock_v1001.CODEC, PacketRecipient.SERVER);
        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    These packets are decoded from a 1.26.40 client and encoded for a 1.26.30 backend \
                    without error, but a 1.26.30 reader cannot consume the result:
                      """ + String.join("\n  ", broken));
        }
    }

    private static List<String> sweep(BedrockCodec from, BedrockCodec to, PacketRecipient direction) {
        List<String> broken = new ArrayList<>();

        for (int id = 0; id < MAX_PACKET_ID; id++) {
            BedrockPacketDefinition<?> source = from.getPacketDefinition(id);
            BedrockPacketDefinition<?> target = to.getPacketDefinition(id);
            if (source == null || target == null) {
                continue;
            }
            if (source.getRecipient() != null && source.getRecipient() != direction) {
                continue;
            }
            String name = packetName(source);
            if (DROPPED_ACROSS_THE_EDGE.contains(name)) {
                continue;
            }

            // Calibration: a packet the destination codec cannot read back from its own writer is
            // already broken at matching versions, and reporting it here would blame the hop for it.
            if (readBackFailure(to, id, populated(target)) != null) {
                continue;
            }

            BedrockPacket decoded;
            try {
                decoded = readBack(from, id, populated(source));
            } catch (Throwable ignored) {
                // The source codec cannot round-trip it either; nothing to conclude about the hop.
                continue;
            }

            try {
                String failure = readBackFailure(to, id, decoded);
                if (failure != null) {
                    broken.add(name + " — " + failure);
                }
            } finally {
                ReferenceCountUtil.release(decoded);
            }
        }
        return broken;
    }

    private static BedrockPacket populated(BedrockPacketDefinition<?> definition) {
        return PacketPopulator.populate(definition.getFactory().get());
    }

    /**
     * @return a description of why {@code packet} could not be encoded and read back with
     * {@code codec}, or null when it survives the trip with every byte accounted for.
     */
    private static String readBackFailure(BedrockCodec codec, int id, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        BedrockPacket readBack = null;
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            int written = buffer.readableBytes();
            readBack = codec.tryDecode(codec.createHelper(), buffer, id);
            int trailing = buffer.readableBytes();
            if (trailing == 0) {
                return null;
            }
            // Trailing bytes are the dangerous case, not an obviously broken one: the packet
            // "decodes", and everything after it in the batch is read from the wrong offset.
            return "wrote " + written + " bytes but its reader consumed only " + (written - trailing)
                    + ", leaving " + trailing + " trailing";
        } catch (Throwable failure) {
            return rootCause(failure);
        } finally {
            ReferenceCountUtil.release(readBack);
            buffer.release();
        }
    }

    private static BedrockPacket readBack(BedrockCodec codec, int id, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static String packetName(BedrockPacketDefinition<?> definition) {
        return definition.getFactory().get().getClass().getSimpleName();
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
