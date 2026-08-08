package org.cloudburstmc.protocol.bedrock.data.auth;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

public class DualPayload implements AuthPayload {
    @Getter
    private final List<String> chain;
    @Getter
    private final String token;
    private final AuthType type;

    public DualPayload(List<String> chain, String token, AuthType type) {
        this.chain = chain;
        this.token = token;
        this.type = Objects.requireNonNull(type);
    }

    @Override
    public AuthType getAuthType() {
        return type;
    }
}
