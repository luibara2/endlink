package org.cloudburstmc.protocol.bedrock.codec.v898.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamOption;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvailableCommandsSerializerV898Test {
    @Test
    void writesParameterAsValueAndEnumTypeShorts() {
        TestSerializer serializer = new TestSerializer();
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        CommandParamData param = new CommandParamData();
        param.setName("name");
        param.setType(CommandParam.STRING);
        param.setOptional(true);

        ByteBuf buffer = Unpooled.buffer();
        try {
            serializer.write(buffer, helper, param);

            assertEquals("name", helper.readString(buffer));
            assertEquals(44, buffer.readUnsignedShortLE());
            assertEquals(16, buffer.readUnsignedShortLE());
            assertEquals(true, buffer.readBoolean());
            assertEquals(0, buffer.readUnsignedByte());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void readsParameterFromValueAndEnumTypeShorts() {
        TestSerializer serializer = new TestSerializer();
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();

        ByteBuf buffer = Unpooled.buffer();
        try {
            helper.writeString(buffer, "name");
            buffer.writeShortLE(44);
            buffer.writeShortLE(16);
            buffer.writeBoolean(true);
            buffer.writeByte(0);

            CommandParamData param = serializer.read(buffer, helper);

            assertEquals("name", param.getName());
            assertEquals(CommandParam.STRING, param.getType());
            assertEquals(true, param.isOptional());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void preservesUnknownParameterTypeId() {
        TestSerializer serializer = new TestSerializer();
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();

        ByteBuf buffer = Unpooled.buffer();
        try {
            helper.writeString(buffer, "value");
            buffer.writeShortLE(56);
            buffer.writeShortLE(16);
            buffer.writeBoolean(false);
            buffer.writeByte(0);

            CommandParamData param = serializer.read(buffer, helper);
            assertEquals(56, param.getType().getDefaultValue());

            serializer.write(buffer, helper, param);
            assertEquals("value", helper.readString(buffer));
            assertEquals(56, buffer.readUnsignedShortLE());
            assertEquals(16, buffer.readUnsignedShortLE());
            assertEquals(false, buffer.readBoolean());
            assertEquals(0, buffer.readUnsignedByte());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void preservesUnknownEnumTypeFlags() {
        TestSerializer serializer = new TestSerializer();
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();

        ByteBuf buffer = Unpooled.buffer();
        try {
            helper.writeString(buffer, "chainedCommand");
            buffer.writeShortLE(74);
            buffer.writeShortLE(2064);
            buffer.writeBoolean(false);
            buffer.writeByte(0);

            CommandParamData param = serializer.read(buffer, helper);
            assertEquals(74, param.getProtocolValueType());
            assertEquals(2064, param.getProtocolEnumType());

            serializer.write(buffer, helper, param);
            assertEquals("chainedCommand", helper.readString(buffer));
            assertEquals(74, buffer.readUnsignedShortLE());
            assertEquals(2064, buffer.readUnsignedShortLE());
            assertEquals(false, buffer.readBoolean());
            assertEquals(0, buffer.readUnsignedByte());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void writesCommandFlagsWithUnusedLowBit() {
        TestSerializer serializer = new TestSerializer();
        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        CommandParamData param = new CommandParamData();
        param.setName("target");
        param.setType(CommandParam.TARGET);
        param.getOptions().add(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION);
        param.getOptions().add(CommandParamOption.HAS_SEMANTIC_CONSTRAINT);
        param.getOptions().add(CommandParamOption.ENUM_AS_CHAINED_COMMAND);

        ByteBuf buffer = Unpooled.buffer();
        try {
            serializer.write(buffer, helper, param);
            assertEquals("target", helper.readString(buffer));
            assertEquals(8, buffer.readUnsignedShortLE());
            assertEquals(16, buffer.readUnsignedShortLE());
            assertEquals(false, buffer.readBoolean());
            assertEquals(0b0000_1110, buffer.readUnsignedByte());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static final class TestSerializer extends AvailableCommandsSerializer_v898 {
        private TestSerializer() {
            super(TypeMap.builder(CommandParam.class)
                    .insert(8, CommandParam.TARGET)
                    .insert(44, CommandParam.STRING)
                    .build());
        }

        private void write(ByteBuf buffer, BedrockCodecHelper helper, CommandParamData param) {
            writeParameter(buffer, helper, param, List.of(), List.of(), List.of());
        }

        private CommandParamData read(ByteBuf buffer, BedrockCodecHelper helper) {
            return readParameter(buffer, helper, List.of(), List.of(), Set.<Consumer<List<CommandEnumData>>>of());
        }
    }
}
