package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PlayerAuthInput carries five optional blocks. The reader decides each one purely from the two
 * booleans on the wire; the writer used to decide from the {@code inputData} flag set instead. Every
 * assertion here is about those two agreeing.
 *
 * <p>When they disagree the packet still "decodes" and nothing throws — the relayed copy is simply
 * missing a block the far end is still counting on, so BDS reads the remainder from the wrong offset
 * and answers with a PacketViolationWarning at severity {@code TERMINATING_CONNECTION}: observed live
 * as {@code wrong const value for member "Action type"} / {@code readNoHeader failed! packetId: 144},
 * which drops the player and forces a failover.</p>
 */
class PlayerAuthInputOptionalsTest {

    private static final int PACKET_ID =
            Bedrock_v2168.CODEC.getPacketDefinition(PlayerAuthInputPacket.class).getId();

    @Test
    void aStackRequestOnTheWireSurvivesEvenWithoutItsFlag() {
        // The live failure. The block is present on the wire, the flag that used to gate the writer
        // is not, and the relayed copy silently lost the whole ItemStackRequest.
        ByteBuf fromClient = clientInput(EnumSet.of(PlayerAuthInputData.START_SPRINTING), true, false);

        PlayerAuthInputPacket decoded = decode(fromClient.copy());
        assertNotNull(decoded.getItemStackRequest(), "the reader takes the block from the wire boolean");

        assertReEncodesTo(fromClient, decoded);
    }

    @Test
    void blockActionsOnTheWireSurviveWithoutTheirFlag() {
        ByteBuf fromClient = clientInput(EnumSet.of(PlayerAuthInputData.START_SPRINTING), false, true);

        PlayerAuthInputPacket decoded = decode(fromClient.copy());
        assertEquals(1, decoded.getPlayerActions().size());

        assertReEncodesTo(fromClient, decoded);
    }

    @Test
    void aFlagWithoutItsPayloadDoesNotInventOne() {
        // The mirror image, and the reason the old writer could throw: the flag is set but the wire
        // says the block is absent, so getItemStackRequest() is null and writing it dereferenced null.
        ByteBuf fromClient = clientInput(
                EnumSet.of(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST,
                        PlayerAuthInputData.PERFORM_BLOCK_ACTIONS),
                false, false);

        PlayerAuthInputPacket decoded = decode(fromClient.copy());
        assertNull(decoded.getItemStackRequest());
        assertEquals(0, decoded.getPlayerActions().size());

        assertReEncodesTo(fromClient, decoded);
    }

    @Test
    void theOrdinaryTickWithNoOptionalsIsUnchanged() {
        // By far the common case - every player, every tick. It must not move.
        ByteBuf fromClient = clientInput(EnumSet.of(PlayerAuthInputData.START_SPRINTING), false, false);

        assertReEncodesTo(fromClient, decode(fromClient.copy()));
    }

    @Test
    void flagAndPayloadTogetherStillRoundTrip() {
        ByteBuf fromClient = clientInput(
                EnumSet.of(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST,
                        PlayerAuthInputData.PERFORM_BLOCK_ACTIONS),
                true, true);

        PlayerAuthInputPacket decoded = decode(fromClient.copy());
        assertNotNull(decoded.getItemStackRequest());
        assertEquals(1, decoded.getPlayerActions().size());

        assertReEncodesTo(fromClient, decoded);
    }

    private static void assertReEncodesTo(ByteBuf expected, PlayerAuthInputPacket decoded) {
        ByteBuf reEncoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), reEncoded, decoded);
            assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(reEncoded),
                    "the relayed copy must be identical to what the client sent");
        } finally {
            expected.release();
            reEncoded.release();
        }
    }

    private static PlayerAuthInputPacket decode(ByteBuf buffer) {
        try {
            PlayerAuthInputPacket packet = (PlayerAuthInputPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), buffer, PACKET_ID);
            assertEquals(0, buffer.readableBytes(), "the reader must consume the whole packet");
            return packet;
        } finally {
            buffer.release();
        }
    }

    /**
     * A 1.26.40 client tick. {@code flags} goes on the wire as-is; the two payload blocks are present
     * or absent independently of it, which is exactly the disagreement under test.
     */
    private static ByteBuf clientInput(Set<PlayerAuthInputData> flags,
                                       boolean withStackRequest,
                                       boolean withBlockActions) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeFloatLE(1f);                       // rotation x
        buf.writeFloatLE(2f);                       // rotation y
        writeVec3f(buf, 10f, 64f, 20f);             // position
        buf.writeFloatLE(0.1f);                     // motion x
        buf.writeFloatLE(0.2f);                     // motion y
        buf.writeFloatLE(3f);                       // rotation z

        buf.writeBoolean(true);
        VarInts.writeUnsignedInt(buf, flags.size());
        for (PlayerAuthInputData flag : flags) {    // EnumSet iterates in ordinal order, as the writer does
            VarInts.writeInt(buf, flag.ordinal());
        }

        VarInts.writeUnsignedInt(buf, 0);           // inputMode
        VarInts.writeUnsignedInt(buf, 0);           // playMode
        VarInts.writeInt(buf, 0);                   // inputInteractionModel
        writeVec2f(buf, 4f, 5f);                    // interactRotation
        VarInts.writeUnsignedLong(buf, 1234L);      // tick
        writeVec3f(buf, 0f, 0f, 0f);                // delta

        buf.writeBoolean(true);                     // itemUseTransaction
        buf.writeBoolean(false);

        buf.writeBoolean(true);                     // itemStackRequest
        buf.writeBoolean(withStackRequest);
        if (withStackRequest) {
            VarInts.writeInt(buf, 1);               // requestId
            VarInts.writeUnsignedInt(buf, 1);       // one action: CREATE, whose wire id is its ordinal
            VarInts.writeUnsignedInt(buf, 6);
            buf.writeByte(6);
            buf.writeByte(3);                       // created slot
            VarInts.writeUnsignedInt(buf, 0);       // no filter strings
            buf.writeIntLE(-1);                     // no TextProcessingEventOrigin
        }

        buf.writeBoolean(true);                     // playerActions
        buf.writeBoolean(withBlockActions);
        if (withBlockActions) {
            VarInts.writeUnsignedInt(buf, 1);
            VarInts.writeInt(buf, 0);               // PlayerActionType ordinal 0
            writeVec3i(buf, 1, 2, 3);
            VarInts.writeInt(buf, 4);               // face
        }

        buf.writeBoolean(true);                     // vehicleRotation
        buf.writeBoolean(false);
        buf.writeBoolean(true);                     // predictedVehicle
        buf.writeBoolean(false);

        writeVec2f(buf, 0f, 0f);                    // analogMoveVector
        writeVec3f(buf, 0f, 1f, 0f);                // cameraOrientation
        writeVec2f(buf, 0f, 0f);                    // rawMoveVector
        return buf;
    }

    private static void writeVec2f(ByteBuf buf, float x, float y) {
        buf.writeFloatLE(x);
        buf.writeFloatLE(y);
    }

    private static void writeVec3f(ByteBuf buf, float x, float y, float z) {
        buf.writeFloatLE(x);
        buf.writeFloatLE(y);
        buf.writeFloatLE(z);
    }

    private static void writeVec3i(ByteBuf buf, int x, int y, int z) {
        VarInts.writeInt(buf, x);
        VarInts.writeUnsignedInt(buf, y);
        VarInts.writeInt(buf, z);
    }
}
