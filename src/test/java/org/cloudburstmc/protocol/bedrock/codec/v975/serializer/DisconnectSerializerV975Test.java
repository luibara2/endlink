package org.cloudburstmc.protocol.bedrock.codec.v975.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.DisconnectFailReason;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A backend shutting down sends a disconnect, and until the reason ordinal stopped indexing the enum
 * blindly, a reason newer than this build's {@code DisconnectFailReason} made the whole packet
 * undecodable. It was then relayed to the client as a raw payload — an unrecoverable kick that fired
 * before the proxy could fail the player over.
 */
final class DisconnectSerializerV975Test {
    @Test
    void decodesAReasonNewerThanThisBuildKnowsAbout() {
        int unknownToUs = DisconnectFailReason.values().length + 7;
        ByteBuf buffer = encoded(unknownToUs, "Server closed");

        DisconnectPacket decoded = new DisconnectPacket();
        DisconnectSerializer_v975.INSTANCE.deserialize(buffer, helper(), decoded);

        assertEquals(DisconnectFailReason.UNKNOWN, decoded.getReason());
        assertEquals("Server closed", decoded.getKickMessage());
    }

    @Test
    void relaysAnUnknownReasonBackOntoTheWireUnchanged() {
        // The proxy re-encodes what it decoded, so degrading the reason to UNKNOWN must not change
        // the bytes the client receives.
        int unknownToUs = DisconnectFailReason.values().length + 7;
        ByteBuf original = encoded(unknownToUs, "Server closed");
        byte[] originalBytes = bytes(original.duplicate());

        DisconnectPacket decoded = new DisconnectPacket();
        DisconnectSerializer_v975.INSTANCE.deserialize(original, helper(), decoded);

        ByteBuf reencoded = Unpooled.buffer();
        DisconnectSerializer_v975.INSTANCE.serialize(reencoded, helper(), decoded);

        assertArrayEquals(originalBytes, bytes(reencoded));
        assertEquals(0, original.readableBytes());
    }

    @Test
    void roundTripsAKnownReason() {
        ByteBuf original = encoded(DisconnectFailReason.KICKED.ordinal(), "Kicked by an operator");
        byte[] originalBytes = bytes(original.duplicate());

        DisconnectPacket decoded = new DisconnectPacket();
        DisconnectSerializer_v975.INSTANCE.deserialize(original, helper(), decoded);
        assertEquals(DisconnectFailReason.KICKED, decoded.getReason());

        ByteBuf reencoded = Unpooled.buffer();
        DisconnectSerializer_v975.INSTANCE.serialize(reencoded, helper(), decoded);

        assertArrayEquals(originalBytes, bytes(reencoded));
    }

    @Test
    void usesTheEnumOrdinalForAPacketThatWasBuiltRatherThanDecoded() {
        DisconnectPacket built = new DisconnectPacket();
        built.setReason(DisconnectFailReason.TIMEOUT);
        built.setMessageSkipped(true);

        ByteBuf encoded = Unpooled.buffer();
        DisconnectSerializer_v975.INSTANCE.serialize(encoded, helper(), built);

        assertEquals(DisconnectFailReason.TIMEOUT.ordinal(), VarInts.readInt(encoded));
    }

    private static ByteBuf encoded(int reasonOrdinal, String kickMessage) {
        ByteBuf buffer = Unpooled.buffer();
        VarInts.writeInt(buffer, reasonOrdinal);
        VarInts.writeUnsignedInt(buffer, 0);
        helper().writeString(buffer, kickMessage);
        helper().writeString(buffer, "");
        return buffer;
    }

    private static BedrockCodecHelper helper() {
        return Bedrock_v1001.CODEC.createHelper();
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] copy = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), copy);
        return copy;
    }
}
