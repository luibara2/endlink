package org.cloudburstmc.protocol.bedrock.codec.v924;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A translated chat line must still be translated after the proxy has decoded and re-encoded it.
 *
 * <p>Found on a PowerNukkitX backend: its join broadcast is {@code TRANSLATION}
 * "%multiplayer.player.joined" with the player's name as a parameter, and players behind the proxy
 * saw the raw key because the relayed copy went out with {@code needsTranslation} cleared.</p>
 */
final class TextTranslationRelayTest {

    @Test
    void theTranslationFlagSurvivesARelay() throws Exception {
        BedrockCodec codec = CanonicalProtocol.newest().codec();
        int id = codec.getPacketDefinition(TextPacket.class).getId();

        TextPacket original = new TextPacket();
        original.setType(TextPacket.Type.TRANSLATION);
        original.setNeedsTranslation(true);
        original.setMessage("§e%multiplayer.player.joined");
        original.setParameters(List.of("luibara2"));
        original.setXuid("");
        original.setPlatformChatId("");
        original.setFilteredMessage("");

        ByteBuf wire = Unpooled.buffer();
        ByteBuf relayed = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), wire, original);
            byte[] fromBackend = new byte[wire.readableBytes()];
            wire.getBytes(wire.readerIndex(), fromBackend);

            TextPacket decoded = (TextPacket) codec.tryDecode(codec.createHelper(), wire, id);
            assertTrue(decoded.isNeedsTranslation());
            assertEquals(List.of("luibara2"), decoded.getParameters());

            codec.tryEncode(codec.createHelper(), relayed, decoded);
            byte[] toClient = new byte[relayed.readableBytes()];
            relayed.getBytes(relayed.readerIndex(), toClient);
            assertEquals(java.util.Arrays.toString(fromBackend), java.util.Arrays.toString(toClient),
                    "the relayed packet must be byte-identical to what the backend sent");
        } finally {
            wire.release();
            relayed.release();
        }
    }
}
