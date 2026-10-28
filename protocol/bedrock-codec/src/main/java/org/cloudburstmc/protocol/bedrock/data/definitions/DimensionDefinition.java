package org.cloudburstmc.protocol.bedrock.data.definitions;

import lombok.Value;

import java.util.UUID;

/**
 * One entry of {@code DimensionDataPacket}.
 *
 * <p>The wire carries {@code Minimum Y} and then a {@code Height Range} &mdash; a span, not a
 * second coordinate. The serializers convert, so {@link #getMaximumHeight()} is a real upper bound
 * here rather than the first varint on the wire. The constructor keeps its long-standing argument
 * order, maximum before minimum, so that addons built against it still compile.
 */
@Value
public class DimensionDefinition {
    String id;
    int maximumHeight;
    int minimumHeight;
    int generatorType;
    int dimensionType;
    /**
     * @since v2168
     */
    UUID packId;
    /**
     * @since v2192
     */
    String defaultBiome;
    /**
     * @since v2225
     */
    int cloudHeight;
    /**
     * @since v2225
     */
    boolean renderClouds;

    public DimensionDefinition(String id, int maximumHeight, int minimumHeight, int generatorType, int dimensionType,
                               UUID packId, String defaultBiome, int cloudHeight, boolean renderClouds) {
        this.id = id;
        this.maximumHeight = maximumHeight;
        this.minimumHeight = minimumHeight;
        this.generatorType = generatorType;
        this.dimensionType = dimensionType;
        this.packId = packId;
        this.defaultBiome = defaultBiome;
        this.cloudHeight = cloudHeight;
        this.renderClouds = renderClouds;
    }

    /**
     * The constructor as it was before 1.26.60 added the two cloud fields, kept so that addons and
     * tests built against it still compile. The clouds read as absent: height 0, not rendered.
     */
    public DimensionDefinition(String id, int maximumHeight, int minimumHeight, int generatorType, int dimensionType,
                               UUID packId, String defaultBiome) {
        this(id, maximumHeight, minimumHeight, generatorType, dimensionType, packId, defaultBiome, 0, false);
    }
}
