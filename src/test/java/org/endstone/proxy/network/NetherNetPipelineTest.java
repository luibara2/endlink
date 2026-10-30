package org.endstone.proxy.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.UnframedBatchCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetherNetPipelineTest {

    private static final byte[] REQUEST_NETWORK_SETTINGS_BATCH = {0x06, (byte) 0xc1, 0x01, 0x00, 0x00, 0x08, (byte) 0xb1};

    @Test
    void aChannelWithoutRakNetOptionsIsTreatedAsAnUnframedVersion11Transport() {
        EmbeddedChannel channel = new EmbeddedChannel();
        assertFalse(BedrockChannelInitializer.isRakNet(channel));
        assertEquals(11, BedrockChannelInitializer.rakVersion(channel));
    }

    @Test
    void aBatchWithoutTheRakNetFrameIdDecodesIntoPackets() {
        List<BedrockPacket> received = new ArrayList<>();
        EmbeddedChannel channel = channel(received);

        assertInstanceOf(UnframedBatchCodec.class, channel.pipeline().get(FrameIdCodec.NAME));
        channel.writeInbound(Unpooled.wrappedBuffer(REQUEST_NETWORK_SETTINGS_BATCH));
        channel.runPendingTasks();

        assertEquals(1, received.size());
        RequestNetworkSettingsPacket packet = assertInstanceOf(RequestNetworkSettingsPacket.class, received.get(0));
        assertEquals(2225, packet.getProtocolVersion());
        channel.finishAndReleaseAll();
    }

    @Test
    void encryptionStaysOffOnAnUnframedTransport() {
        EmbeddedChannel channel = channel(new ArrayList<>());
        BedrockPeer peer = channel.pipeline().get(BedrockPeer.class);

        peer.enableEncryption(new SecretKeySpec(new byte[32], "AES"));

        assertNull(channel.pipeline().get(BedrockEncryptionDecoder.class));
        channel.finishAndReleaseAll();
    }

    @Test
    void outboundBatchesCarryNoFrameId() {
        List<BedrockServerSession> sessions = new ArrayList<>();
        EmbeddedChannel channel = channel(new ArrayList<>(), sessions);
        PlayStatusPacket packet = new PlayStatusPacket();
        packet.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);

        sessions.get(0).sendPacketImmediately(packet);

        ByteBuf written = channel.readOutbound();
        assertEquals(0x05, written.getUnsignedByte(0));
        assertEquals(0x02, written.getUnsignedByte(1));
        written.release();
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel channel(List<BedrockPacket> received) {
        return channel(received, new ArrayList<>());
    }

    private static EmbeddedChannel channel(List<BedrockPacket> received, List<BedrockServerSession> sessions) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new BedrockServerInitializer() {
            @Override
            protected void initSession(BedrockServerSession session) {
                sessions.add(session);
                session.setPacketHandler(new BedrockPacketHandler() {
                    @Override
                    public PacketSignal handlePacket(BedrockPacket packet) {
                        received.add(packet);
                        return PacketSignal.HANDLED;
                    }
                });
            }
        });
        channel.pipeline().fireChannelActive();
        return channel;
    }
}
