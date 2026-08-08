package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

public record ProtocolBinding(
        BedrockCodec clientCodec,
        BedrockCodec canonicalCodec,
        BedrockCodec backendCodec,
        PacketTranslator translator
) {
    public ProtocolBinding {
        if (clientCodec == null) {
            throw new IllegalArgumentException("clientCodec cannot be null");
        }
        if (canonicalCodec == null) {
            throw new IllegalArgumentException("canonicalCodec cannot be null");
        }
        if (backendCodec == null) {
            throw new IllegalArgumentException("backendCodec cannot be null");
        }
        if (translator == null) {
            throw new IllegalArgumentException("translator cannot be null");
        }
    }

    public int clientProtocolVersion() {
        return clientCodec.getProtocolVersion();
    }
}
