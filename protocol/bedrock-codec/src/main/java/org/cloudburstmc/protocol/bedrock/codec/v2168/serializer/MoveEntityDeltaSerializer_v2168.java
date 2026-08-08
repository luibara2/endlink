package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v419.serializer.MoveEntityDeltaSerializer_v419;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import static org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket.Flag.*;

public class MoveEntityDeltaSerializer_v2168 extends MoveEntityDeltaSerializer_v419 {

    public static final MoveEntityDeltaSerializer_v2168 INSTANCE = new MoveEntityDeltaSerializer_v2168();

    protected MoveEntityDeltaSerializer_v2168() {
        super();
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        VarInts.writeUnsignedLong(buffer, packet.getRuntimeEntityId());
        helper.writeOptional(buffer, f->f.contains(HAS_X), packet.getFlags(), (buf, h) -> buf.writeFloatLE(packet.getX()));
        helper.writeOptional(buffer, f->f.contains(HAS_Y), packet.getFlags(), (buf, h) -> buf.writeFloatLE(packet.getY()));
        helper.writeOptional(buffer, f->f.contains(HAS_Z), packet.getFlags(), (buf, h) -> buf.writeFloatLE(packet.getZ()));
        helper.writeOptional(buffer, f->f.contains(HAS_PITCH), packet.getFlags(), (buf, h) -> helper.writeByteAngle(buf, packet.getPitch()));
        helper.writeOptional(buffer, f->f.contains(HAS_YAW), packet.getFlags(), (buf, h) -> helper.writeByteAngle(buf, packet.getYaw()));
        helper.writeOptional(buffer, f->f.contains(HAS_HEAD_YAW), packet.getFlags(), (buf, h) -> helper.writeByteAngle(buf, packet.getHeadYaw()));
        // Read these from the flag set as well as the booleans.
        //
        // 1.26.40 moved them out of the packed flag word into four trailing booleans, and upstream
        // reads only the booleans. Every earlier serializer — including the 1.26.30 one this proxy
        // decodes with — records them *solely* as members of packet.getFlags() and never touches the
        // boolean fields. Relaying 1.26.30 to 1.26.40 therefore told the client that every entity in
        // the world was airborne, was not teleporting and needed no move completion, at roughly 170
        // packets a second, because the four booleans were still at their default false.
        //
        // Losing ON_GROUND is the damaging one: the receiving client runs its own physics for every
        // entity it is told is unsupported, so the whole world is permanently falling and the client
        // fights a correction it can never win.
        buffer.writeBoolean(packet.isOnGround() || packet.getFlags().contains(ON_GROUND));
        // Bit 7 is Teleport and bit 8 is ForceMove on the pre-1.26.40 wire, and 1.26.40 has no
        // teleport boolean — both are a forced move as far as the new shape can express it.
        buffer.writeBoolean(packet.isForceMove()
                || packet.getFlags().contains(TELEPORTING)
                || packet.getFlags().contains(FORCE_MOVE_LOCAL_ENTITY));
        // The remaining two booleans exist only from 1.26.40, so they are taken from the fields and
        // never from the legacy flag word. There is no bit that means them on an older wire: bit 8 is
        // ForceMove (handled above) and bit 9 is not a flag at all, it is the untouched remainder of
        // the 0xFFFF preset. Reading them from the flag set asserted both on every relayed packet.
        buffer.writeBoolean(packet.isForceMoveLocalEntity());
        buffer.writeBoolean(packet.isForceCompletion());
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        packet.setRuntimeEntityId(VarInts.readUnsignedLong(buffer));
        packet.setX(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_X);
            return byteBuf.readFloatLE();
        }));
        packet.setY(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_Y);
            return byteBuf.readFloatLE();
        }));
        packet.setZ(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_Z);
            return byteBuf.readFloatLE();
        }));
        packet.setPitch(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_PITCH);
            return helper.readByteAngle(byteBuf);
        }));
        packet.setYaw(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_YAW);
            return helper.readByteAngle(byteBuf);
        }));
        packet.setHeadYaw(helper.readOptional(buffer, 0f, byteBuf -> {
            packet.getFlags().add(HAS_HEAD_YAW);
            return helper.readByteAngle(byteBuf);
        }));
        // Populate the flag set as well, so a packet decoded here can be re-encoded by any older
        // serializer — those read these four out of packet.getFlags(), not the booleans. Without
        // this the loss simply runs the other way.
        packet.setOnGround(readInto(buffer, packet, ON_GROUND));
        packet.setForceMove(readInto(buffer, packet, TELEPORTING));
        packet.setForceMoveLocalEntity(readInto(buffer, packet, FORCE_MOVE_LOCAL_ENTITY));
        packet.setForceCompletion(readInto(buffer, packet, FORCE_COMPLETION));
    }

    private static boolean readInto(ByteBuf buffer, MoveEntityDeltaPacket packet, MoveEntityDeltaPacket.Flag flag) {
        boolean set = buffer.readBoolean();
        if (set) {
            packet.getFlags().add(flag);
        }
        return set;
    }
}
