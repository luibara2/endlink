package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sweeps every packet both codecs register and re-encodes it across the 1.26.40 &harr; 1.26.30 hop,
 * which is exactly what the proxy does: decode with the sending side's codec, re-encode with the
 * receiving side's.
 *
 * <p>The trick that makes this useful is the baseline. A default-constructed packet has null fields
 * all over it, so plenty of packets cannot be encoded at all — that is not a cross-protocol problem
 * and reporting it would bury the real findings. So each packet is first round-tripped through its
 * <em>own</em> codec, and any packet that cannot survive that is skipped. What remains is precisely
 * the set that one codec handles happily and the other chokes on, which is the definition of a
 * cross-protocol break.</p>
 */
class CrossProtocolPacketSweepTest {

    private static final int MAX_PACKET_ID = 512;

    /**
     * {@code ClientboundUpdateSoundData} is the one packet whose two shapes share no field: 1.26.30
     * writes a handle plus an event string, 1.26.40 writes a handle plus seven independent
     * optionals. Decoding either side leaves everything the other writes unset, so it is dropped in
     * {@link ModernClientTo1001Translator} rather than relayed. It is expected to fail this sweep —
     * that failure is the reason the drop exists.
     */
    private static final Set<String> DROPPED_ACROSS_THE_EDGE = Set.of("ClientboundUpdateSoundDataPacket");

    @Test
    void every1_26_30PacketReEncodesForA1_26_40Client() {
        List<String> broken = sweep(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, PacketRecipient.CLIENT);
        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    These packets decode on a 1.26.30 backend but cannot be re-encoded for a 1.26.40 \
                    client, so they would kill the relay mid-session. This is the direction that runs \
                    constantly, so each one breaks ordinary play:
                      """ + String.join("\n  ", broken));
        }
    }

    /**
     * Only packets a client is allowed to send are checked here. A clientbound-only packet is never
     * encoded for a backend, so a gap in that direction is unreachable — and including them buries
     * the reachable findings under noise like {@code SetScorePacket}, whose packet-level action
     * 1.26.40 no longer writes.
     */
    @Test
    void every1_26_40PacketReEncodesForA1_26_30Backend() {
        List<String> broken = sweep(Bedrock_v2168.CODEC, Bedrock_v1001.CODEC, PacketRecipient.SERVER);
        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    These packets decode from a 1.26.40 client but cannot be re-encoded for a 1.26.30 \
                    backend:
                      """ + String.join("\n  ", broken));
        }
    }

    /**
     * Both codecs must agree on the packet-id table, or a relayed packet arrives as something else
     * entirely. 1.26.40 added and removed nothing, so this is a straight equality check and the
     * cheapest possible guard against a future version quietly renumbering.
     */
    @Test
    void thePacketIdTablesAreIdentical() {
        List<String> onlyModern = new ArrayList<>();
        List<String> onlyLegacy = new ArrayList<>();

        for (int id = 0; id < MAX_PACKET_ID; id++) {
            BedrockPacketDefinition<?> modern = Bedrock_v2168.CODEC.getPacketDefinition(id);
            BedrockPacketDefinition<?> legacy = Bedrock_v1001.CODEC.getPacketDefinition(id);
            if (modern != null && legacy == null) {
                onlyModern.add(id + " " + packetName(modern));
            } else if (modern == null && legacy != null) {
                onlyLegacy.add(id + " " + packetName(legacy));
            } else if (modern != null && !packetName(modern).equals(packetName(legacy))) {
                onlyModern.add(id + " is " + packetName(modern) + " on 1.26.40 but " + packetName(legacy) + " on 1.26.30");
            }
        }

        assertEquals(List.of(), onlyModern, "packet ids that differ or are 1.26.40-only");
        assertEquals(List.of(), onlyLegacy, "packet ids that are 1.26.30-only");
    }

    private static List<String> sweep(BedrockCodec from, BedrockCodec to, PacketRecipient direction) {
        List<String> broken = new ArrayList<>();

        for (int id = 0; id < MAX_PACKET_ID; id++) {
            BedrockPacketDefinition<?> source = from.getPacketDefinition(id);
            if (source == null || to.getPacketDefinition(id) == null) {
                continue;
            }
            // A null recipient means the codec does not say, so check it rather than assume.
            if (source.getRecipient() != null && source.getRecipient() != direction) {
                continue;
            }
            String name = packetName(source);
            if (DROPPED_ACROSS_THE_EDGE.contains(name)) {
                continue;
            }

            BedrockPacket decoded;
            try {
                // Populated, not default. A default StartGame or ResourcePackClientResponse cannot be
                // encoded at all, so a sweep of default instances silently skips the entire join
                // sequence — the packets that decide whether a player ever spawns.
                decoded = roundTrip(from, PacketPopulator.populate(source.getFactory().get()), id);
            } catch (Throwable ignored) {
                // A packet this codec cannot even encode for itself tells us nothing about the hop.
                continue;
            }

            try {
                encode(to, decoded);
            } catch (Throwable failure) {
                broken.add(name + " — " + rootCause(failure));
            }
        }
        return broken;
    }

    private static BedrockPacket roundTrip(BedrockCodec codec, BedrockPacket packet, int id) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static void encode(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
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
