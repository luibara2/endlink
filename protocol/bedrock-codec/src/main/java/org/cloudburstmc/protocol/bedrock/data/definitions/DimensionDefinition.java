package org.cloudburstmc.protocol.bedrock.data.definitions;

import lombok.Value;

import java.util.UUID;

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
}
