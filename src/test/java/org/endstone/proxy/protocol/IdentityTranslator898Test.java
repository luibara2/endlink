package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class IdentityTranslator898Test {
    @Test
    void preservesServerboundAndClientboundPacketObjects() {
        ProtocolBinding binding = ProtocolRegistry.createDefault().findClient(898).orElseThrow();
        TranslationContext context = new TranslationContext(
                binding.clientCodec(),
                binding.canonicalCodec(),
                binding.backendCodec()
        );
        TextPacket packet = new TextPacket();

        assertSame(packet, binding.translator().translateServerbound(packet, context));
        assertSame(packet, binding.translator().translateClientbound(packet, context));
    }

    @Test
    void preservesCommandTreeObject() {
        ProtocolBinding binding = ProtocolRegistry.createDefault().findClient(898).orElseThrow();
        TranslationContext context = new TranslationContext(
                binding.clientCodec(),
                binding.canonicalCodec(),
                binding.backendCodec()
        );
        AvailableCommandsPacket packet = new AvailableCommandsPacket();

        assertSame(packet, binding.translator().translateCommandTree(packet, context));
    }
}
