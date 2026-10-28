package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.DimensionDataSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.UUID;

public class DimensionDataSerializer_v2192 extends DimensionDataSerializer_v2168 {

    public static final DimensionDataSerializer_v2192 INSTANCE = new DimensionDataSerializer_v2192();

    /**
     * 1.26.50 added a default biome to every dimension definition.
     *
     * <p>The {@code definition.getDefaultBiome() == null} branch is this tree's, not upstream's, and
     * it is the difference between a relayed packet and a dropped one. A {@link DimensionDefinition}
     * that a 1.26.45 or older backend sent was decoded by a serializer with no such field, which
     * passes {@code null} for it; {@code helper.writeString} rejects null, so re-encoding that
     * definition for a 1.26.50 client would throw inside the serializer and the packet would never
     * arrive. The field is not optional on the wire, so there is nothing to omit &mdash; the empty
     * identifier is what "the sender had no opinion" has to look like.
     */
    @Override
    protected void writeDefinition(ByteBuf buffer, BedrockCodecHelper helper, DimensionDefinition definition) {
        super.writeDefinition(buffer, helper, definition);
        String defaultBiome = definition.getDefaultBiome();
        helper.writeString(buffer, defaultBiome == null ? "" : defaultBiome);
    }

    @Override
    protected DimensionDefinition readDefinition(ByteBuf buffer, BedrockCodecHelper helper) {
        String id = helper.readString(buffer);
        int minimumHeight = VarInts.readInt(buffer);
        int maximumHeight = minimumHeight + VarInts.readInt(buffer);
        int generatorType = VarInts.readInt(buffer);
        int dimensionType = VarInts.readInt(buffer);
        UUID packId = helper.readUuid(buffer);
        String defaultBiome = helper.readString(buffer);
        return new DimensionDefinition(id, maximumHeight, minimumHeight, generatorType, dimensionType, packId, defaultBiome, 0, false);
    }
}
