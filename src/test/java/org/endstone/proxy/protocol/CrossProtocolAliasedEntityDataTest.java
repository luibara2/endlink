package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aliased entity-data slots carried across the 1.26.40 &harr; 1.26.30 hop.
 *
 * <p>Some wire slots are registered to more than one {@code EntityDataType} because the field's
 * meaning depends on the entity, so {@code readEntityData} populates <em>every</em> registered type
 * from one incoming value and {@code writeEntityData} deduplicates on {@code (id, format)} to put it
 * back as one. {@code EntityDataAliasedSlotTest} pins that within a single codec.
 *
 * <p>The deduplication only collapses the aliases if the <em>writing</em> codec agrees with the
 * reading one about which slot and format each alias occupies — and 1.26.40 rebuilt exactly this
 * map. Upstream's {@code Bedrock_v2168} does {@code ENTITY_DATA.remove(16)} and re-inserts four
 * types there where 1.26.30 has two. So one value read from slot 16 by the backend's codec becomes
 * two map entries, and if the client's codec resolves those to different slots it writes them back
 * out as <em>two</em> fields. The entry count is a prefix, so the packet stays perfectly readable —
 * it just describes an entity that is not the one the backend sent.
 *
 * <p>Nothing else covers this pairing. {@code CrossProtocolEntityDataTest} hops one type at a time
 * and only asserts the encode does not throw, and it cannot reach the aliases at all: it has no
 * block-definition registry, so the BLOCK alias deserializes to null and is silently dropped.
 *
 * <p>The registry below mirrors the proxy's real one, which resolves <em>any</em> runtime id rather
 * than only known blocks. Without that this test would pass vacuously, for the same reason.
 */
class CrossProtocolAliasedEntityDataTest {

    private record AnyBlockDefinition(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }

    private static BedrockCodecHelper helperFor(BedrockCodec codec) {
        BedrockCodecHelper helper = codec.createHelper();
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return new AnyBlockDefinition(runtimeId);
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        });
        return helper;
    }

    /**
     * Slot 2/INT is VARIANT or BLOCK, and VARIANT rides on nearly every entity, so this is the alias
     * that decides whether ordinary mob metadata survives the hop.
     */
    @Test
    void variantSlotCrossesTheHopAsOneField() {
        assertAliasedSlotSurvives(EntityDataTypes.VARIANT, 7, EntityDataTypes.BLOCK);
    }

    /**
     * Slot 16 is the one 1.26.40 actually rebuilt, and it is <em>not</em> fixed by the guard above —
     * recorded here so the remaining gap is a known quantity rather than a surprise.
     *
     * <p>1.26.30 registers DISPLAY_BLOCK_STATE and HORSE_FLAGS both at {@code (16, INT)}, so reading
     * one value populates both. 1.26.40 splits them by format: DISPLAY_BLOCK_STATE stays
     * {@code (16, INT)} but HORSE_FLAGS becomes {@code (16, LONG)}. The deduplication keys on
     * {@code (id, format)}, which is what identifies a wire slot within one version — across these
     * two it no longer does, so both are written and the client is sent id 16 twice with conflicting
     * formats.
     *
     * <p>Left as-is deliberately. Widening the key to the id alone would collapse them, but which of
     * the two survives would then be decided by map iteration order, and if HORSE_FLAGS won it would
     * hand {@code VarInts.writeLong} an Integer — turning a duplicated field into a
     * {@code ClassCastException} that drops the whole packet. A duplicate is the safer failure.
     *
     * <p>Reachable only for entities that use slot 16 at all — falling blocks, display minecarts,
     * horses — not for the VARIANT case above, which is on nearly every entity.
     */
    @Test
    void slot16StillCrossesTheHopAsTwoFieldsBecauseTheFormatsDiverged() {
        BedrockCodecHelper backend = helperFor(Bedrock_v1001.CODEC);
        BedrockCodecHelper client = helperFor(Bedrock_v2168.CODEC);

        EntityDataMap sent = new EntityDataMap();
        putUnchecked(sent, EntityDataTypes.DISPLAY_BLOCK_STATE, new AnyBlockDefinition(7));

        EntityDataMap decoded = new EntityDataMap();
        ByteBuf fromBackend = Unpooled.buffer();
        try {
            backend.writeEntityData(fromBackend, sent);
            assertEquals(1, entryCount(fromBackend), "precondition: the backend writes one field");
            backend.readEntityData(fromBackend, decoded);
        } finally {
            fromBackend.release();
        }

        assertTrue(decoded.containsKey(EntityDataTypes.HORSE_FLAGS), "precondition: slot 16 aliases populate");

        ByteBuf toClient = Unpooled.buffer();
        try {
            client.writeEntityData(toClient, decoded);
            assertEquals(2, entryCount(toClient), """
                    Characterisation, not an endorsement. If this becomes 1, slot 16 has been fixed \
                    too and the note on this test should go. If it throws, the format mismatch has \
                    started killing the packet and that is worse than the duplicate.""");
        } finally {
            toClient.release();
        }
    }

    private static void assertAliasedSlotSurvives(
            org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType<?> written,
            Object value,
            org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType<?> alias
    ) {
        BedrockCodecHelper backend = helperFor(Bedrock_v1001.CODEC);
        BedrockCodecHelper client = helperFor(Bedrock_v2168.CODEC);

        EntityDataMap sent = new EntityDataMap();
        putUnchecked(sent, written, value);

        ByteBuf fromBackend = Unpooled.buffer();
        EntityDataMap decoded = new EntityDataMap();
        try {
            backend.writeEntityData(fromBackend, sent);
            assertEquals(1, entryCount(fromBackend), "precondition: the backend writes one field");
            backend.readEntityData(fromBackend, decoded);
            assertEquals(0, fromBackend.readableBytes(), "the backend's reader must consume its own field");
        } finally {
            fromBackend.release();
        }

        assertTrue(
                decoded.containsKey(alias),
                "precondition: reading must populate both aliases, otherwise this test proves nothing"
        );

        ByteBuf toClient = Unpooled.buffer();
        try {
            client.writeEntityData(toClient, decoded);
            assertEquals(
                    1,
                    entryCount(toClient),
                    "one entity-data field from a 1.26.30 backend was written to a 1.26.40 client as more than"
                            + " one, because the aliases of this slot do not collapse in the client's map. The"
                            + " packet still reads, so nothing throws — the client is simply told something the"
                            + " backend never said"
            );

            EntityDataMap readBack = new EntityDataMap();
            client.readEntityData(toClient, readBack);
            assertEquals(0, toClient.readableBytes(), "the client's reader must consume the whole field");
        } finally {
            toClient.release();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putUnchecked(
            EntityDataMap map,
            org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType<?> type,
            Object value
    ) {
        map.put((org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType) type, value);
    }

    /** The entry count is the leading varint of an entity-data field, and stays under 128 here. */
    private static int entryCount(ByteBuf buffer) {
        return buffer.getByte(buffer.readerIndex());
    }
}
