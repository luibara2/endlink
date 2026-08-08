package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v544.serializer.ClientboundMapItemDataSerializer_v544;
import org.cloudburstmc.protocol.bedrock.data.MapDecoration;
import org.cloudburstmc.protocol.bedrock.data.MapTrackedObject;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundMapItemDataPacket;
import org.cloudburstmc.protocol.common.util.Preconditions;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.List;

public class ClientboundMapItemDataSerializer_v2168 extends ClientboundMapItemDataSerializer_v544 {

    public static final ClientboundMapItemDataSerializer_v2168 INSTANCE = new ClientboundMapItemDataSerializer_v2168();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ClientboundMapItemDataPacket packet) {
        VarInts.writeLong(buffer, packet.getUniqueMapId());
        buffer.writeByte(packet.getDimensionId());
        buffer.writeBoolean(packet.isLocked());
        helper.writeVector3i(buffer, packet.getOrigin());

        helper.writeOptionalNull(buffer, packet.getTrackedEntityIds(), (buf, trackedEntityIds) -> {
            VarInts.writeUnsignedInt(buf, trackedEntityIds.size());
            for (long trackedEntityId : trackedEntityIds) {
                VarInts.writeLong(buf, trackedEntityId);
            }
        });

        helper.writeOptionalNull(buffer, packet.getScale(), (buf,b)-> buf.writeByte(b));

        helper.writeOptionalNull(buffer, packet.getTrackedObjects(), (buf, trackedObjects) -> {
            VarInts.writeUnsignedInt(buf, trackedObjects.size());
            for (MapTrackedObject object : trackedObjects) {
                buf.writeIntLE(object.getType().ordinal());
                switch (object.getType()) {
                    case ENTITY:
                        buf.writeBoolean(true);
                        VarInts.writeLong(buf, object.getEntityId());
                        buf.writeBoolean(false);
                        break;
                    case BLOCK:
                        buf.writeBoolean(false);
                        buf.writeBoolean(true);
                        helper.writeBlockPosition(buf, object.getPosition());
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }
        });

        helper.writeOptionalNull(buffer, packet.getDecorations(), (buf, decorations) -> {
            VarInts.writeUnsignedInt(buf, decorations.size());
            for (MapDecoration decoration : decorations) {
                buf.writeByte(decoration.getImage());
                buf.writeByte(decoration.getRotation());
                buf.writeByte(decoration.getXOffset());
                buf.writeByte(decoration.getYOffset());
                helper.writeString(buf, decoration.getLabel());
                buf.writeIntLE(decoration.getColor());
            }
        });

        helper.writeOptionalNull(buffer, packet.getWidth(), VarInts::writeInt);
        helper.writeOptionalNull(buffer, packet.getHeight(), VarInts::writeInt);
        helper.writeOptionalNull(buffer, packet.getXOffset(), VarInts::writeInt);
        helper.writeOptionalNull(buffer, packet.getYOffset(), VarInts::writeInt);

        helper.writeOptionalNull(buffer, packet.getColors(), (buf, colors) -> {
            VarInts.writeUnsignedInt(buf, colors.length);
            for (int color : colors) {
                buf.writeIntLE(color);
            }
        });
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ClientboundMapItemDataPacket packet) {
        packet.setUniqueMapId(VarInts.readLong(buffer));
        packet.setDimensionId(buffer.readUnsignedByte());
        packet.setLocked(buffer.readBoolean());
        packet.setOrigin(helper.readVector3i(buffer));

        if (buffer.readBoolean()) {
            LongList trackedEntityIds = new LongArrayList();
            int length = VarInts.readUnsignedInt(buffer);
            for (int i = 0; i < length; i++) {
                trackedEntityIds.add(VarInts.readLong(buffer));
            }
            packet.setTrackedEntityIds(trackedEntityIds);
        }

        packet.setScale(helper.readOptional(buffer, null, ByteBuf::readByte));

        if (buffer.readBoolean()) {
            List<MapTrackedObject> trackedObjects = new ArrayList<>();
            int length = VarInts.readUnsignedInt(buffer);
            for (int i = 0; i < length; i++) {
                MapTrackedObject.Type objectType = MapTrackedObject.Type.values()[buffer.readIntLE()];
                if (buffer.readBoolean()) {
                    trackedObjects.add(new MapTrackedObject(VarInts.readLong(buffer)));
                }
                if (buffer.readBoolean()) {
                    trackedObjects.add(new MapTrackedObject(helper.readBlockPosition(buffer)));
                }
            }
            packet.setTrackedObjects(trackedObjects);
        }

        if (buffer.readBoolean()) {
            List<MapDecoration> decorations = new ArrayList<>();
            int length = VarInts.readUnsignedInt(buffer);
            for (int i = 0; i < length; i++) {
                int image = buffer.readByte();
                int rotation = buffer.readUnsignedByte();
                int xOffset = buffer.readUnsignedByte();
                int yOffset = buffer.readUnsignedByte();
                String label = helper.readString(buffer);
                int color = buffer.readIntLE();
                decorations.add(new MapDecoration(image, rotation, xOffset, yOffset, label, color));
            }
            packet.setDecorations(decorations);
        }

        packet.setWidth(helper.readOptional(buffer, null, VarInts::readInt));
        packet.setHeight(helper.readOptional(buffer, null, VarInts::readInt));
        packet.setXOffset(helper.readOptional(buffer, null, VarInts::readInt));
        packet.setYOffset(helper.readOptional(buffer, null, VarInts::readInt));

        if (buffer.readBoolean()) {
            int length = VarInts.readUnsignedInt(buffer);
            Preconditions.checkArgument(buffer.isReadable(length), "Not enough readable bytes");
            int[] colors = new int[length];
            for (int i = 0; i < length; i++) {
                colors[i] = buffer.readIntLE();
            }
            packet.setColors(colors);
        }
    }
}
