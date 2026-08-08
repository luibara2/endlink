package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;

import java.util.Optional;

public enum CanonicalProtocol {
    V1_21_130(Bedrock_v898.CODEC),
    V1_26_0(Bedrock_v924.CODEC),
    V1_26_10(Bedrock_v944.CODEC),
    V1_26_20(Bedrock_v975.CODEC),
    V1_26_30(Bedrock_v1001.CODEC),
    // Mojang renumbered at 1.26.40: 1001 -> 2168, not the ~1010 the earlier steps would suggest.
    V1_26_40(Bedrock_v2168.CODEC);

    private final BedrockCodec codec;

    CanonicalProtocol(BedrockCodec codec) {
        this.codec = codec;
    }

    public BedrockCodec codec() {
        return codec;
    }

    public int protocolVersion() {
        return codec.getProtocolVersion();
    }

    public String minecraftVersion() {
        return codec.getMinecraftVersion();
    }

    /**
     * The newest client version the proxy speaks. This is what the server list advertises, so
     * anything user-facing that names a version should derive it from here rather than hardcode one.
     */
    public static CanonicalProtocol newest() {
        CanonicalProtocol[] values = values();
        return values[values.length - 1];
    }

    public static CanonicalProtocol fromConfig(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        for (CanonicalProtocol protocol : values()) {
            if (Integer.toString(protocol.protocolVersion()).equals(normalized)
                    || protocol.minecraftVersion().equalsIgnoreCase(normalized)
                    || protocol.minecraftVersion().substring(2).equalsIgnoreCase(normalized)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unsupported backend protocol: " + value);
    }

    public static Optional<CanonicalProtocol> fromProtocolVersion(int protocolVersion) {
        for (CanonicalProtocol protocol : values()) {
            if (protocol.protocolVersion() == protocolVersion) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }
}
