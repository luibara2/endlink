package org.cloudburstmc.protocol.bedrock.codec.v898.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOutputMessage;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOutputType;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandOutputSerializerV898Test {
    @Test
    void commandOutputEndsAfterMessageArray() {
        CommandOutputPacket packet = new CommandOutputPacket();
        packet.setCommandOriginData(new CommandOriginData(
                CommandOriginType.PLAYER,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "request-id",
                42
        ));
        packet.setType(CommandOutputType.ALL_OUTPUT);
        packet.setSuccessCount(1);
        packet.setData("must not be encoded");

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);

            helper.readCommandOrigin(buffer);
            assertEquals("alloutput", helper.readString(buffer));
            assertEquals(1, buffer.readUnsignedIntLE());
            assertEquals(0, VarInts.readUnsignedInt(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void commandOutputRoundTripsMessagesForProtocol898() {
        CommandOutputPacket packet = new CommandOutputPacket();
        packet.setCommandOriginData(new CommandOriginData(
                CommandOriginType.PLAYER,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "request-id",
                7
        ));
        packet.setType(CommandOutputType.ALL_OUTPUT);
        packet.setSuccessCount(1);
        packet.getMessages().add(new CommandOutputMessage(false, "commands.gamemode.success.self", new String[]{"creative"}));

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);
            CommandOutputPacket decoded = (CommandOutputPacket) Bedrock_v898.CODEC.tryDecode(
                    Bedrock_v898.CODEC.createHelper(),
                    buffer,
                    Bedrock_v898.CODEC.getPacketDefinition(CommandOutputPacket.class).getId()
            );

            assertEquals(CommandOutputType.ALL_OUTPUT, decoded.getType());
            assertEquals(1, decoded.getSuccessCount());
            assertEquals(1, decoded.getMessages().size());
            assertEquals("commands.gamemode.success.self", decoded.getMessages().get(0).getMessageId());
            assertEquals(false, decoded.getMessages().get(0).isInternal());
            assertEquals("creative", decoded.getMessages().get(0).getParameters()[0]);
        } finally {
            buffer.release();
        }
    }
}
