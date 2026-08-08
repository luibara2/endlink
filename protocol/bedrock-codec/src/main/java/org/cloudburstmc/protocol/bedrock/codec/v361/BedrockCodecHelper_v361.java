package org.cloudburstmc.protocol.bedrock.codec.v361;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v340.BedrockCodecHelper_v340;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataFormat;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureAnimationMode;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureMirror;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureRotation;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureSettings;
import org.cloudburstmc.protocol.bedrock.transformer.EntityDataTransformer;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.*;

import static org.cloudburstmc.protocol.common.util.Preconditions.checkArgument;
import static org.cloudburstmc.protocol.common.util.Preconditions.checkNotNull;

public class BedrockCodecHelper_v361 extends BedrockCodecHelper_v340 {

    public BedrockCodecHelper_v361(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes) {
        super(entityData, gameRulesTypes);
    }

    @Override
    public void readEntityData(ByteBuf buffer, EntityDataMap entityDataMap) {
        checkNotNull(entityDataMap, "entityDataMap");

        int length = VarInts.readUnsignedInt(buffer);
        checkArgument(this.encodingSettings.maxListSize() <= 0 || length <= this.encodingSettings.maxListSize(), "Entity data size is too big: %s", length);

        for (int i = 0; i < length; i++) {
            int id = VarInts.readUnsignedInt(buffer);
            int formatId = VarInts.readUnsignedInt(buffer);
            EntityDataFormat format = EntityDataFormat.values()[formatId];

            Object value;
            switch (format) {
                case BYTE:
                    value = buffer.readByte();
                    break;
                case SHORT:
                    value = buffer.readShortLE();
                    break;
                case INT:
                    value = VarInts.readInt(buffer);
                    break;
                case FLOAT:
                    value = buffer.readFloatLE();
                    break;
                case STRING:
                    value = readString(buffer);
                    break;
                case NBT:
                    value = this.readTag(buffer, Object.class);
                    break;
                case VECTOR3I:
                    value = readVector3i(buffer);
                    break;
                case LONG:
                    value = VarInts.readLong(buffer);
                    break;
                case VECTOR3F:
                    value = readVector3f(buffer);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown entity data type received");
            }

            EntityDataTypeMap.Definition<?>[] definitions = this.entityData.fromId(id, format);
            if (definitions != null) {
                for (EntityDataTypeMap.Definition<?> definition : definitions) {
                    //noinspection unchecked
                    EntityDataTransformer<Object, ?> transformer = (EntityDataTransformer<Object, ?>) definition.getTransformer();
                    Object transformedValue = transformer.deserialize(this, entityDataMap, value);
                    if (transformedValue != null) {
                        entityDataMap.put(definition.getType(), transformedValue);
                    }
                }
            } else {
                log.debug("Unknown entity data: {} type {} value {}", id, format, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void writeEntityData(ByteBuf buffer, EntityDataMap entityDataMap) {
        checkNotNull(entityDataMap, "entityDataMap");

        // Collect serialized entries first
        List<Map.Entry<EntityDataTypeMap.Definition<?>, Object>> serializedEntries = new LinkedList<>();

        // Several wire slots are registered to more than one EntityDataType, because the meaning of
        // the field depends on the entity: id 2/INT is VARIANT or BLOCK, id 16/INT is
        // DISPLAY_BLOCK_STATE or HORSE_FLAGS. readEntityData() cannot tell which was intended, so it
        // populates *every* registered type from the single incoming value. That is harmless for a
        // client or server, which reads back only the type it cares about — but a proxy re-encodes
        // the whole map, so without this guard one incoming field is written back out as two,
        // corrupting both the entry count and the field layout for the receiving client.
        Set<Long> writtenSlots = new HashSet<>();

        for (Map.Entry<EntityDataType<?>, Object> entry : entityDataMap.entrySet()) {
            EntityDataTypeMap.Definition<?> definition = this.entityData.fromType(entry.getKey());

            // The wire has one slot per (id, format); the aliased types all round-trip to the same
            // value, so keeping the first is lossless.
            long slot = ((long) definition.getId() << 8) | definition.getFormat().ordinal();
            if (!writtenSlots.add(slot)) {
                continue;
            }

            try {
                Object value = ((EntityDataTransformer<?, Object>) definition.getTransformer())
                        .serialize(this, entityDataMap, entry.getValue());

                // Skip if transformer returns null (indicating this entry shouldn't be serialized)
                if (value == null) {
                    writtenSlots.remove(slot);
                    continue;
                }

                serializedEntries.add(new AbstractMap.SimpleEntry<>(definition, value));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to encode EntityData " + definition.getId() + " of " + definition.getType().getTypeName(), e);
            }
        }

        VarInts.writeUnsignedInt(buffer, serializedEntries.size());

        for (Map.Entry<EntityDataTypeMap.Definition<?>, Object> entry : serializedEntries) {
            EntityDataTypeMap.Definition<?> definition = entry.getKey();
            Object value = entry.getValue();
            VarInts.writeUnsignedInt(buffer, definition.getId());
            VarInts.writeUnsignedInt(buffer, definition.getFormat().ordinal());

            switch (definition.getFormat()) {
                case BYTE:
                    buffer.writeByte((byte) value);
                    break;
                case SHORT:
                    buffer.writeShortLE((short) value);
                    break;
                case INT:
                    VarInts.writeInt(buffer, (int) value);
                    break;
                case FLOAT:
                    buffer.writeFloatLE((float) value);
                    break;
                case STRING:
                    writeString(buffer, (String) value);
                    break;
                case NBT:
                    this.writeTag(buffer, value);
                    break;
                case VECTOR3I:
                    writeVector3i(buffer, (Vector3i) value);
                    break;
                case LONG:
                    VarInts.writeLong(buffer, (long) value);
                    break;
                case VECTOR3F:
                    writeVector3f(buffer, (Vector3f) value);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown entity data type " + definition.getFormat());
            }
        }
    }

    @Override
    public StructureSettings readStructureSettings(ByteBuf buffer) {
        String paletteName = this.readString(buffer);
        boolean ignoringEntities = buffer.readBoolean();
        boolean ignoringBlocks = buffer.readBoolean();
        Vector3i size = this.readBlockPosition(buffer);
        Vector3i offset = this.readBlockPosition(buffer);
        long lastEditedByEntityId = VarInts.readLong(buffer);
        StructureRotation rotation = StructureRotation.from(buffer.readByte());
        StructureMirror mirror = StructureMirror.from(buffer.readByte());
        float integrityValue = buffer.readFloatLE();
        int integritySeed = buffer.readIntLE();

        return new StructureSettings(paletteName, ignoringEntities, ignoringBlocks, true, size, offset, lastEditedByEntityId,
                rotation, mirror, StructureAnimationMode.NONE, 0f, integrityValue, integritySeed,
                Vector3f.ZERO);
    }

    @Override
    public void writeStructureSettings(ByteBuf buffer, StructureSettings settings) {
        this.writeString(buffer, settings.getPaletteName());
        buffer.writeBoolean(settings.isIgnoringEntities());
        buffer.writeBoolean(settings.isIgnoringBlocks());
        this.writeBlockPosition(buffer, settings.getSize());
        this.writeBlockPosition(buffer, settings.getOffset());
        VarInts.writeLong(buffer, settings.getLastEditedByEntityId());
        buffer.writeByte(settings.getRotation().ordinal());
        buffer.writeByte(settings.getMirror().ordinal());
        buffer.writeFloatLE(settings.getIntegrityValue());
        buffer.writeIntLE(settings.getIntegritySeed());
    }
}
