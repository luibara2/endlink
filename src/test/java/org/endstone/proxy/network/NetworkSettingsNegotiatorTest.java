package org.endstone.proxy.network;

import org.endstone.proxy.protocol.CanonicalProtocol;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.endstone.proxy.protocol.ProtocolNegotiator;
import org.endstone.proxy.protocol.ProtocolRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class NetworkSettingsNegotiatorTest {
    @Test
    void acceptedProtocolGetsClientCodecAndNetworkSettings() {
        NetworkSettingsNegotiator negotiator = new NetworkSettingsNegotiator(
                new ProtocolNegotiator(ProtocolRegistry.createDefault())
        );

        NetworkSettingsNegotiationResult.Accepted accepted = assertInstanceOf(
                NetworkSettingsNegotiationResult.Accepted.class,
                negotiator.handle(request(898))
        );

        assertEquals(898, accepted.clientCodec().getProtocolVersion());
        assertEquals(PacketCompressionAlgorithm.ZLIB, accepted.networkSettings().getCompressionAlgorithm());
        assertEquals(0, accepted.networkSettings().getCompressionThreshold());
    }

    @Test
    void unsupportedProtocolGetsPlayStatusRejection() {
        NetworkSettingsNegotiator negotiator = new NetworkSettingsNegotiator(
                new ProtocolNegotiator(ProtocolRegistry.createDefault())
        );

        NetworkSettingsNegotiationResult.Rejected rejected = assertInstanceOf(
                NetworkSettingsNegotiationResult.Rejected.class,
                negotiator.handle(request(CanonicalProtocol.newest().protocolVersion() + 1))
        );

        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD, rejected.playStatus().getStatus());
    }

    private static RequestNetworkSettingsPacket request(int protocol) {
        RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
        packet.setProtocolVersion(protocol);
        return packet;
    }
}
