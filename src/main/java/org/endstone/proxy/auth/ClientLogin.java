package org.endstone.proxy.auth;

import org.jose4j.json.internal.json_simple.JSONObject;

import java.net.InetSocketAddress;
import java.security.PublicKey;

/**
 * @param bridgeClientAddress the player's real address when they arrived through the a bridged edition
 *                            bridge, or null for an ordinary Bedrock client. Bridged players all share
 *                            one loopback source address, so this is the only place their real
 *                            address exists &mdash; see {@link ClientLoginAuthenticator}
 */
public record ClientLogin(
        AuthData authData,
        JSONObject skinData,
        PublicKey identityPublicKey,
        InetSocketAddress bridgeClientAddress
) {
    public ClientLogin(AuthData authData, JSONObject skinData, PublicKey identityPublicKey) {
        this(authData, skinData, identityPublicKey, null);
    }

    public ClientLogin {
        if (authData == null) {
            throw new IllegalArgumentException("authData cannot be null");
        }
        if (skinData == null) {
            throw new IllegalArgumentException("skinData cannot be null");
        }
        if (identityPublicKey == null) {
            throw new IllegalArgumentException("identityPublicKey cannot be null");
        }
    }

    /** True when this player reached the proxy through the a bridged edition bridge. */
    public boolean isJavaEdition() {
        return bridgeClientAddress != null;
    }
}
