package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

public record TranslationContext(
        BedrockCodec clientCodec,
        BedrockCodec canonicalCodec,
        BedrockCodec backendCodec
) {
    public TranslationContext {
        if (clientCodec == null) {
            throw new IllegalArgumentException("clientCodec cannot be null");
        }
        if (canonicalCodec == null) {
            throw new IllegalArgumentException("canonicalCodec cannot be null");
        }
        if (backendCodec == null) {
            throw new IllegalArgumentException("backendCodec cannot be null");
        }
    }
}
