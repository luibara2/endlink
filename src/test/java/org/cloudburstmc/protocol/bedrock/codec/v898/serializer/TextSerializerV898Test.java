package org.cloudburstmc.protocol.bedrock.codec.v898.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSerializerV898Test {
    @Test
    void writesChatWithVariantNames() {
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.CHAT);
        packet.setSourceName("luibara2");
        packet.setMessage("test");
        packet.setXuid("2535459084817261");
        packet.setPlatformChatId("");
        packet.setFilteredMessage("");

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);

            assertFalse(buffer.readBoolean());
            assertEquals(1, buffer.readUnsignedByte());
            assertEquals("chat", helper.readString(buffer));
            assertEquals("whisper", helper.readString(buffer));
            assertEquals("announcement", helper.readString(buffer));
            assertEquals(TextPacket.Type.CHAT.ordinal(), buffer.readUnsignedByte());
            assertEquals("luibara2", helper.readString(buffer));
            assertEquals("test", helper.readString(buffer));
            assertEquals("2535459084817261", helper.readString(buffer));
            assertEquals("", helper.readString(buffer));
            assertFalse(buffer.readBoolean());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void preservesTranslationFlagWhenReencodingTranslationText() {
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeBoolean(true);
            buffer.writeByte(2);
            helper.writeString(buffer, "translate");
            helper.writeString(buffer, "popup");
            helper.writeString(buffer, "jukeboxPopup");
            buffer.writeByte(TextPacket.Type.TRANSLATION.ordinal());
            helper.writeString(buffer, "commands.gamemode.success.self");
            VarInts.writeUnsignedInt(buffer, 1);
            helper.writeString(buffer, "Creative");
            helper.writeString(buffer, "");
            helper.writeString(buffer, "");
            buffer.writeBoolean(false);

            TextPacket decoded = (TextPacket) Bedrock_v898.CODEC.tryDecode(
                    Bedrock_v898.CODEC.createHelper(),
                    buffer,
                    Bedrock_v898.CODEC.getPacketDefinition(TextPacket.class).getId()
            );
            assertTrue(decoded.isNeedsTranslation());
            assertEquals(TextPacket.Type.TRANSLATION, decoded.getType());
            assertEquals("commands.gamemode.success.self", decoded.getMessage());
            assertEquals(1, decoded.getParameters().size());
            assertEquals("Creative", decoded.getParameters().get(0));

            ByteBuf reencoded = Unpooled.buffer();
            try {
                Bedrock_v898.CODEC.tryEncode(Bedrock_v898.CODEC.createHelper(), reencoded, decoded);
                assertTrue(reencoded.readBoolean());
                assertEquals(2, reencoded.readUnsignedByte());
            } finally {
                reencoded.release();
            }
        } finally {
            buffer.release();
        }
    }
}
