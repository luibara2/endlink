package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProtocolNegotiatorTest {
    @Test
    void acceptsSupportedProtocol() {
        ProtocolNegotiator negotiator = new ProtocolNegotiator(ProtocolRegistry.createDefault());
        RequestNetworkSettingsPacket packet = request(898);

        ProtocolNegotiation negotiation = negotiator.negotiate(packet);

        ProtocolNegotiation.Accepted accepted = assertInstanceOf(ProtocolNegotiation.Accepted.class, negotiation);
        assertEquals(898, accepted.clientCodec().getProtocolVersion());
    }

    @Test
    void rejectsOldClientProtocol() {
        ProtocolNegotiator negotiator = new ProtocolNegotiator(ProtocolRegistry.createDefault());

        ProtocolNegotiation.Rejected rejected = assertInstanceOf(
                ProtocolNegotiation.Rejected.class,
                negotiator.negotiate(request(860))
        );
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, rejected.status());
    }

    @Test
    void acceptsNewestSupportedProtocol() {
        ProtocolNegotiator negotiator = new ProtocolNegotiator(ProtocolRegistry.createDefault());

        ProtocolNegotiation.Accepted accepted = assertInstanceOf(
                ProtocolNegotiation.Accepted.class,
                negotiator.negotiate(request(1001))
        );
        assertEquals(1001, accepted.clientCodec().getProtocolVersion());
    }

    @Test
    void rejectsProtocolNewerThanNewestSupported() {
        ProtocolNegotiator negotiator = new ProtocolNegotiator(ProtocolRegistry.createDefault());

        ProtocolNegotiation.Rejected rejected = assertInstanceOf(
                ProtocolNegotiation.Rejected.class,
                negotiator.negotiate(request(CanonicalProtocol.newest().protocolVersion() + 1))
        );
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD, rejected.status());
    }

    private static RequestNetworkSettingsPacket request(int protocol) {
        RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
        packet.setProtocolVersion(protocol);
        return packet;
    }
}
