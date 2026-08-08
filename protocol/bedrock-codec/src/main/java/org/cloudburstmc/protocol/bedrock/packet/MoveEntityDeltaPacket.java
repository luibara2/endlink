package org.cloudburstmc.protocol.bedrock.packet;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.EnumSet;
import java.util.Set;

@Data
@EqualsAndHashCode(doNotUseGetters = true)
public class MoveEntityDeltaPacket implements BedrockPacket {
    private long runtimeEntityId;

    private final Set<Flag> flags = EnumSet.noneOf(Flag.class);

    @Deprecated
    private int deltaX;
    @Deprecated
    private int deltaY;
    @Deprecated
    private int deltaZ;

    private float x;
    private float y;
    private float z;

    private float pitch;
    private float yaw;
    private float headYaw;

    private boolean onGround;
    private boolean forceMove;
    private boolean forceMoveLocalEntity;
    private boolean forceCompletion;

    @Override
    public final PacketSignal handle(BedrockPacketHandler handler) {
        return handler.handle(this);
    }

    public BedrockPacketType getPacketType() {
        return BedrockPacketType.MOVE_ENTITY_DELTA;
    }

    public String toString() {
        return "MoveEntityDeltaPacket(runtimeEntityId=" + runtimeEntityId +
                ", flags=" + flags + ", delta=(" + deltaX + ", " + deltaY + ", " + deltaZ +
                "), position=(" + x + ", " + y + ", " + z +
                "), rotation=(" + pitch + ", " + yaw + ", " + headYaw + "), onGround=" + onGround +",forceMove=" +
                forceMove + ",forceMoveLocalEntity=" + forceMoveLocalEntity + ",forceCompletion=" + forceCompletion + ")";
    }

    public enum Flag {
        HAS_X,
        HAS_Y,
        HAS_Z,
        HAS_PITCH,
        HAS_YAW,
        HAS_HEAD_YAW,
        ON_GROUND,
        TELEPORTING,
        FORCE_MOVE_LOCAL_ENTITY,
        FORCE_COMPLETION
    }

    @Override
    public MoveEntityDeltaPacket clone() {
        try {
            return (MoveEntityDeltaPacket) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}

