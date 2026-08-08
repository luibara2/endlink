package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

public sealed interface ProtocolNegotiation permits ProtocolNegotiation.Accepted, ProtocolNegotiation.Rejected {
    record Accepted(BedrockCodec clientCodec) implements ProtocolNegotiation {
        public Accepted {
            if (clientCodec == null) {
                throw new IllegalArgumentException("clientCodec cannot be null");
            }
        }
    }

    record Rejected(int requestedProtocol, PlayStatusPacket.Status status) implements ProtocolNegotiation {
        public Rejected {
            if (status == null) {
                throw new IllegalArgumentException("status cannot be null");
            }
        }
    }
}
