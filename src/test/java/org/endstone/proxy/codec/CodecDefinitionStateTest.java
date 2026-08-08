package org.endstone.proxy.codec;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodecDefinitionStateTest {
    @Test
    void unknownBlockDefinitionsPreserveRuntimeIds() {
        BlockDefinition definition = CodecDefinitionState.unknownBlockDefinitions().getDefinition(42);

        assertNotNull(definition);
        assertEquals(42, definition.getRuntimeId());
        assertTrue(CodecDefinitionState.unknownBlockDefinitions().isRegistered(definition));
    }

    @Test
    void unknownItemDefinitionsPreserveRuntimeIds() {
        ItemDefinition definition = CodecDefinitionState.unknownItemDefinitions().getDefinition(512);

        assertNotNull(definition);
        assertEquals(512, definition.getRuntimeId());
        assertEquals("minecraft:unknown_512", definition.getIdentifier());
        assertTrue(CodecDefinitionState.unknownItemDefinitions().isRegistered(definition));
    }
}
