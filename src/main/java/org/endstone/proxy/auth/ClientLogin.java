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

    /**
     * The Minecraft version the client says it is running, or null if it did not say.
     *
     * <p>Worth having because the protocol number does not always identify the release: 1.26.40
     * through 1.26.44 all negotiate 2168 and do not all write the same bytes. See
     * {@link org.endstone.proxy.protocol.BedrockRelease}.
     *
     * <p>Self-reported and unsigned — it lives in the client data payload, which is signed only by
     * the client's own key. Fine for choosing between two wire shapes, where lying costs the liar
     * their own session and nobody else's; not a thing to make a security decision on.
     */
    public String gameVersion() {
        Object value = skinData.get("GameVersion");
        return value instanceof String version && !version.isBlank() ? version : null;
    }
}
