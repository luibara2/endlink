package org.cloudburstmc.protocol.bedrock.codec.v766.serializer;

import io.netty.buffer.ByteBuf;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v748.serializer.PlayerAuthInputSerializer_v748;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.EnumSet;
import java.util.Set;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayerAuthInputSerializer_v766 extends PlayerAuthInputSerializer_v748 {

    public static final PlayerAuthInputSerializer_v766 INSTANCE = new PlayerAuthInputSerializer_v766();

    /**
     * How many input flags this form of the packet can carry: ordinals 0-64 inclusive.
     *
     * <p>The field is a {@code std::bitset<65>}, which gophertunnel names
     * {@code PlayerAuthInputBitsetSize = 65} — a constant its 1.26.40 branch <b>deletes</b>, because
     * 1.26.40 replaced the bitset with a list of set flag ids. A reader of this form rejects a bitset
     * whose bits run past 65 outright, so a single out-of-range flag loses the whole packet.
     *
     * <p><b>Why this cap has to exist.</b> {@code writeLargeVarIntFlags} sets one bit per ordinal with
     * no upper bound, so the width of what it writes is decided by however many constants
     * {@code PlayerAuthInputData} happens to have <em>today</em> — not by the version being written
     * to. 1.26.40 added {@code INTERNAL_UPDATE} at ordinal 65, so relaying a 1.26.40 client onto a
     * 1.26.30 backend now emits a 66-bit bitset whenever the client sets it, and the backend drops
     * the packet. That is the same defect family as the {@code 0xFFFF} preset and the 64-bit word
     * overflow: <b>a flag set whose size is fixed by one version and read by another.</b>
     *
     * <p>Dropping ordinal 65 is the correct translation — a backend on this version has no concept of
     * it. The cap can only ever remove bits no pre-1.26.40 version defines, so it cannot change any
     * encoding that works today.
     */
    protected static final int INPUT_BITSET_SIZE = 65;

    /**
     * The flags that may legally be written to this form, dropping any the destination version has no
     * bit for. Returns the set unchanged when there is nothing to drop, which is every packet from a
     * same-version client, so the common path allocates nothing.
     */
    protected static Set<PlayerAuthInputData> inputDataWithinBitset(Set<PlayerAuthInputData> inputData) {
        boolean needsFiltering = false;
        for (PlayerAuthInputData data : inputData) {
            if (data.ordinal() >= INPUT_BITSET_SIZE) {
                needsFiltering = true;
                break;
            }
        }
        if (!needsFiltering) {
            return inputData;
        }
        Set<PlayerAuthInputData> within = EnumSet.noneOf(PlayerAuthInputData.class);
        for (PlayerAuthInputData data : inputData) {
            if (data.ordinal() < INPUT_BITSET_SIZE) {
                within.add(data);
            }
        }
        return within;
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerAuthInputPacket packet) {
        Vector3f rotation = packet.getRotation();
        buffer.writeFloatLE(rotation.getX());
        buffer.writeFloatLE(rotation.getY());
        helper.writeVector3f(buffer, packet.getPosition());
        buffer.writeFloatLE(packet.getMotion().getX());
        buffer.writeFloatLE(packet.getMotion().getY());
        buffer.writeFloatLE(rotation.getZ());
        helper.writeLargeVarIntFlags(buffer, inputDataWithinBitset(packet.getInputData()), PlayerAuthInputData.class);
        VarInts.writeUnsignedInt(buffer, packet.getInputMode().ordinal());
        VarInts.writeUnsignedInt(buffer, packet.getPlayMode().ordinal());
        writeInteractionModel(buffer, helper, packet);
        helper.writeVector2f(buffer, packet.getInteractRotation());
        VarInts.writeUnsignedLong(buffer, packet.getTick());
        helper.writeVector3f(buffer, packet.getDelta());
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
            this.writeItemUseTransaction(buffer, helper, packet.getItemUseTransaction());
        }
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST)) {
            helper.writeItemStackRequest(buffer, packet.getItemStackRequest());
        }
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS)) {
            VarInts.writeInt(buffer, packet.getPlayerActions().size());
            for (PlayerBlockActionData actionData : packet.getPlayerActions()) {
                writePlayerBlockActionData(buffer, helper, actionData);
            }
        }
        if (packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)) {
            helper.writeVector2f(buffer, packet.getVehicleRotation());
            VarInts.writeLong(buffer, packet.getPredictedVehicle());
        }
        helper.writeVector2f(buffer, packet.getAnalogMoveVector());
        helper.writeVector3f(buffer, packet.getCameraOrientation());
        helper.writeVector2f(buffer, packet.getRawMoveVector());
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerAuthInputPacket packet) {
        float x = buffer.readFloatLE();
        float y = buffer.readFloatLE();
        packet.setPosition(helper.readVector3f(buffer));
        packet.setMotion(Vector2f.from(buffer.readFloatLE(), buffer.readFloatLE()));
        float z = buffer.readFloatLE();
        packet.setRotation(Vector3f.from(x, y, z));
        helper.readLargeVarIntFlags(buffer, packet.getInputData(), PlayerAuthInputData.class);
        packet.setInputMode(INPUT_MODES[VarInts.readUnsignedInt(buffer)]);
        packet.setPlayMode(CLIENT_PLAY_MODES[VarInts.readUnsignedInt(buffer)]);
        readInteractionModel(buffer, helper, packet);
        packet.setInteractRotation(helper.readVector2f(buffer));
        packet.setTick(VarInts.readUnsignedLong(buffer));
        packet.setDelta(helper.readVector3f(buffer));
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {
            packet.setItemUseTransaction(this.readItemUseTransaction(buffer, helper));
        }
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST)) {
            packet.setItemStackRequest(helper.readItemStackRequest(buffer));
        }
        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS)) {
            helper.readArray(buffer, packet.getPlayerActions(), VarInts::readInt, this::readPlayerBlockActionData, 32); // 32 is more than enough
        }
        if (packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)) {
            packet.setVehicleRotation(helper.readVector2f(buffer));
            packet.setPredictedVehicle(VarInts.readLong(buffer));
        }
        packet.setAnalogMoveVector(helper.readVector2f(buffer));
        packet.setCameraOrientation(helper.readVector3f(buffer));
        packet.setRawMoveVector(helper.readVector2f(buffer));
    }
}
