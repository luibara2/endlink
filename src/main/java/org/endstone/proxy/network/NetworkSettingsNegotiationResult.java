package org.endstone.proxy.network;

import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

public sealed interface NetworkSettingsNegotiationResult
        permits NetworkSettingsNegotiationResult.Accepted, NetworkSettingsNegotiationResult.Rejected {
    record Accepted(BedrockCodec clientCodec, NetworkSettingsPacket networkSettings)
            implements NetworkSettingsNegotiationResult {
    }

    /**
     * @param requestedProtocol the protocol the client asked for. Carried through so the rejection
     *                          can name it: when a new Minecraft version lands, the number in that
     *                          log line is the first thing needed to add support for it, and a
     *                          rejected client is the easiest way to obtain it.
     */
    record Rejected(int requestedProtocol, PlayStatusPacket playStatus)
            implements NetworkSettingsNegotiationResult {
    }
}
