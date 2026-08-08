package org.cloudburstmc.protocol.bedrock.codec.v291.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.common.util.TriConsumer;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.EnumMap;
import java.util.Set;

public class MoveEntityDeltaSerializer_v291 implements BedrockPacketSerializer<MoveEntityDeltaPacket> {
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_DELTA_X =
            (buffer, helper, packet) -> packet.setDeltaX(VarInts.readInt(buffer));
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_DELTA_Y =
            (buffer, helper, packet) -> packet.setDeltaY(VarInts.readInt(buffer));
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_DELTA_Z =
            (buffer, helper, packet) -> packet.setDeltaZ(VarInts.readInt(buffer));

    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_PITCH =
            (buffer, helper, packet) -> packet.setPitch(helper.readByteAngle(buffer));
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_YAW =
            (buffer, helper, packet) -> packet.setYaw(helper.readByteAngle(buffer));
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> READER_HEAD_YAW =
            (buffer, helper, packet) -> packet.setHeadYaw(helper.readByteAngle(buffer));

    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_DELTA_X =
            (buffer, helper, packet) -> VarInts.writeInt(buffer, packet.getDeltaX());
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_DELTA_Y =
            (buffer, helper, packet) -> VarInts.writeInt(buffer, packet.getDeltaY());
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_DELTA_Z =
            (buffer, helper, packet) -> VarInts.writeInt(buffer, packet.getDeltaZ());

    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_PITCH =
            (buffer, helper, packet) -> helper.writeByteAngle(buffer, packet.getPitch());
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_YAW =
            (buffer, helper, packet) -> helper.writeByteAngle(buffer, packet.getYaw());
    protected static final TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> WRITER_HEAD_YAW =
            (buffer, helper, packet) -> helper.writeByteAngle(buffer, packet.getHeadYaw());

    /**
     * The flag bits that exist on the pre-1.26.40 wire — <b>not</b> {@code Flag.values()}.
     *
     * <p>The bitfield is preset to {@code 0xFFFF} and bits are <em>cleared</em> for flags that are not
     * set (see {@code MoveEntityDeltaSerializer_v388}), which means any bit the sending version does
     * not know about arrives as a 1. Vanilla defines nine flags here, ending at {@code FORCE_MOVE};
     * {@code FORCE_COMPLETION} at ordinal 9 was added for 1.26.40 and has no bit on this wire.
     *
     * <p>Iterating {@code Flag.values()} therefore made the reader add {@code FORCE_COMPLETION} to
     * <em>every</em> packet a 1.26.30 backend sent, because bit 9 is always 1 there — and
     * {@code MoveEntityDeltaSerializer_v2168} turns that flag into a {@code forceCompletion=true}
     * boolean for the client. Every entity move, roughly 90 a second, carried a force-completion the
     * server never asked for.
     *
     * <p>It hid because it round-trips: the reader adds the flag, the writer sees it present and so
     * does not clear bit 9, and the bytes match. Re-encode verification is blind to it by
     * construction, and it only becomes visible when the flag is re-encoded by a version that gives
     * bit 9 a meaning.
     *
     * <p>Truncating here fixes both halves at once — the reader stops inventing the flag, and the
     * writer stops clearing a bit vanilla leaves set, so the legacy encoding stays byte-identical.
     */
    protected static final MoveEntityDeltaPacket.Flag[] FLAGS = {
            MoveEntityDeltaPacket.Flag.HAS_X,
            MoveEntityDeltaPacket.Flag.HAS_Y,
            MoveEntityDeltaPacket.Flag.HAS_Z,
            MoveEntityDeltaPacket.Flag.HAS_PITCH,
            MoveEntityDeltaPacket.Flag.HAS_YAW,
            MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW,
            MoveEntityDeltaPacket.Flag.ON_GROUND,
            MoveEntityDeltaPacket.Flag.TELEPORTING,
            MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY
    };

    public static final MoveEntityDeltaSerializer_v291 INSTANCE = new MoveEntityDeltaSerializer_v291();

    protected final EnumMap<MoveEntityDeltaPacket.Flag, TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket>> readers = new EnumMap<>(MoveEntityDeltaPacket.Flag.class);
    protected final EnumMap<MoveEntityDeltaPacket.Flag, TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket>> writers = new EnumMap<>(MoveEntityDeltaPacket.Flag.class);

    protected MoveEntityDeltaSerializer_v291() {
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_X, READER_DELTA_X);
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_Y, READER_DELTA_Y);
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_Z, READER_DELTA_Z);
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_PITCH, READER_PITCH);
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_YAW, READER_YAW);
        this.readers.put(MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW, READER_HEAD_YAW);

        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_X, WRITER_DELTA_X);
        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_Y, WRITER_DELTA_Y);
        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_Z, WRITER_DELTA_Z);
        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_PITCH, WRITER_PITCH);
        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_YAW, WRITER_YAW);
        this.writers.put(MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW, WRITER_HEAD_YAW);
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        VarInts.writeUnsignedLong(buffer, packet.getRuntimeEntityId());

        int flagsIndex = buffer.writerIndex();
        buffer.writeByte(0); // flags

        int flags = 0;
        for (MoveEntityDeltaPacket.Flag flag : packet.getFlags()) {
            flags |= 1 << flag.ordinal();

            TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> writer = this.writers.get(flag);
            if (writer != null) {
                writer.accept(buffer, helper, packet);
            }
        }

        // Go back to flags and set them
        int currentIndex = buffer.writerIndex();
        buffer.writerIndex(flagsIndex);
        buffer.writeByte(flags);
        buffer.writerIndex(currentIndex);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        packet.setRuntimeEntityId(VarInts.readUnsignedLong(buffer));
        int flags = buffer.readUnsignedByte();
        Set<MoveEntityDeltaPacket.Flag> flagSet = packet.getFlags();

        for (MoveEntityDeltaPacket.Flag flag : FLAGS) {
            if ((flags & (1 << flag.ordinal())) != 0) {
                flagSet.add(flag);
                TriConsumer<ByteBuf, BedrockCodecHelper, MoveEntityDeltaPacket> reader = this.readers.get(flag);
                if (reader != null) {
                    reader.accept(buffer, helper, packet);
                }
            }
        }
    }
}
