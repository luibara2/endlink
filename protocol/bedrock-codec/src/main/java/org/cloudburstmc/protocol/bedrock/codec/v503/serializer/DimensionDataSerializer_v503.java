package org.cloudburstmc.protocol.bedrock.codec.v503.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

public class DimensionDataSerializer_v503 implements BedrockPacketSerializer<DimensionDataPacket> {

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, DimensionDataPacket packet) {
        helper.writeArray(buffer, packet.getDefinitions(), this::writeDefinition);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, DimensionDataPacket packet) {
        helper.readArray(buffer, packet.getDefinitions(), this::readDefinition);
    }

    /**
     * The two height varints are {@code Minimum Y} then {@code Height Range}, not maximum then
     * minimum.
     *
     * <p>Mojang's own dump names them that way, and the second is a span rather than a coordinate.
     * The bytes are unchanged by reading them correctly &mdash; a relay that reads a definition and
     * writes it back produces the same two varints either way &mdash; so this costs nothing and is
     * only about what {@link DimensionDefinition}'s accessors mean. It starts mattering the moment a
     * definition is built from real heights, or read from a peer that has this right.
     */
    protected void writeDefinition(ByteBuf buffer, BedrockCodecHelper helper, DimensionDefinition definition) {
        helper.writeString(buffer, definition.getId());
        VarInts.writeInt(buffer, definition.getMinimumHeight());
        VarInts.writeInt(buffer, definition.getMaximumHeight() - definition.getMinimumHeight());
        VarInts.writeInt(buffer, definition.getGeneratorType());
    }

    protected DimensionDefinition readDefinition(ByteBuf buffer, BedrockCodecHelper helper) {
        String id = helper.readString(buffer);
        int minimumHeight = VarInts.readInt(buffer);
        int maximumHeight = minimumHeight + VarInts.readInt(buffer);
        int generatorType = VarInts.readInt(buffer);
        return new DimensionDefinition(id, maximumHeight, minimumHeight, generatorType, 0, null, null, 0, false);
    }
}
