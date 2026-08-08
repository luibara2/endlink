package org.cloudburstmc.protocol.bedrock.codec.v975.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitionChunkGenData;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeSurfaceBuilderData;
import org.cloudburstmc.protocol.common.util.SequencedHashSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The surface builder pair at the end of a biome's chunk-gen data is optional on the wire. Upstream
 * reads it with a presence byte but writes the payload bare, which NPEs on biomes that have none and
 * silently desyncs the stream by a byte per builder on the ones that do. A proxy re-encodes what it
 * decoded, so both halves have to agree.
 */
class BiomeSurfaceBuilderOptionalityTest {

    private static final class Exposed extends BiomeDefinitionListSerializer_v975 {
        void write(ByteBuf buffer, BedrockCodecHelper helper, BiomeDefinitionChunkGenData data) {
            writeDefinitionChunkGen(buffer, helper, data, new SequencedHashSet<>());
        }

        BiomeDefinitionChunkGenData read(ByteBuf buffer, BedrockCodecHelper helper) {
            return readDefinitionChunkGen(buffer, helper, new ArrayList<>());
        }
    }

    private final Exposed serializer = new Exposed();
    private final BedrockCodecHelper helper = Bedrock_v1001.CODEC.createHelper();

    private static BiomeDefinitionChunkGenData chunkGen(BiomeSurfaceBuilderData surface,
                                                        BiomeSurfaceBuilderData subsurface) {
        return new BiomeDefinitionChunkGenData(
                null, null, null, null, null,
                false, false, false, false,
                null, null, null, null, null,
                List.of(), null,
                surface, subsurface
        );
    }

    private BiomeDefinitionChunkGenData roundTrip(BiomeDefinitionChunkGenData original) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            serializer.write(buffer, helper, original);
            BiomeDefinitionChunkGenData decoded = serializer.read(buffer, helper);
            assertEquals(0, buffer.readableBytes(), "serializer must consume exactly what it wrote");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsABiomeWithNoSurfaceBuilders() {
        BiomeDefinitionChunkGenData decoded = roundTrip(chunkGen(null, null));

        assertNull(decoded.getSurfaceBuilderData());
        assertNull(decoded.getSubsurfaceBuilderData());
    }

    @Test
    void roundTripsABiomeWithBothSurfaceBuilders() {
        BiomeSurfaceBuilderData surface = new BiomeSurfaceBuilderData(null, true, false, false, false, null, null, null);
        BiomeSurfaceBuilderData subsurface = new BiomeSurfaceBuilderData(null, false, true, false, false, null, null, null);

        BiomeDefinitionChunkGenData decoded = roundTrip(chunkGen(surface, subsurface));

        assertNotNull(decoded.getSurfaceBuilderData());
        assertNotNull(decoded.getSubsurfaceBuilderData());
        assertEquals(surface, decoded.getSurfaceBuilderData());
        assertEquals(subsurface, decoded.getSubsurfaceBuilderData());
    }

    @Test
    void roundTripsABiomeWithOnlyOneSurfaceBuilder() {
        BiomeSurfaceBuilderData surface = new BiomeSurfaceBuilderData(null, true, false, false, false, null, null, null);

        BiomeDefinitionChunkGenData decoded = roundTrip(chunkGen(surface, null));

        assertEquals(surface, decoded.getSurfaceBuilderData());
        assertNull(decoded.getSubsurfaceBuilderData());
    }
}
