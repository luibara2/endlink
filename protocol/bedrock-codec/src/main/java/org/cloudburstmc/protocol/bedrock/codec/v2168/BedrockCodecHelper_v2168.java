package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v975.BedrockCodecHelper_v975;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.GatheringsConfigurationJoinInfo;
import org.cloudburstmc.protocol.bedrock.data.PresenceConfiguration;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataFormat;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.data.skin.*;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureAnimationMode;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureMirror;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureRotation;
import org.cloudburstmc.protocol.bedrock.data.structure.StructureSettings;
import org.cloudburstmc.protocol.bedrock.transformer.EntityDataTransformer;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.cloudburstmc.protocol.common.util.stream.LittleEndianByteBufInputStream;
import org.cloudburstmc.protocol.common.util.stream.LittleEndianByteBufOutputStream;

import java.awt.Color;
import java.io.IOException;
import java.util.*;

import static java.util.Objects.requireNonNull;
import static org.cloudburstmc.protocol.common.util.Preconditions.checkArgument;
import static org.cloudburstmc.protocol.common.util.Preconditions.checkNotNull;

public class BedrockCodecHelper_v2168 extends BedrockCodecHelper_v975 {

    public BedrockCodecHelper_v2168(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes, TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                    TypeMap<ContainerSlotType> containerSlotTypes, TypeMap<Ability> abilities, TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
    }

    /**
     * Whether the peer this helper serves puts the constant {@code true} in a {@code RemoveScore}
     * entry, ahead of the optional objective name's own presence flag.
     *
     * <p>Protocol 2168 covers five Minecraft releases, 1.26.40 through 1.26.44, and they do not all
     * write that entry the same way. 1.26.44 gave the objective name the {@code isKeyedSetterGetter}
     * trait, so cereal emits a fixed {@code true} in front of the optional; 1.26.40 to 1.26.43 do
     * not, and 1.26.45 drops it again — but 1.26.45 is protocol 2169, so it can never reach this
     * helper. The protocol number does not distinguish the two shapes, which is the whole problem:
     * both sides negotiate 2168 and then disagree about a byte.
     *
     * <p>So the shape has to come from the peer's <em>Minecraft</em> version rather than its
     * protocol version, and it is per-connection state because a proxy holds two peers at once and
     * they are routinely on different releases. Defaults to {@code true}, the current release.
     *
     * @see org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.SetScoreSerializer_v2168
     */
    private boolean removeScoreKeyedConstant = true;

    public boolean isRemoveScoreKeyedConstant() {
        return this.removeScoreKeyedConstant;
    }

    public void setRemoveScoreKeyedConstant(boolean removeScoreKeyedConstant) {
        this.removeScoreKeyedConstant = removeScoreKeyedConstant;
    }

    /**
     * 1.26.40 drops the two presence names and leaves only an optional rich presence id.
     *
     * <p>See {@link org.cloudburstmc.protocol.bedrock.codec.v975.BedrockCodecHelper_v975#readPresenceConfiguration}
     * — without these the whole family throws {@link UnsupportedOperationException} out of
     * StartGame, which is the difference between a player spawning and not.</p>
     */
    @Override
    public PresenceConfiguration readPresenceConfiguration(ByteBuf buffer) {
        return new PresenceConfiguration(null, null, this.readOptional(buffer, null, this::readString));
    }

    @Override
    public void writePresenceConfiguration(ByteBuf buffer, PresenceConfiguration configuration) {
        this.writeOptionalNull(buffer, configuration.getRichPresenceId(), this::writeString);
    }

    /**
     * 1.26.40 writes an integer game rule inside StartGame as a fixed 4-byte little-endian int32.
     * 1.26.30 wrote a zigzag varint. Booleans and floats are unchanged.
     *
     * <p><b>Local delta: upstream CloudburstMC does not have this and is wrong here.</b> The evidence
     * is a captured 1.26.40 StartGame, and it verifies itself — the value bytes {@code 01 00 00 00}
     * are followed by {@code 0a} and exactly ten ASCII bytes spelling {@code locatorbar}, the next
     * rule's name. Read as a zigzag varint the value decodes to -1 and the reader lands three bytes
     * short, so the following name is garbage and every field after the gamerule array is corrupt.
     * That is precisely how the live decode failed.</p>
     *
     * <p>Note that upstream already disagrees with itself: {@link
     * org.cloudburstmc.protocol.bedrock.codec.v844.BedrockCodecHelper_v844#readGameRule} reads the
     * same value with {@code readIntLE}, while its in-StartGame twin uses a varint — even though
     * EndstoneMC/protocol-docs {@code r26_u4} shows LevelSettings embedding the very same
     * {@code GameRule} type that GameRulesChanged uses. Only the StartGame path is corrected here:
     * the non-StartGame path is already little-endian, and 1.26.30 backends are still on the wire and
     * still decode with the varint form, so the change is version-scoped rather than a blanket fix.</p>
     */
    @Override
    public GameRuleData<?> readGameRuleInStartGame(ByteBuf buffer) {
        checkNotNull(buffer, "buffer");

        String name = readString(buffer);
        boolean editable = buffer.readBoolean();
        int type = VarInts.readUnsignedInt(buffer);

        switch (type) {
            case 1:
                return new GameRuleData<>(name, editable, buffer.readBoolean());
            case 2:
                return new GameRuleData<>(name, editable, buffer.readIntLE());
            case 3:
                return new GameRuleData<>(name, editable, buffer.readFloatLE());
        }
        // Naming the rule matters: a bare "Invalid gamerule type received" says nothing about where
        // in a 39-rule array the decode went off the rails.
        throw new IllegalStateException("Invalid gamerule type " + type + " for rule '" + name + "'");
    }

    @Override
    public void writeGameRuleInStartGame(ByteBuf buffer, GameRuleData<?> gameRule) {
        checkNotNull(buffer, "buffer");
        checkNotNull(gameRule, "gameRule");

        Object value = gameRule.getValue();
        int type = this.gameRuleType.getId(value.getClass());

        writeString(buffer, gameRule.getName());
        buffer.writeBoolean(gameRule.isEditable());
        VarInts.writeUnsignedInt(buffer, type);
        switch (type) {
            case 1:
                buffer.writeBoolean((boolean) value);
                break;
            case 2:
                buffer.writeIntLE((int) value);
                break;
            case 3:
                buffer.writeFloatLE((float) value);
                break;
        }
    }

    @Override
    public void readEntityData(ByteBuf buffer, EntityDataMap entityDataMap) {
        checkNotNull(entityDataMap, "entityDataMap");

        int length = VarInts.readUnsignedInt(buffer);
        checkArgument(this.encodingSettings.maxListSize() <= 0 || length <= this.encodingSettings.maxListSize(), "Entity data size is too big: %s", length);

        for (int i = 0; i < length; i++) {
            int id = VarInts.readUnsignedInt(buffer);
            int oneOf = VarInts.readUnsignedInt(buffer);
            int type = buffer.readUnsignedByte();
            if (oneOf != type) {
                throw new IllegalArgumentException(oneOf + "!=" + type);
            }

            EntityDataFormat format = EntityDataFormat.values()[type];

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

        // Keep this in step with BedrockCodecHelper_v361.writeEntityData — it is the same guard, and
        // this class exists only because 1.26.40 added the `oneOf` discriminator to the wire.
        //
        // Several wire slots are registered to more than one EntityDataType because the meaning of
        // the field depends on the entity: id 2/INT is VARIANT or BLOCK, id 16 is DISPLAY_BLOCK_STATE
        // or DISPLAY_FIREWORK or HORSE_FLAGS or WITHER_SKULL_DANGEROUS. readEntityData cannot tell
        // which was intended, so it populates *every* registered type from the one incoming value.
        // A client or server reads back only the type it cares about and never notices; a proxy
        // re-encodes the whole map, so without this guard one incoming field goes out as two.
        //
        // This override is why that mattered again. The fix lived in v361, and inheriting it was what
        // kept every earlier version correct — then the 1.26.40 port brought in upstream's
        // writeEntityData wholesale, which reverted it for the client-facing codec alone. VARIANT
        // rides on nearly every entity, so the effect was a duplicated metadata field on nearly every
        // AddEntity, AddItemEntity and SetEntityData a 1.26.40 client received. The entry count is a
        // length prefix, so the packet still parses and nothing throws — which is exactly why no
        // existing check caught it.
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
            buffer.writeByte(definition.getFormat().ordinal());

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
    public ItemData readItemInstance(ByteBuf buffer) { // NetworkItemInstanceDescriptorData
        int runtimeId = VarInts.readInt(buffer);

        ItemDefinition definition = runtimeId == 0 ? ItemDefinition.AIR : this.itemDefinitions.getDefinition(runtimeId);
        if (definition == null && log.isDebugEnabled()) {
            log.debug("No ItemDefinition for runtimeId {}, did proxy not set itemDefinitions?", runtimeId);
        }

        int count = buffer.readUnsignedShortLE();
        int aux = VarInts.readUnsignedInt(buffer);

        int blockRuntimeId = VarInts.readInt(buffer);

        NbtMap compoundTag = null;
        long blockingTicks = 0;
        String[] canPlace = new String[0];
        String[] canBreak = new String[0];

        ByteBuf buf = buffer.readSlice(VarInts.readUnsignedInt(buffer));

        if (buf.isReadable()) {
            try (LittleEndianByteBufInputStream stream = new LittleEndianByteBufInputStream(buf);
                 NBTInputStream nbtStream = new NBTInputStream(stream, this.encodingSettings.maxItemNBTSize())) {
                int nbtSize = stream.readShort();

                if (nbtSize > 0) {
                    compoundTag = (NbtMap) nbtStream.readTag();
                } else if (nbtSize == -1) {
                    int tagCount = stream.readUnsignedByte();
                    if (tagCount != 1) throw new IllegalArgumentException("Expected 1 tag but got " + tagCount);
                    compoundTag = (NbtMap) nbtStream.readTag();
                }

                int maxLength = this.encodingSettings.maxListSize();
                int length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can place entries, but maximum is %s", length, maxLength);
                canPlace = new String[length];
                for (int i = 0; i < canPlace.length; i++) {
                    canPlace[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can break entries, but maximum is %s", length, maxLength);
                canBreak = new String[length];
                for (int i = 0; i < canBreak.length; i++) {
                    canBreak[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                if (definition != null && BLOCKING_ID.equals(definition.getIdentifier())) {
                    blockingTicks = stream.readLong();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read item user data", e);
            }
        }

        if (buf.isReadable()) {
            log.info("Item user data has {} readable bytes left", buf.readableBytes());

            if (log.isDebugEnabled()) {
                log.debug("Item data:\n{}", ByteBufUtil.prettyHexDump(buf.readerIndex(0)));
            }
        }

        return ItemData.builder()
                .definition(definition)
                .damage(aux)
                .count(count)
                .tag(compoundTag)
                .canPlace(canPlace)
                .canBreak(canBreak)
                .blockingTicks(blockingTicks)
                .blockDefinition(runtimeId == 0 ? ItemData.AIR.getBlockDefinition() : this.blockDefinitions.getDefinition(blockRuntimeId))
                .build();
    }

    @Override
    public ItemData readNetworkItemStackDescriptor(ByteBuf buffer) { // cerealizer_NetworkItemStackDescriptor___SerializedData
        int runtimeId = buffer.readShortLE();

        ItemDefinition definition = runtimeId == 0 ? ItemDefinition.AIR : this.itemDefinitions.getDefinition(runtimeId);
        if (definition == null && log.isDebugEnabled()) {
            log.debug("No ItemDefinition for runtimeId {}, did proxy not set itemDefinitions?", runtimeId);
        }

        int count = buffer.readUnsignedShortLE();
        int aux = VarInts.readUnsignedInt(buffer);

        int netId = 0;
        boolean hasNetId = buffer.readBoolean();

        if (hasNetId) {
            netId = VarInts.readInt(buffer);
        }

        int blockRuntimeId = VarInts.readUnsignedInt(buffer);

        NbtMap compoundTag = null;
        long blockingTicks = 0;
        String[] canPlace = new String[0];
        String[] canBreak = new String[0];

        ByteBuf buf = buffer.readSlice(VarInts.readUnsignedInt(buffer));

        if (buf.isReadable()) {
            try (LittleEndianByteBufInputStream stream = new LittleEndianByteBufInputStream(buf);
                 NBTInputStream nbtStream = new NBTInputStream(stream, this.encodingSettings.maxItemNBTSize())) {
                int nbtSize = stream.readShort();

                if (nbtSize > 0) {
                    compoundTag = (NbtMap) nbtStream.readTag();
                } else if (nbtSize == -1) {
                    int tagCount = stream.readUnsignedByte();
                    if (tagCount != 1) throw new IllegalArgumentException("Expected 1 tag but got " + tagCount);
                    compoundTag = (NbtMap) nbtStream.readTag();
                }

                int maxLength = this.encodingSettings.maxListSize();
                int length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can place entries, but maximum is %s", length, maxLength);
                canPlace = new String[length];
                for (int i = 0; i < canPlace.length; i++) {
                    canPlace[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can break entries, but maximum is %s", length, maxLength);
                canBreak = new String[length];
                for (int i = 0; i < canBreak.length; i++) {
                    canBreak[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                if (definition != null && BLOCKING_ID.equals(definition.getIdentifier())) {
                    blockingTicks = stream.readLong();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read item user data", e);
            }
        }

        if (buf.isReadable()) {
            log.info("Item user data has {} readable bytes left", buf.readableBytes());

            if (log.isDebugEnabled()) {
                log.debug("Item data:\n{}", ByteBufUtil.prettyHexDump(buf.readerIndex(0)));
            }
        }

        return ItemData.builder()
                .definition(definition)
                .damage(aux)
                .count(count)
                .tag(compoundTag)
                .canPlace(canPlace)
                .canBreak(canBreak)
                .blockingTicks(blockingTicks)
                .blockDefinition(runtimeId == 0 ? ItemData.AIR.getBlockDefinition() : this.blockDefinitions.getDefinition(blockRuntimeId))
                .usingNetId(hasNetId)
                .netId(netId)
                .build();
    }

    @Override
    public void writeItemInstance(ByteBuf buffer, ItemData item) {
        requireNonNull(item, "item is null!");

        ItemDefinition definition = item.getDefinition();
        boolean air = isAir(definition);

        VarInts.writeInt(buffer, air ? 0 : definition.getRuntimeId());
        buffer.writeShortLE(item.getCount());
        VarInts.writeUnsignedInt(buffer, item.getDamage());

        VarInts.writeInt(buffer, air || item.getBlockDefinition() == null ? 0 : item.getBlockDefinition().getRuntimeId());

        if (air) {
            VarInts.writeUnsignedInt(buffer, 0);
        } else {
            ByteBuf userDataBuf = ByteBufAllocator.DEFAULT.ioBuffer();
            try (LittleEndianByteBufOutputStream stream = new LittleEndianByteBufOutputStream(userDataBuf);
                 NBTOutputStream nbtStream = new NBTOutputStream(stream)) {
                if (item.getTag() != null) {
                    stream.writeShort(-1);
                    stream.writeByte(1); // Hardcoded in current version
                    nbtStream.writeTag(item.getTag());
                } else {
                    userDataBuf.writeShortLE(0);
                }

                String[] canPlace = item.getCanPlace();
                stream.writeInt(canPlace.length);
                for (String aCanPlace : canPlace) {
                    stream.writeUTF(aCanPlace);
                }

                String[] canBreak = item.getCanBreak();
                stream.writeInt(canBreak.length);
                for (String aCanBreak : canBreak) {
                    stream.writeUTF(aCanBreak);
                }

                if (BLOCKING_ID.equals(definition.getIdentifier())) {
                    stream.writeLong(item.getBlockingTicks());
                }

                VarInts.writeUnsignedInt(buffer, userDataBuf.readableBytes());
                buffer.writeBytes(userDataBuf);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write item user data", e);
            } finally {
                userDataBuf.release();
            }
        }
    }

    @Override
    public void writeNetworkItemStackDescriptor(ByteBuf buffer, ItemData item) {
        requireNonNull(item, "item is null!");

        ItemDefinition definition = item.getDefinition();
        boolean air = isAir(definition);

        buffer.writeShortLE(air ? 0 : definition.getRuntimeId());
        buffer.writeShortLE(item.getCount());
        VarInts.writeUnsignedInt(buffer, item.getDamage());

        buffer.writeBoolean(item.isUsingNetId());
        if (item.isUsingNetId()) {
            VarInts.writeInt(buffer, item.getNetId());
        }

        VarInts.writeUnsignedInt(buffer, air || item.getBlockDefinition() == null ? 0 : item.getBlockDefinition().getRuntimeId());

        if (air) {
            VarInts.writeUnsignedInt(buffer, 0);
        } else {
            ByteBuf userDataBuf = ByteBufAllocator.DEFAULT.ioBuffer();
            try (LittleEndianByteBufOutputStream stream = new LittleEndianByteBufOutputStream(userDataBuf);
                 NBTOutputStream nbtStream = new NBTOutputStream(stream)) {
                if (item.getTag() != null) {
                    stream.writeShort(-1);
                    stream.writeByte(1); // Hardcoded in current version
                    nbtStream.writeTag(item.getTag());
                } else {
                    userDataBuf.writeShortLE(0);
                }

                String[] canPlace = item.getCanPlace();
                stream.writeInt(canPlace.length);
                for (String aCanPlace : canPlace) {
                    stream.writeUTF(aCanPlace);
                }

                String[] canBreak = item.getCanBreak();
                stream.writeInt(canBreak.length);
                for (String aCanBreak : canBreak) {
                    stream.writeUTF(aCanBreak);
                }

                if (BLOCKING_ID.equals(definition.getIdentifier())) {
                    stream.writeLong(item.getBlockingTicks());
                }

                VarInts.writeUnsignedInt(buffer, userDataBuf.readableBytes());
                buffer.writeBytes(userDataBuf);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write item user data", e);
            } finally {
                userDataBuf.release();
            }
        }
    }

    @Override
    protected void writeRequestActionData(ByteBuf byteBuf, ItemStackRequestAction action) {
        // NOT a repeat of the varint selector in front of it, however much it looks like one. The two
        // fields use different numberings on purpose and both are needed as they stand:
        //
        //   varint selector -> the union's *case index*, which skips PLACE_IN_ITEM_CONTAINER and
        //                      TAKE_FROM_ITEM_CONTAINER (deprecated since v712). That is what
        //                      ITEM_STACK_REQUEST_TYPES models, so writeItemStackRequest is right to
        //                      take it from the TypeMap.
        //   this byte       -> BDS's own enum value, which still counts the deprecated members. That
        //                      is ordinal(), and from LAB_TABLE_COMBINE onward it is the case index
        //                      plus two.
        //
        // Making them agree looks like an obvious cleanup and breaks every ItemStackRequest: BDS
        // validates this byte as the const member it calls "Action type" and rejects the packet at
        // severity TERMINATING_CONNECTION. Verified live in both directions — with ordinal() a
        // malformed auto-craft got as far as `wrong const value for member "Descriptor Type"` deep
        // inside the ingredients, and with the TypeMap id the same packet failed earlier and harder
        // at `"Action type"`, taking PlayerAuthInput (which embeds a request) down with it.
        byteBuf.writeByte(action.getType().ordinal());

        switch (action.getType()) {
            case TAKE:
            case PLACE:
                byteBuf.writeByte(((TransferItemStackRequestAction) action).getCount());
                writeStackRequestSlotInfo(byteBuf, ((TransferItemStackRequestAction) action).getSource());
                writeStackRequestSlotInfo(byteBuf, ((TransferItemStackRequestAction) action).getDestination());
                break;
            case SWAP:
                writeStackRequestSlotInfo(byteBuf, ((SwapAction) action).getSource());
                writeStackRequestSlotInfo(byteBuf, ((SwapAction) action).getDestination());
                break;
            case DROP:
                byteBuf.writeByte(((DropAction) action).getCount());
                writeStackRequestSlotInfo(byteBuf, ((DropAction) action).getSource());
                byteBuf.writeBoolean(((DropAction) action).isRandomly());
                break;
            case DESTROY:
                byteBuf.writeByte(((DestroyAction) action).getCount());
                writeStackRequestSlotInfo(byteBuf, ((DestroyAction) action).getSource());
                break;
            case CONSUME:
                byteBuf.writeByte(((ConsumeAction) action).getCount());
                writeStackRequestSlotInfo(byteBuf, ((ConsumeAction) action).getSource());
                break;
            case CREATE:
                byteBuf.writeByte(((CreateAction) action).getSlot());
                break;
            case LAB_TABLE_COMBINE:
                break;
            case BEACON_PAYMENT:
                VarInts.writeInt(byteBuf, ((BeaconPaymentAction) action).getPrimaryEffect());
                VarInts.writeInt(byteBuf, ((BeaconPaymentAction) action).getSecondaryEffect());
                break;
            case MINE_BLOCK:
                VarInts.writeInt(byteBuf, ((MineBlockAction) action).getHotbarSlot());
                VarInts.writeInt(byteBuf, ((MineBlockAction) action).getPredictedDurability());
                byteBuf.writeIntLE(((MineBlockAction) action).getStackNetworkId()); // int
                break;
            case CRAFT_RECIPE:
                VarInts.writeUnsignedInt(byteBuf, ((RecipeItemStackRequestAction) action).getRecipeNetworkId());
                byteBuf.writeByte(((RecipeItemStackRequestAction) action).getNumberOfRequestedCrafts());
                break;
            case CRAFT_RECIPE_AUTO:
                VarInts.writeUnsignedInt(byteBuf, ((AutoCraftRecipeAction) action).getRecipeNetworkId());
                byteBuf.writeByte(((AutoCraftRecipeAction) action).getNumberOfRequestedCrafts()); // count duplication removed
                // The ingredient array carries exactly one length prefix, the unsigned varint that
                // writeArray emits. Upstream also wrote a byte count in front of it, which the reader
                // opposite never consumes: a decoded auto-craft re-encoded one byte too long. BDS
                // reads that stray byte as the array length (identical encoding below 128, so the
                // count still looks right), then starts the first ingredient on the real length
                // prefix and rejects the packet with `wrong const value for member "Descriptor Type"`
                // at severity TERMINATING_CONNECTION. Every recipe-book craft through the proxy
                // dropped the player. See ItemStackRequestAutoCraftTest.
                writeArray(byteBuf, ((AutoCraftRecipeAction) action).getIngredients(), this::writeIngredient2);
                break;
            case CRAFT_CREATIVE:
                VarInts.writeUnsignedInt(byteBuf, ((CraftCreativeAction) action).getCreativeItemNetworkId());
                byteBuf.writeByte(((CraftCreativeAction) action).getNumberOfRequestedCrafts());
                break;
            case CRAFT_RECIPE_OPTIONAL:
                VarInts.writeUnsignedInt(byteBuf, ((CraftRecipeOptionalAction) action).getRecipeNetworkId());
                byteBuf.writeIntLE(((CraftRecipeOptionalAction) action).getFilteredStringIndex());
                break;
            case CRAFT_REPAIR_AND_DISENCHANT:
                byteBuf.writeIntLE(((CraftGrindstoneAction) action).getRecipeNetworkId()); // int
                byteBuf.writeByte(((CraftGrindstoneAction) action).getNumberOfRequestedCrafts());
                VarInts.writeInt(byteBuf, ((CraftGrindstoneAction) action).getRepairCost());
                break;
            case CRAFT_LOOM:
                this.writeString(byteBuf, ((CraftLoomAction) action).getPatternId());
                byteBuf.writeByte(((CraftLoomAction) action).getTimesCrafted());
                break;
            case CRAFT_NON_IMPLEMENTED_DEPRECATED:
                break;
            case CRAFT_RESULTS_DEPRECATED:
                this.writeArray(byteBuf, ((CraftResultsDeprecatedAction) action).getResultItems(), this::writeItemStackRequestNetworkItemInstanceDescriptor);
                byteBuf.writeByte(((CraftResultsDeprecatedAction) action).getTimesCrafted());
                break;
            default:
                throw new IllegalArgumentException("got " + action.getType());
        }
    }

    @Override
    protected ItemStackRequestAction readRequestActionData(ByteBuf byteBuf, ItemStackRequestActionType type) {
        int type2 = byteBuf.readByte();

        switch (type) {
            case TAKE:
                return new TakeAction(
                        byteBuf.readUnsignedByte(),
                        readStackRequestSlotInfo(byteBuf),
                        readStackRequestSlotInfo(byteBuf)
                );
            case PLACE:
                return new PlaceAction(
                        byteBuf.readUnsignedByte(),
                        readStackRequestSlotInfo(byteBuf),
                        readStackRequestSlotInfo(byteBuf)
                );
            case SWAP:
                return new SwapAction(
                        readStackRequestSlotInfo(byteBuf),
                        readStackRequestSlotInfo(byteBuf)
                );
            case DROP:
                return new DropAction(
                        byteBuf.readUnsignedByte(),
                        readStackRequestSlotInfo(byteBuf),
                        byteBuf.readBoolean()
                );
            case DESTROY:
                return new DestroyAction(
                        byteBuf.readUnsignedByte(),
                        readStackRequestSlotInfo(byteBuf)
                );
            case CONSUME:
                return new ConsumeAction(
                        byteBuf.readUnsignedByte(),
                        readStackRequestSlotInfo(byteBuf)
                );
            case CREATE:
                return new CreateAction(
                        byteBuf.readUnsignedByte()
                );
            case LAB_TABLE_COMBINE:
                return new LabTableCombineAction();
            case BEACON_PAYMENT:
                return new BeaconPaymentAction(
                        VarInts.readInt(byteBuf),
                        VarInts.readInt(byteBuf)
                );
            case MINE_BLOCK:
                return new MineBlockAction(
                        VarInts.readInt(byteBuf), VarInts.readInt(byteBuf), byteBuf.readIntLE() // int
                );
            case CRAFT_RECIPE:
                return new CraftRecipeAction(
                        VarInts.readUnsignedInt(byteBuf), byteBuf.readByte()
                );
            case CRAFT_RECIPE_AUTO:
                int recipeNetworkId = VarInts.readUnsignedInt(byteBuf);
                int numberOfRequestedCrafts = byteBuf.readUnsignedByte(); // count duplication removed
                List<ItemDescriptorWithCount> ingredients = new ObjectArrayList<>();
                this.readArray(byteBuf, ingredients, this::readIngredient2);
                return new AutoCraftRecipeAction(recipeNetworkId, numberOfRequestedCrafts, ingredients, numberOfRequestedCrafts);
            case CRAFT_CREATIVE:
                return new CraftCreativeAction(
                        VarInts.readUnsignedInt(byteBuf), byteBuf.readByte()
                );
            case CRAFT_RECIPE_OPTIONAL:
                return new CraftRecipeOptionalAction(
                        VarInts.readUnsignedInt(byteBuf), byteBuf.readIntLE()
                );
            case CRAFT_REPAIR_AND_DISENCHANT:
                return new CraftGrindstoneAction(
                        byteBuf.readIntLE(), byteBuf.readByte(), VarInts.readInt(byteBuf) // int
                );
            case CRAFT_LOOM:
                return new CraftLoomAction(
                        this.readString(byteBuf), byteBuf.readUnsignedByte()
                );
            case CRAFT_NON_IMPLEMENTED_DEPRECATED:
                return new CraftNonImplementedAction();
            case CRAFT_RESULTS_DEPRECATED:
                return new CraftResultsDeprecatedAction(
                        this.readArray(byteBuf, new ItemData[0], this::readItemStackRequestNetworkItemInstanceDescriptor),
                        byteBuf.readUnsignedByte()
                );
            default:
                throw new IllegalArgumentException("got " + type);
        }
    }

    @Override
    protected ItemStackRequestSlotData readStackRequestSlotInfo(ByteBuf buffer) {
        FullContainerName containerName = this.readFullContainerName(buffer);
        return new ItemStackRequestSlotData(
                containerName.getContainer(),
                buffer.readUnsignedByte(),
                buffer.readIntLE(),
                containerName
        );
    }

    @Override
    protected void writeStackRequestSlotInfo(ByteBuf buffer, ItemStackRequestSlotData data) {
        this.writeFullContainerName(buffer, data.getContainerName());
        buffer.writeByte(data.getSlot());
        buffer.writeIntLE(data.getStackNetworkId());
    }

    @Override
    public void writeItem(ByteBuf buffer, ItemData item) {
        writeNetworkItemStackDescriptor(buffer, item);
    }

    @Override
    public ItemData readItem(ByteBuf buffer) {
        return readNetworkItemStackDescriptor(buffer);
    }

    @Override
    public SerializedSkin readSkin(ByteBuf buffer) {
        String skinId = this.readString(buffer);
        String playFabId = this.readString(buffer);
        String skinResourcePatch = this.readString(buffer);
        ImageData skinData = this.readImage(buffer, ImageData.SKIN_PERSONA_SIZE);

        List<AnimationData> animations = new ObjectArrayList<>();
        this.readArray(buffer, animations, (b, h) -> this.readAnimationData(b));

        ImageData capeData = this.readImage(buffer, ImageData.SINGLE_SKIN_SIZE);
        String geometryData = this.readStringMaxLen(buffer, this.encodingSettings.maxGeometryDataSize());
        String geometryDataEngineVersion = this.readString(buffer);
        String animationData = this.readString(buffer);
        String capeId = this.readString(buffer);
        String fullSkinId = this.readString(buffer);

        String armSize = buffer.readUnsignedByte() == 1 ? "wide" : "slim";
        Color color = new Color(buffer.readIntLE(), true);

        List<PersonaPieceData> personaPieces = new ObjectArrayList<>();
        this.readArray(buffer, personaPieces, (buf, h) -> {
            String pieceId = this.readString(buf);
            PersonaPieceType pieceType = PersonaPieceType.values()[buf.readIntLE()];
            UUID packId = this.readUuid(buf);
            boolean isDefault = buf.readBoolean();
            String productId = this.readString(buf);
            return new PersonaPieceData(pieceId, pieceType, packId, isDefault, productId);
        });

        List<PersonaPieceTintData> tintColors = new ObjectArrayList<>();
        this.readArray(buffer, tintColors, (buf, h) -> {
            PersonaPieceType pieceType = PersonaPieceType.fromName(this.readString(buf));
            List<Color> colors = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                colors.add(new Color(buf.readIntLE(), true));
            }
            return new PersonaPieceTintData(pieceType, colors);
        });

        boolean premium = buffer.readBoolean();
        boolean persona = buffer.readBoolean();
        boolean capeOnClassic = buffer.readBoolean();
        boolean primaryUser = buffer.readBoolean();
        boolean overridingPlayerAppearance = buffer.readBoolean();

        boolean trusted = "true".equalsIgnoreCase(this.readString(buffer));
        String profileHash = this.readString(buffer);

        return SerializedSkin.of(skinId, playFabId, skinResourcePatch, skinData, animations, capeData, geometryData, geometryDataEngineVersion,
                animationData, premium, persona, capeOnClassic, primaryUser, capeId, fullSkinId, armSize, color, personaPieces, tintColors,
                overridingPlayerAppearance, trusted, profileHash);
    }

    @Override
    public void writeSkin(ByteBuf buffer, SerializedSkin skin) {
        requireNonNull(skin, "Skin is null");

        this.writeString(buffer, skin.getSkinId());
        this.writeString(buffer, skin.getPlayFabId());
        this.writeString(buffer, skin.getSkinResourcePatch());
        this.writeImage(buffer, skin.getSkinData());

        List<AnimationData> animations = skin.getAnimations();
        VarInts.writeUnsignedInt(buffer, animations.size());
        for (AnimationData animation : animations) {
            this.writeAnimationData(buffer, animation);
        }

        this.writeImage(buffer, skin.getCapeData());
        this.writeString(buffer, skin.getGeometryData());
        this.writeString(buffer, skin.getGeometryDataEngineVersion());
        this.writeString(buffer, skin.getAnimationData());
        this.writeString(buffer, skin.getCapeId());
        this.writeString(buffer, skin.getFullSkinId());

        buffer.writeByte("slim".equalsIgnoreCase(skin.getArmSize()) ? 0 : 1);
        buffer.writeIntLE(skin.getColor().getRGB());

        List<PersonaPieceData> pieces = skin.getPersonaPieces();
        VarInts.writeUnsignedInt(buffer, pieces.size());
        for (PersonaPieceData piece : pieces) {
            this.writeString(buffer, piece.getId());
            buffer.writeIntLE(piece.getPieceType().ordinal());
            this.writeUuid(buffer, piece.getPackUuid());
            buffer.writeBoolean(piece.isDefault());
            this.writeString(buffer, piece.getProductId());
        }

        List<PersonaPieceTintData> tints = skin.getTintColors();
        VarInts.writeUnsignedInt(buffer, tints.size());
        for (PersonaPieceTintData tint : tints) {
            this.writeString(buffer, tint.getType());
            List<Color> colors = tint.getColorsNew();
            if (colors.size() != 4) {
                throw new IllegalArgumentException("Expected 4 colors in PersonaPieceTintData");
            }
            for (Color color : colors) {
                buffer.writeIntLE(color.getRGB());
            }
        }

        buffer.writeBoolean(skin.isPremium());
        buffer.writeBoolean(skin.isPersona());
        buffer.writeBoolean(skin.isCapeOnClassic());
        buffer.writeBoolean(skin.isPrimaryUser());

        buffer.writeBoolean(skin.isOverridingPlayerAppearance());

        this.writeString(buffer, Boolean.toString(skin.isTrusted()));
        this.writeString(buffer, skin.getProfileHash());
    }

    @Override
    public AnimationData readAnimationData(ByteBuf buffer) {
        ImageData image = this.readImage(buffer, ImageData.ANIMATION_SIZE);
        AnimatedTextureType textureType = TEXTURE_TYPES[VarInts.readUnsignedInt(buffer)];
        float frames = buffer.readFloatLE();
        AnimationExpressionType expressionType = EXPRESSION_TYPES[VarInts.readUnsignedInt(buffer)];
        return new AnimationData(image, textureType, frames, expressionType);
    }

    @Override
    public void writeAnimationData(ByteBuf buffer, AnimationData animation) {
        this.writeImage(buffer, animation.getImage());
        VarInts.writeUnsignedInt(buffer, animation.getTextureType().ordinal());
        buffer.writeFloatLE(animation.getFrames());
        VarInts.writeUnsignedInt(buffer, animation.getExpressionType().ordinal());
    }

    @Override
    public ItemStackRequest readItemStackRequest(ByteBuf buffer) {
        int requestId = VarInts.readInt(buffer);
        List<ItemStackRequestAction> actions = new ObjectArrayList<>();

        this.readArray(buffer, actions, byteBuf -> {
            ItemStackRequestActionType type = this.stackRequestActionTypes.getType(VarInts.readUnsignedInt(byteBuf));
            return readRequestActionData(byteBuf, type);
        }, this.getEncodingSettings().maxInventoryActionsOrRequests());

        List<String> filteredStrings = new ObjectArrayList<>();
        this.readArray(buffer, filteredStrings, this::readString);

        int originVal = buffer.readIntLE();
        TextProcessingEventOrigin origin = originVal == -1 ? null : this.textProcessingEventOrigins.getType(originVal);
        return new ItemStackRequest(requestId, actions.toArray(new ItemStackRequestAction[0]), filteredStrings.toArray(new String[0]), origin);
    }

    @Override
    public void writeItemStackRequest(ByteBuf buffer, ItemStackRequest request) {
        VarInts.writeInt(buffer, request.getRequestId());

        this.writeArray(buffer, request.getActions(), (byteBuf, action) -> {
            VarInts.writeUnsignedInt(byteBuf, this.stackRequestActionTypes.getId(action.getType()));
            writeRequestActionData(byteBuf, action);
        });

        this.writeArray(buffer, request.getFilterStrings(), this::writeString);

        TextProcessingEventOrigin origin = request.getTextProcessingEventOrigin();
        buffer.writeIntLE(origin == null ? -1 : this.textProcessingEventOrigins.getId(origin));
    }

    @Override
    public ItemDescriptorWithCount readIngredient(ByteBuf buffer) {
        ItemDescriptorType type = DESCRIPTOR_TYPES[VarInts.readUnsignedInt(buffer)];
        ItemDescriptor descriptor = this.readItemDescriptor(buffer, type);
        int count = VarInts.readInt(buffer);
        return new ItemDescriptorWithCount(descriptor, count);
    }

    protected ItemDescriptorWithCount readIngredient2(ByteBuf buffer) {
        ItemDescriptorType type = DESCRIPTOR_TYPES[VarInts.readUnsignedInt(buffer)];

        int type2 = buffer.readUnsignedByte();
        //type = DESCRIPTOR_TYPES[type2];

        ItemDescriptor descriptor;
        switch (type) {
            case INVALID:
                descriptor = InvalidDescriptor.INSTANCE;
                break;
            case DEFAULT:
                String id = this.readString(buffer);
                int aux = VarInts.readInt(buffer);
                ItemDefinition definition = this.itemDefinitions.getDefinition(id);
                if (definition == null && log.isDebugEnabled()) {
                    log.debug("No ItemDefinition for id {}, did proxy not set itemDefinitions?", id);
                }
                descriptor = new DefaultDescriptor(definition, aux);
                break;
            case MOLANG:
                descriptor = new MolangDescriptor(this.readString(buffer), buffer.readShortLE());
                break;
            case ITEM_TAG:
                descriptor = new ItemTagDescriptor(this.readString(buffer));
                break;
            default:
                throw new UnsupportedOperationException("ItemDescriptorType");
        }

        int count = buffer.readUnsignedShortLE();
        return new ItemDescriptorWithCount(descriptor, count);
    }

    @Override
    public void writeIngredient(ByteBuf buffer, ItemDescriptorWithCount ingredient) {
        VarInts.writeUnsignedInt(buffer, Math.min(ingredient.getDescriptor().getType().ordinal(), 1));
        this.writeItemDescriptor(buffer, ingredient.getDescriptor());
        VarInts.writeInt(buffer, ingredient.getCount());
    }

    protected void writeIngredient2(ByteBuf buffer, ItemDescriptorWithCount ingredient) {
        VarInts.writeUnsignedInt(buffer, ingredient.getDescriptor().getType().ordinal());

        buffer.writeByte(ingredient.getDescriptor().getType().ordinal());

        switch (ingredient.getDescriptor().getType()) {
            case INVALID:
                break;
            case DEFAULT:
                DefaultDescriptor defaultDescriptor = (DefaultDescriptor) ingredient.getDescriptor();
                this.writeString(buffer, defaultDescriptor.getItemId().getIdentifier());
                VarInts.writeInt(buffer, defaultDescriptor.getAuxValue());
                break;
            case MOLANG:
                MolangDescriptor molangDescriptor = (MolangDescriptor) ingredient.getDescriptor();
                this.writeString(buffer, molangDescriptor.getTagExpression());
                buffer.writeShortLE(molangDescriptor.getMolangVersion());
                break;
            case ITEM_TAG:
                ItemTagDescriptor tagDescriptor = (ItemTagDescriptor) ingredient.getDescriptor();
                this.writeString(buffer, tagDescriptor.getItemTag());
                break;
            default:
                throw new UnsupportedOperationException("ItemDescriptorType");
        }

        buffer.writeShortLE(ingredient.getCount());
    }

    @Override
    protected ItemDescriptor readItemDescriptor(ByteBuf buffer, ItemDescriptorType type) {
        ItemDescriptor descriptor;
        if (type != ItemDescriptorType.INVALID) {
            String desc = this.readString(buffer);
            type = ItemDescriptorType.fromName(desc);
        }

        switch (type) {
            case INVALID:
                int aux_ = VarInts.readInt(buffer);
                descriptor = InvalidDescriptor.INSTANCE;
                break;
            case DEFAULT:
                String id = this.readString(buffer);
                int aux = VarInts.readInt(buffer);
                ItemDefinition definition = this.itemDefinitions.getDefinition(id);
                if (definition == null && log.isDebugEnabled()) {
                    log.debug("No ItemDefinition for id {}, did proxy not set itemDefinitions?", id);
                }
                descriptor = new DefaultDescriptor(definition, aux);
                break;
            case MOLANG:
                descriptor = new MolangDescriptor(this.readString(buffer), buffer.readShortLE());
                break;
            case ITEM_TAG:
                descriptor = new ItemTagDescriptor(this.readString(buffer));
                int aux__ = VarInts.readInt(buffer);
                break;
            default:
                throw new UnsupportedOperationException();
        }

        return descriptor;
    }

    @Override
    protected void writeItemDescriptor(ByteBuf buffer, ItemDescriptor descriptor) {
        if (descriptor.getType() != ItemDescriptorType.INVALID) {
            this.writeString(buffer, descriptor.getType().getSerializeName());
        }

        switch (descriptor.getType()) {
            case INVALID:
                VarInts.writeInt(buffer, 32767);
                break;
            case DEFAULT:
                DefaultDescriptor defaultDescriptor = (DefaultDescriptor) descriptor;
                this.writeString(buffer, defaultDescriptor.getItemId().getIdentifier());
                VarInts.writeInt(buffer, defaultDescriptor.getAuxValue());
                break;
            case MOLANG:
                MolangDescriptor molangDescriptor = (MolangDescriptor) descriptor;
                this.writeString(buffer, molangDescriptor.getTagExpression());
                buffer.writeShortLE(molangDescriptor.getMolangVersion());
                break;
            case ITEM_TAG:
                ItemTagDescriptor tagDescriptor = (ItemTagDescriptor) descriptor;
                this.writeString(buffer, tagDescriptor.getItemTag());
                VarInts.writeInt(buffer, 32767);
                break;
            default:
                throw new UnsupportedOperationException();
        }
    }

    @Override
    public StructureSettings readStructureSettings(ByteBuf buffer) {
        String paletteName = this.readString(buffer);
        boolean ignoringEntities = buffer.readBoolean();
        boolean ignoringBlocks = buffer.readBoolean();
        boolean nonTickingPlayersAndTickingAreasEnabled = buffer.readBoolean();
        Vector3i size = this.readBlockPosition(buffer);
        Vector3i offset = this.readBlockPosition(buffer);
        long lastEditedByEntityId = VarInts.readLong(buffer);
        StructureRotation rotation = StructureRotation.from(buffer.readUnsignedByte());
        StructureMirror mirror = StructureMirror.from(buffer.readUnsignedByte());
        StructureAnimationMode animationMode = StructureAnimationMode.from(buffer.readUnsignedByte());
        float animationSeconds = buffer.readFloatLE();
        float integrityValue = buffer.readFloatLE();
        int integritySeed = buffer.readIntLE();
        Vector3f pivot = this.readVector3f(buffer);

        return new StructureSettings(paletteName, ignoringEntities, ignoringBlocks,
                nonTickingPlayersAndTickingAreasEnabled, size, offset, lastEditedByEntityId, rotation, mirror,
                animationMode, animationSeconds, integrityValue, integritySeed, pivot);
    }

    @Override
    protected ItemStackResponseSlot readItemEntry(ByteBuf buffer) {
        int slot = buffer.readUnsignedByte();
        int hotbarSlot = buffer.readUnsignedByte();
        int count = buffer.readUnsignedByte();
        int stackNetworkId = buffer.readBoolean() && buffer.readBoolean() ? VarInts.readInt(buffer) : 0;
        String customName = this.readString(buffer);
        String filteredCustomName = this.readString(buffer);
        int durabilityCorrection = VarInts.readInt(buffer);
        return new ItemStackResponseSlot(slot, hotbarSlot, count, stackNetworkId,
                customName, durabilityCorrection, filteredCustomName);

    }

    @Override
    protected void writeItemEntry(ByteBuf buffer, ItemStackResponseSlot itemEntry) {
        buffer.writeByte(itemEntry.getSlot());
        buffer.writeByte(itemEntry.getHotbarSlot());
        buffer.writeByte(itemEntry.getCount());
        buffer.writeBoolean(true);
        this.writeOptional(buffer, id->id > 0, itemEntry.getStackNetworkId(), VarInts::writeInt);
        this.writeString(buffer, itemEntry.getCustomName());
        this.writeString(buffer, itemEntry.getFilteredCustomName());
        VarInts.writeInt(buffer, itemEntry.getDurabilityCorrection());
    }

    private ItemData readItemStackRequestNetworkItemInstanceDescriptor(ByteBuf buffer) {
        ItemDescriptorType type = DESCRIPTOR_TYPES[VarInts.readUnsignedInt(buffer)];

        int typeStr = buffer.readUnsignedByte();

        ItemDescriptor descriptor = InvalidDescriptor.INSTANCE;
        if (type != ItemDescriptorType.INVALID) {
            String id = this.readString(buffer);

            int aux = VarInts.readInt(buffer);
            ItemDefinition definition = this.itemDefinitions.getDefinition(id);
            if (definition == null &&log.isDebugEnabled()) {
                log.debug("No ItemDefinition for id {}, did proxy not set itemDefinitions?", id);
            }
            descriptor = new DefaultDescriptor(definition, aux);
        }

        ItemDefinition definition = descriptor == InvalidDescriptor.INSTANCE ? ItemData.AIR.getDefinition() : ((DefaultDescriptor) descriptor).getItemId();
        int aux = descriptor == InvalidDescriptor.INSTANCE ? 0 : ((DefaultDescriptor) descriptor).getAuxValue();

        int count = buffer.readShortLE();

        int blockRuntimeId = VarInts.readUnsignedInt(buffer);

        NbtMap compoundTag = null;
        long blockingTicks = 0;
        String[] canPlace = new String[0];
        String[] canBreak = new String[0];

        ByteBuf buf = buffer.readSlice(VarInts.readUnsignedInt(buffer));

        if (buf.isReadable()) {
            try (LittleEndianByteBufInputStream stream = new LittleEndianByteBufInputStream(buf);
                 NBTInputStream nbtStream = new NBTInputStream(stream, this.encodingSettings.maxItemNBTSize())) {
                int nbtSize = stream.readShort();

                if (nbtSize > 0) {
                    compoundTag = (NbtMap) nbtStream.readTag();
                } else if (nbtSize == -1) {
                    int tagCount = stream.readUnsignedByte();
                    if (tagCount != 1) throw new IllegalArgumentException("Expected 1 tag but got " + tagCount);
                    compoundTag = (NbtMap) nbtStream.readTag();
                }

                int maxLength = this.encodingSettings.maxListSize();
                int length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can place entries, but maximum is %s", length, maxLength);
                canPlace = new String[length];
                for (int i = 0; i < canPlace.length; i++) {
                    canPlace[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                length = stream.readInt();
                checkArgument(maxLength <= 0 || length <= maxLength, "Tried to read %s can break entries, but maximum is %s", length, maxLength);
                canBreak = new String[length];
                for (int i = 0; i < canBreak.length; i++) {
                    canBreak[i] = stream.readUTFMaxLen(this.encodingSettings.maxItemStackTagLength());
                }

                if (definition != null && BLOCKING_ID.equals(definition.getIdentifier())) {
                    blockingTicks = stream.readLong();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read item user data", e);
            }
        }

        if (buf.isReadable()) {
            log.info("Item user data has {} readable bytes left", buf.readableBytes());

            if (log.isDebugEnabled()) {
                log.debug("Item data:\n{}", ByteBufUtil.prettyHexDump(buf.readerIndex(0)));
            }
        }

        return ItemData.builder()
                .definition(definition)
                .damage(aux)
                .count(count)
                .tag(compoundTag)
                .canPlace(canPlace)
                .canBreak(canBreak)
                .blockingTicks(blockingTicks)
                .blockDefinition(definition.getRuntimeId() == 0 ? ItemData.AIR.getBlockDefinition() : this.blockDefinitions.getDefinition(blockRuntimeId))
                .build();
    }

    private void writeItemStackRequestNetworkItemInstanceDescriptor(ByteBuf buffer, ItemData item) {
        requireNonNull(item, "item is null!");

        ItemDefinition definition = item.getDefinition();
        boolean air = isAir(definition);

        VarInts.writeUnsignedInt(buffer, air ? 0 : 1); //descriptor type
        buffer.writeByte(air ? 0 : 1); // type again
        if (!air) {
            this.writeString(buffer, definition.getIdentifier());
            VarInts.writeInt(buffer, item.getDamage());
        }

        buffer.writeShortLE(item.getCount());

        VarInts.writeUnsignedInt(buffer, air || item.getBlockDefinition() == null ? 0 : item.getBlockDefinition().getRuntimeId());

        if (air) {
            VarInts.writeUnsignedInt(buffer, 0);
        } else {
            ByteBuf userDataBuf = ByteBufAllocator.DEFAULT.ioBuffer();
            try (LittleEndianByteBufOutputStream stream = new LittleEndianByteBufOutputStream(userDataBuf);
                 NBTOutputStream nbtStream = new NBTOutputStream(stream)) {
                if (item.getTag() != null) {
                    stream.writeShort(-1);
                    stream.writeByte(1); // Hardcoded in current version
                    nbtStream.writeTag(item.getTag());
                } else {
                    userDataBuf.writeShortLE(0);
                }

                String[] canPlace = item.getCanPlace();
                stream.writeInt(canPlace.length);
                for (String aCanPlace : canPlace) {
                    stream.writeUTF(aCanPlace);
                }

                String[] canBreak = item.getCanBreak();
                stream.writeInt(canBreak.length);
                for (String aCanBreak : canBreak) {
                    stream.writeUTF(aCanBreak);
                }

                if (BLOCKING_ID.equals(definition.getIdentifier())) {
                    stream.writeLong(item.getBlockingTicks());
                }

                VarInts.writeUnsignedInt(buffer, userDataBuf.readableBytes());
                buffer.writeBytes(userDataBuf);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write item user data", e);
            } finally {
                userDataBuf.release();
            }
        }
    }

    @Override
    public void writeGatheringsConfiguration(ByteBuf buf, BedrockCodecHelper h, GatheringsConfigurationJoinInfo info) {
        h.writeUuid(buf, info.getExperienceId());
        h.writeString(buf, info.getExperienceName());
        h.writeOptionalNull(buf, info.getWorldId(), h::writeUuid);
        h.writeOptionalNull(buf, info.getWorldName(), h::writeString);
        h.writeString(buf, info.getCreatorId());
        h.writeOptionalNull(buf, info.getTargetId(), h::writeUuid);
        h.writeOptionalNull(buf, info.getScenarioId(), h::writeString);
        h.writeOptionalNull(buf, info.getServerId(), h::writeString);
    }

    @Override
    public GatheringsConfigurationJoinInfo readGatheringsConfiguration(ByteBuf buf, BedrockCodecHelper h) {
        return new GatheringsConfigurationJoinInfo(
                h.readUuid(buf),
                h.readString(buf),
                h.readOptional(buf, null, h::readUuid),
                h.readOptional(buf, null, h::readString),
                h.readString(buf),
                h.readOptional(buf, null, h::readUuid),
                h.readOptional(buf, null, h::readString),
                h.readOptional(buf, null, h::readString)
        );
    }

    @Override
    public InventorySource readSource(ByteBuf buffer) {
        InventorySource.Type type = InventorySource.Type.byId(VarInts.readUnsignedInt(buffer));

        int containerId = 0;
        InventorySource.Flag flag = null;
        if (buffer.readBoolean() && buffer.readBoolean()) containerId = buffer.readByte();
        if (buffer.readBoolean() && buffer.readBoolean()) flag = InventorySource.Flag.values()[VarInts.readUnsignedInt(buffer)];
        switch (type) {
            case CONTAINER:
                return InventorySource.fromContainerWindowId(containerId);
            case GLOBAL:
                return InventorySource.fromGlobalInventory();
            case WORLD_INTERACTION:
                if (flag == null) throw new IllegalStateException();
                return InventorySource.fromWorldInteraction(flag);
            case CREATIVE:
                return InventorySource.fromCreativeInventory();
            case NON_IMPLEMENTED_TODO:
                return InventorySource.fromNonImplementedTodo(containerId);
            case UNTRACKED_INTERACTION_UI:
                return InventorySource.fromUntrackedInteractionUI(containerId);
            default:
                return InventorySource.fromInvalid();
        }
    }

    @Override
    public void writeSource(ByteBuf buffer, InventorySource inventorySource) {
        requireNonNull(inventorySource, "InventorySource was null");

        VarInts.writeUnsignedInt(buffer, inventorySource.getType().id());

        buffer.writeBoolean(true);
        switch (inventorySource.getType()) {
            case CONTAINER:
            case NON_IMPLEMENTED_TODO:
                buffer.writeBoolean(true);
                buffer.writeByte(inventorySource.getContainerId());
                break;
            default:
                buffer.writeBoolean(false);
                break;
        }

        buffer.writeBoolean(true);
        switch (inventorySource.getType()) {
            case WORLD_INTERACTION:
                buffer.writeBoolean(true);
                VarInts.writeUnsignedInt(buffer, inventorySource.getFlag().ordinal());
                break;
            default:
                buffer.writeBoolean(false);
                break;
        }
    }
}
