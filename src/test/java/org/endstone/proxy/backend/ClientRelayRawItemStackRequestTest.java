package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ClientRelayRawItemStackRequestTest {
    private static final int PACKET_ID = 0x93;

    @Test
    void copiesTheExactOriginalPayloadWithoutTheBedrockHeader() {
        byte[] header = {(byte) 0x93, 0x02};
        byte[] payload = {0x01, 0x22, 0x00, (byte) 0xff, 0x45, 0x10};
        ByteBuf wirePacket = Unpooled.buffer().writeBytes(header).writeBytes(payload);
        BedrockPacketWrapper inbound = BedrockPacketWrapper.create(
                PACKET_ID,
                0,
                0,
                new ItemStackRequestPacket(),
                wirePacket
        );
        inbound.setHeaderLength(header.length);

        UnknownPacket copy = ClientRelayPacketHandler.copyOriginalPayload(inbound, PACKET_ID);
        try {
            assertEquals(PACKET_ID, copy.getPacketId());
            assertArrayEquals(payload, ByteBufUtil.getBytes(copy.getPayload()));
            assertEquals(2, wirePacket.refCnt(), "the outbound payload must survive the inbound wrapper release");
        } finally {
            copy.release();
            inbound.release();
        }
    }

    @Test
    void refusesAWrapperForAnotherPacketType() {
        ByteBuf wirePacket = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02});
        BedrockPacketWrapper inbound = BedrockPacketWrapper.create(
                PACKET_ID + 1,
                0,
                0,
                new ItemStackRequestPacket(),
                wirePacket
        );
        inbound.setHeaderLength(1);

        try {
            assertNull(ClientRelayPacketHandler.copyOriginalPayload(inbound, PACKET_ID));
        } finally {
            inbound.release();
        }
    }
}
