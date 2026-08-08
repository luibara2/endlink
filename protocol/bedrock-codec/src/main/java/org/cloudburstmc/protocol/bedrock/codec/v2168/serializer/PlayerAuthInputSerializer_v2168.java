package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v944.serializer.PlayerAuthInputSerializer_v944;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.LegacySetItemSlotData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

public class PlayerAuthInputSerializer_v2168 extends PlayerAuthInputSerializer_v944 {

    public static final PlayerAuthInputSerializer_v2168 INSTANCE = new PlayerAuthInputSerializer_v2168();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerAuthInputPacket packet) {
        Vector3f rotation = packet.getRotation();
        buffer.writeFloatLE(rotation.getX());
        buffer.writeFloatLE(rotation.getY());
        helper.writeVector3f(buffer, packet.getPosition());
        buffer.writeFloatLE(packet.getMotion().getX());
        buffer.writeFloatLE(packet.getMotion().getY());
        buffer.writeFloatLE(rotation.getZ());

        buffer.writeBoolean(true);
        VarInts.writeUnsignedInt(buffer, packet.getInputData().size());
        for (PlayerAuthInputData flag : packet.getInputData()) {
            VarInts.writeInt(buffer, flag.ordinal());
        }

        VarInts.writeUnsignedInt(buffer, packet.getInputMode().ordinal());
        VarInts.writeUnsignedInt(buffer, packet.getPlayMode().ordinal());
        VarInts.writeInt(buffer, packet.getInputInteractionModel().ordinal());
        helper.writeVector2f(buffer, packet.getInteractRotation());
        VarInts.writeUnsignedLong(buffer, packet.getTick());
        helper.writeVector3f(buffer, packet.getDelta());
        // These optionals are keyed off the decoded value, not off the input-data flag, because that
        // is what `deserialize` below does: it reads each block purely from the two booleans on the
        // wire and never consults `inputData`. Gating the writer on the flag instead makes read and
        // write disagree whenever a client's flags and payloads do not line up exactly — a payload
        // arriving without its flag is silently dropped, which leaves BDS reading the rest of the
        // packet from the wrong offset and rejecting it with a const mismatch on whichever member it
        // happens to land on (observed live as `wrong const value for member "Action type"` /
        // `readNoHeader failed! packetId: 144`, severity TERMINATING_CONNECTION). A flag arriving
        // without its payload was worse still: `getItemUseTransaction()` is then null and the write
        // threw. Keying off the value reproduces the reader exactly, which is the only thing a relay
        // can be correct against. See PlayerAuthInputOptionalsTest.
        buffer.writeBoolean(true);
        if (packet.getItemUseTransaction() != null) {
            buffer.writeBoolean(true);
            this.writeItemUseTransaction(buffer, helper, packet.getItemUseTransaction());
        } else {
            buffer.writeBoolean(false);
        }
        buffer.writeBoolean(true);
        if (packet.getItemStackRequest() != null) {
            buffer.writeBoolean(true);
            helper.writeItemStackRequest(buffer, packet.getItemStackRequest());
        } else {
            buffer.writeBoolean(false);
        }
        buffer.writeBoolean(true);
        if (!packet.getPlayerActions().isEmpty()) {
            buffer.writeBoolean(true);
            VarInts.writeUnsignedInt(buffer, packet.getPlayerActions().size());
            for (PlayerBlockActionData actionData : packet.getPlayerActions()) {
                writePlayerBlockActionData(buffer, helper, actionData);
            }
        } else {
            buffer.writeBoolean(false);
        }
        buffer.writeBoolean(true);
        if (packet.getVehicleRotation() != null) {
            buffer.writeBoolean(true);
            helper.writeVector2f(buffer, packet.getVehicleRotation());
        } else {
            buffer.writeBoolean(false);
        }
        // The one optional still keyed off the flag: predictedVehicle is a primitive long, so an
        // absent one is indistinguishable from a zero once decoded and the reader's answer cannot be
        // recovered from the packet. Left as-is because it is not expressible, not because it is
        // right — clients send it together with the vehicle rotation above, so the two agree in
        // practice. Expressing it needs a presence field on PlayerAuthInputPacket.
        buffer.writeBoolean(true);
        if (packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)) {
            buffer.writeBoolean(true);
            VarInts.writeLong(buffer, packet.getPredictedVehicle());
        } else {
            buffer.writeBoolean(false);
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

        if (buffer.readBoolean()) {
            int count = VarInts.readUnsignedInt(buffer);
            for (int i = 0; i < count; i++) {
                int index = VarInts.readInt(buffer);
                packet.getInputData().add(PlayerAuthInputData.values()[index]);
            }
        }

        packet.setInputMode(INPUT_MODES[VarInts.readUnsignedInt(buffer)]);
        packet.setPlayMode(CLIENT_PLAY_MODES[VarInts.readUnsignedInt(buffer)]);
        packet.setInputInteractionModel(VALUES[VarInts.readInt(buffer)]);
        packet.setInteractRotation(helper.readVector2f(buffer));
        packet.setTick(VarInts.readUnsignedLong(buffer));
        packet.setDelta(helper.readVector3f(buffer));
        if (buffer.readBoolean() && buffer.readBoolean()) {
            packet.setItemUseTransaction(this.readItemUseTransaction(buffer, helper));
        }
        if (buffer.readBoolean() && buffer.readBoolean()) {
            packet.setItemStackRequest(helper.readItemStackRequest(buffer));
        }
        if (buffer.readBoolean() && buffer.readBoolean()) {
            helper.readArray(buffer, packet.getPlayerActions(), this::readPlayerBlockActionData, 100);
        }
        if (buffer.readBoolean() && buffer.readBoolean()) {
            packet.setVehicleRotation(helper.readVector2f(buffer));
        }
        if (buffer.readBoolean() && buffer.readBoolean()) {
            packet.setPredictedVehicle(VarInts.readLong(buffer));
        }
        packet.setAnalogMoveVector(helper.readVector2f(buffer));
        packet.setCameraOrientation(helper.readVector3f(buffer));
        packet.setRawMoveVector(helper.readVector2f(buffer));
    }
    @Override
    protected void writeItemUseTransaction(ByteBuf buffer, BedrockCodecHelper helper, ItemUseTransaction transaction) {
        int legacyRequestId = transaction.getLegacyRequestId();
        VarInts.writeInt(buffer, legacyRequestId);

        if (legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
            buffer.writeBoolean(true);
            helper.writeArray(buffer, transaction.getLegacySlots(), (buf, packetHelper, data) -> {
                buf.writeByte(data.getContainerId());
                packetHelper.writeByteArray(buf, data.getSlots());
            });
        } else {
            buffer.writeBoolean(false);
        }

        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
        helper.writeInventoryActions(buffer, transaction.getActions(), transaction.isUsingNetIds());

        VarInts.writeInt(buffer, transaction.getActionType());
        buffer.writeByte(transaction.getTriggerType().ordinal());
        helper.writeBlockPosition(buffer, transaction.getBlockPosition());
        buffer.writeByte(transaction.getBlockFace());
        VarInts.writeInt(buffer, transaction.getHotbarSlot());
        helper.writeItem(buffer, transaction.getItemInHand());
        helper.writeVector3f(buffer, transaction.getPlayerPosition());
        helper.writeVector3f(buffer, transaction.getClickPosition());
        VarInts.writeUnsignedInt(buffer, transaction.getBlockDefinition().getRuntimeId());
        buffer.writeByte(transaction.getClientInteractPrediction().ordinal());

        buffer.writeByte(transaction.getClientCooldownState());
    }

    @Override
    protected ItemUseTransaction readItemUseTransaction(ByteBuf buffer, BedrockCodecHelper helper) {
        ItemUseTransaction itemTransaction = new ItemUseTransaction();

        int legacyRequestId = VarInts.readInt(buffer);
        itemTransaction.setLegacyRequestId(legacyRequestId);

        if (buffer.readBoolean()) {
            if (legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
                helper.readArray(buffer, itemTransaction.getLegacySlots(), (buf, packetHelper) -> {
                    int containerId = buffer.readUnsignedByte();
                    byte[] slots = packetHelper.readByteArray(buf, 89);
                    return new LegacySetItemSlotData(containerId, slots);
                });
            }
        }

        if (buffer.readBoolean() && buffer.readBoolean()) {
            helper.readInventoryActions(buffer, itemTransaction.getActions());
        }

        itemTransaction.setActionType(VarInts.readInt(buffer));
        itemTransaction.setTriggerType(ItemUseTransaction.TriggerType.values()[buffer.readUnsignedByte()]);
        itemTransaction.setBlockPosition(helper.readBlockPosition(buffer));
        itemTransaction.setBlockFace(buffer.readUnsignedByte());
        itemTransaction.setHotbarSlot(VarInts.readInt(buffer));
        itemTransaction.setItemInHand(helper.readItem(buffer));
        itemTransaction.setPlayerPosition(helper.readVector3f(buffer));
        itemTransaction.setClickPosition(helper.readVector3f(buffer));
        itemTransaction.setBlockDefinition(helper.getBlockDefinitions().getDefinition(VarInts.readUnsignedInt(buffer)));
        itemTransaction.setClientInteractPrediction(ItemUseTransaction.PredictedResult.values()[buffer.readUnsignedByte()]);

        itemTransaction.setClientCooldownState(buffer.readUnsignedByte());
        return itemTransaction;
    }

    @Override
    protected void writePlayerBlockActionData(ByteBuf buffer, BedrockCodecHelper helper, PlayerBlockActionData actionData) {
        VarInts.writeInt(buffer, actionData.getAction().ordinal());
        helper.writeVector3i(buffer, actionData.getBlockPosition());
        VarInts.writeInt(buffer, actionData.getFace());
    }

    @Override
    protected PlayerBlockActionData readPlayerBlockActionData(ByteBuf buffer, BedrockCodecHelper helper) {
        PlayerBlockActionData actionData = new PlayerBlockActionData();
        actionData.setAction(PlayerActionType.values()[VarInts.readInt(buffer)]);
        actionData.setBlockPosition(helper.readVector3i(buffer));
        actionData.setFace(VarInts.readInt(buffer));
        return actionData;
    }
}
