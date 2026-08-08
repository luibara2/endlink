package org.endstone.proxy.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.endstone.proxy.protocol.PacketTranslator;
import org.endstone.proxy.protocol.ProtocolBinding;
import org.endstone.proxy.protocol.TranslationContext;

public record ProxySessionProfile(
        BedrockCodec clientCodec,
        BedrockCodec canonicalCodec,
        BedrockCodec backendCodec,
        PacketTranslator translator
) {
    public static ProxySessionProfile from(ProtocolBinding binding) {
        return new ProxySessionProfile(
                binding.clientCodec(),
                binding.canonicalCodec(),
                binding.backendCodec(),
                binding.translator()
        );
    }

    public TranslationContext translationContext() {
        return new TranslationContext(clientCodec, canonicalCodec, backendCodec);
    }
}
