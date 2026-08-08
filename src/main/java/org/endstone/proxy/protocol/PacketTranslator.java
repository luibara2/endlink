package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

public interface PacketTranslator {
    /**
     * @return the packet to forward, or {@code null} when the source packet has no safe representation in the target
     * protocol and should be dropped.
     */
    BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context);

    /**
     * @return the packet to forward, or {@code null} when the source packet has no safe representation in the target
     * protocol and should be dropped.
     */
    BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context);

    AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context);
}
