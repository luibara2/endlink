package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;

public final class ProtocolNegotiator {
    private final ProtocolRegistry registry;

    public ProtocolNegotiator(ProtocolRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
    }

    public ProtocolNegotiation negotiate(RequestNetworkSettingsPacket packet) {
        int requestedProtocol = packet.getProtocolVersion();
        return registry.findClientCodec(requestedProtocol)
                .<ProtocolNegotiation>map(ProtocolNegotiation.Accepted::new)
                .orElseGet(() -> new ProtocolNegotiation.Rejected(
                        requestedProtocol,
                        registry.unsupportedStatus(requestedProtocol)
                ));
    }
}
