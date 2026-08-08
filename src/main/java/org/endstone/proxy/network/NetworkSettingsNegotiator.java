package org.endstone.proxy.network;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.endstone.proxy.protocol.ProtocolNegotiation;
import org.endstone.proxy.protocol.ProtocolNegotiator;

public final class NetworkSettingsNegotiator {
    private final ProtocolNegotiator protocolNegotiator;
    private final PacketCompressionAlgorithm compressionAlgorithm;
    private final int compressionThreshold;

    public NetworkSettingsNegotiator(ProtocolNegotiator protocolNegotiator) {
        this(protocolNegotiator, PacketCompressionAlgorithm.ZLIB, 0);
    }

    public NetworkSettingsNegotiator(
            ProtocolNegotiator protocolNegotiator,
            PacketCompressionAlgorithm compressionAlgorithm,
            int compressionThreshold
    ) {
        if (protocolNegotiator == null) {
            throw new IllegalArgumentException("protocolNegotiator cannot be null");
        }
        if (compressionAlgorithm == null) {
            throw new IllegalArgumentException("compressionAlgorithm cannot be null");
        }
        if (compressionThreshold < 0) {
            throw new IllegalArgumentException("compressionThreshold cannot be negative");
        }
        this.protocolNegotiator = protocolNegotiator;
        this.compressionAlgorithm = compressionAlgorithm;
        this.compressionThreshold = compressionThreshold;
    }

    public NetworkSettingsNegotiationResult handle(RequestNetworkSettingsPacket request) {
        ProtocolNegotiation negotiation = protocolNegotiator.negotiate(request);
        if (negotiation instanceof ProtocolNegotiation.Accepted accepted) {
            return new NetworkSettingsNegotiationResult.Accepted(
                    accepted.clientCodec(),
                    acceptedNetworkSettings()
            );
        }

        ProtocolNegotiation.Rejected rejected = (ProtocolNegotiation.Rejected) negotiation;
        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(rejected.status());
        return new NetworkSettingsNegotiationResult.Rejected(rejected.requestedProtocol(), playStatus);
    }

    private NetworkSettingsPacket acceptedNetworkSettings() {
        NetworkSettingsPacket packet = new NetworkSettingsPacket();
        packet.setCompressionAlgorithm(compressionAlgorithm);
        packet.setCompressionThreshold(compressionThreshold);
        return packet;
    }
}
