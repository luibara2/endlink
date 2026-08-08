package org.cloudburstmc.protocol.bedrock.codec.v1001;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Some entity-data wire slots are registered to more than one {@code EntityDataType}, because the
 * field's meaning depends on the entity: id 2/INT is VARIANT or BLOCK, id 16/INT is
 * DISPLAY_BLOCK_STATE or HORSE_FLAGS. {@code readEntityData} cannot tell which was intended, so it
 * populates <em>every</em> registered type from the one incoming value.
 *
 * <p>A client or server reads back only the type it cares about and never notices. A proxy
 * re-encodes the whole map, so one incoming field was written back out as two — inflating the entry
 * count and shifting every field after it. Because VARIANT rides on nearly every entity, this
 * corrupted the metadata of nearly every {@code AddEntity}, {@code AddItemEntity} and
 * {@code SetEntityData} the proxy relayed.
 *
 * <p>The registry below mirrors the proxy's real one, which resolves <em>any</em> runtime id rather
 * than only known blocks. That matters: with no registry the BLOCK alias deserializes to null and is
 * dropped, so the bug does not reproduce and a test would pass vacuously.
 */
class EntityDataAliasedSlotTest {

    private record TestBlockDefinition(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }

    private BedrockCodecHelper helper;

    @BeforeEach
    void setUp() {
        helper = Bedrock_v1001.CODEC.createHelper();
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return new TestBlockDefinition(runtimeId);
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        });
    }

    private ByteBuf writeMap(EntityDataMap map) {
        ByteBuf buffer = Unpooled.buffer();
        helper.writeEntityData(buffer, map);
        return buffer;
    }

    private static byte[] drain(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    @Test
    void reEncodingAnAliasedSlotReproducesTheOriginalBytes() {
        EntityDataMap original = new EntityDataMap();
        original.put(EntityDataTypes.VARIANT, 7);

        ByteBuf wire = writeMap(original);
        byte[] wireBytes = drain(wire);

        EntityDataMap decoded = new EntityDataMap();
        helper.readEntityData(wire, decoded);
        assertEquals(0, wire.readableBytes(), "reader must consume the whole field");
        wire.release();

        // Reading populated both aliases of slot 2/INT from the single incoming value.
        assertTrue(decoded.containsKey(EntityDataTypes.VARIANT));
        assertTrue(
                decoded.containsKey(EntityDataTypes.BLOCK),
                "precondition: both aliases populate, otherwise this test proves nothing"
        );

        ByteBuf reencoded = writeMap(decoded);
        byte[] reencodedBytes = drain(reencoded);
        reencoded.release();

        assertEquals(
                wireBytes.length,
                reencodedBytes.length,
                "a proxy must re-encode entity data to the length it decoded"
        );
        assertArrayEquals(wireBytes, reencodedBytes);
    }

    @Test
    void aliasedSlotIsWrittenExactlyOnce() {
        EntityDataMap both = new EntityDataMap();
        both.put(EntityDataTypes.VARIANT, 7);
        both.put(EntityDataTypes.BLOCK, new TestBlockDefinition(7));

        ByteBuf buffer = writeMap(both);
        try {
            // Entry count is the first varint, and the wire has exactly one slot 2/INT.
            assertEquals(1, buffer.getByte(buffer.readerIndex()), "two aliases must collapse to one entry");
        } finally {
            buffer.release();
        }
    }

    @Test
    void unaliasedFieldsAreUnaffected() {
        EntityDataMap map = new EntityDataMap();
        map.put(EntityDataTypes.AIR_SUPPLY, (short) 300);
        map.put(EntityDataTypes.VARIANT, 3);

        ByteBuf wire = writeMap(map);
        byte[] wireBytes = drain(wire);

        EntityDataMap decoded = new EntityDataMap();
        helper.readEntityData(wire, decoded);
        wire.release();

        ByteBuf reencoded = writeMap(decoded);
        byte[] reencodedBytes = drain(reencoded);
        reencoded.release();

        assertArrayEquals(wireBytes, reencodedBytes);
    }
}
