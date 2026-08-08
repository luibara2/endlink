package org.endstone.proxy.command;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRequestProtocol898Test {
    @Test
    void preservesCommandVersionString() {
        CommandRequestPacket packet = new CommandRequestPacket();
        packet.setCommand("/gamemode creative");
        packet.setCommandOriginData(new CommandOriginData(
                CommandOriginType.PLAYER,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "request-id",
                42
        ));
        packet.setInternal(false);
        packet.setCommandVersion("client-command-version");

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);
            CommandRequestPacket decoded = (CommandRequestPacket) Bedrock_v898.CODEC.tryDecode(
                    Bedrock_v898.CODEC.createHelper(),
                    buffer,
                    Bedrock_v898.CODEC.getPacketDefinition(CommandRequestPacket.class).getId()
            );

            assertEquals("/gamemode creative", decoded.getCommand());
            assertEquals("client-command-version", decoded.getCommandVersion());
            assertEquals(packet.getCommandOriginData(), decoded.getCommandOriginData());
        } finally {
            buffer.release();
        }
    }
}
