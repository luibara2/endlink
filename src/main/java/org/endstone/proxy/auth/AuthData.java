package org.endstone.proxy.auth;

import java.util.UUID;

public record AuthData(String displayName, UUID identity, String xuid) {
    public AuthData {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity cannot be null");
        }
        if (xuid == null || xuid.isBlank()) {
            throw new IllegalArgumentException("xuid cannot be blank");
        }
    }
}
