package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds serializers whose read and write halves disagree, without needing a live server.
 *
 * <p>This is the failure mode that costs the most and shows the least. A proxy re-encodes everything
 * it decodes, so a serializer that reads a field one way and writes it another produces a packet that
 * is <em>valid</em> and <em>wrong</em>: nothing throws, nothing is dropped, no log line appears, and
 * the peer acts on corrupted data. It is how a mined block ends up not in the player's inventory —
 * the server never receives the action the client actually sent.</p>
 *
 * <p>{@code -Dbedrock.verifyReencode=true} catches these against live traffic but is far too slow to
 * play with (it re-encodes every packet, including the 320KB biome list, and the client times out on
 * the loading screen). This does the same comparison offline: encode, decode, encode again, and
 * require the two encodings to be identical.</p>
 */
class AsymmetricSerializerTest {

    private static final int MAX_PACKET_ID = 512;

    @Test
    void no1_26_40SerializerReEncodesDifferentlyFromWhatItRead() {
        List<String> asymmetric = sweep(Bedrock_v2168.CODEC);
        if (!asymmetric.isEmpty()) {
            throw new AssertionError("""
                    These 1.26.40 serializers do not round-trip: what they decode re-encodes to \
                    different bytes. The proxy relays the re-encoded copy, so the peer silently acts \
                    on corrupted data — no exception, no log line:
                      """ + String.join("\n  ", asymmetric));
        }
    }

    /**
     * The same check for 1.26.30, which has been in production far longer. Anything this finds is a
     * pre-existing bug rather than a regression from the 1.26.40 work, but it corrupts traffic just
     * the same.
     */
    @Test
    void no1_26_30SerializerReEncodesDifferentlyFromWhatItRead() {
        List<String> asymmetric = sweep(Bedrock_v1001.CODEC);
        if (!asymmetric.isEmpty()) {
            throw new AssertionError("""
                    These 1.26.30 serializers do not round-trip:
                      """ + String.join("\n  ", asymmetric));
        }
    }

    private static List<String> sweep(BedrockCodec codec) {
        List<String> asymmetric = new ArrayList<>();

        for (int id = 0; id < MAX_PACKET_ID; id++) {
            BedrockPacketDefinition<?> definition = codec.getPacketDefinition(id);
            if (definition == null) {
                continue;
            }

            ByteBuf first = Unpooled.buffer();
            ByteBuf second = Unpooled.buffer();
            try {
                BedrockPacket packet = PacketPopulator.populate(definition.getFactory().get());
                codec.tryEncode(codec.createHelper(), first, packet);

                byte[] originalBytes = ByteBufUtil.getBytes(first);
                BedrockPacket decoded = codec.tryDecode(codec.createHelper(), first, id);
                codec.tryEncode(codec.createHelper(), second, decoded);
                byte[] reencodedBytes = ByteBufUtil.getBytes(second);

                if (!java.util.Arrays.equals(originalBytes, reencodedBytes)) {
                    asymmetric.add("%s — %d bytes in, %d bytes out%n      wrote %s%n      then  %s"
                            .formatted(
                                    packet.getClass().getSimpleName(),
                                    originalBytes.length,
                                    reencodedBytes.length,
                                    ByteBufUtil.hexDump(originalBytes, 0, Math.min(48, originalBytes.length)),
                                    ByteBufUtil.hexDump(reencodedBytes, 0, Math.min(48, reencodedBytes.length))));
                }
            } catch (Throwable ignored) {
                // Cannot be built or cannot round-trip at all; that is the sweep tests' business,
                // not this one's.
            } finally {
                first.release();
                second.release();
            }
        }
        return asymmetric;
    }
}
