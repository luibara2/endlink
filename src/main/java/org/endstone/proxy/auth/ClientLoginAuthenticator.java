package org.endstone.proxy.auth;

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Map;
import java.util.UUID;

public final class ClientLoginAuthenticator {
    private final boolean requireXuid;
    private final boolean trustSelfSigned;
    private final String bridgeSecret;
    private final String javaNamePrefix;

    public ClientLoginAuthenticator() {
        this(true);
    }

    /**
     * @param requireXuid reject a login whose Mojang-signed chain carries no XUID rather than
     *                    substituting "0". The substitute is indistinguishable between players: it
     *                    is what the duplicate-login check keys on and what the backend verifier
     *                    signs, so two such logins are the same identity as far as the whole network
     *                    is concerned
     */
    public ClientLoginAuthenticator(boolean requireXuid) {
        this(requireXuid, false);
    }

    /**
     * @param trustSelfSigned accept a login whose token is self-signed rather than Mojang-signed.
     *                        <p><b>This is an authentication bypass and must never be set for the
     *                        public listener.</b> Its only caller is the a bridged edition bridge
     *                        listener, which is bound to loopback: a bridge addon has no Xbox account to
     *                        sign with, so it mints a self-signed token whose XUID is
     *                        {@code FNV1(javaUsername)} and whose identity UUID is the usual
     *                        {@code pocket-auth-1-xuid:} derivation. Anything reaching that listener
     *                        can therefore claim any name, which is why the listener refuses to bind
     *                        anywhere but 127.0.0.1 &mdash; an attacker needs local code execution on
     *                        the proxy host before this flag is worth anything to them.</p>
     */
    public ClientLoginAuthenticator(boolean requireXuid, boolean trustSelfSigned) {
        this(requireXuid, trustSelfSigned, null, "");
    }

    /**
     * The bridge listener's constructor.
     *
     * @param bridgeSecret  the per-start secret the embedded the bridge stamps into every login it
     *                      forwards. When set, a self-signed login that does not carry it is rejected:
     *                      that is what narrows "any local process may claim any name" down to "our own
     *                      translator may". Null keeps the older behaviour of trusting loopback alone
     * @param javaNamePrefix prepended to the display name of everyone arriving through the bridge, so
     *                       bridged players are distinguishable in chat and on the player list. Empty for
     *                       no prefix. It is applied <em>after</em> the XUID is derived, so a player's
     *                       identity does not change when an operator changes the prefix
     */
    public ClientLoginAuthenticator(boolean requireXuid, boolean trustSelfSigned, String bridgeSecret,
                                    String javaNamePrefix) {
        this.requireXuid = requireXuid;
        this.trustSelfSigned = trustSelfSigned;
        this.bridgeSecret = bridgeSecret;
        this.javaNamePrefix = javaNamePrefix == null ? "" : javaNamePrefix;
    }

    public ClientLogin authenticate(LoginPacket packet) {
        try {
            ChainValidationResult chain = EncryptionUtils.validatePayload(packet.getAuthPayload());
            if (!chain.signed() && !trustSelfSigned) {
                throw new IllegalStateException("Client login chain is not Mojang-signed");
            }

            PublicKey identityPublicKey = chain.identityClaims().parsedIdentityPublicKey();
            JsonWebSignature clientData = new JsonWebSignature();
            clientData.setCompactSerialization(packet.getClientJwt());
            clientData.setKey(identityPublicKey);
            if (!clientData.verifySignature()) {
                throw new IllegalStateException("Client data signature does not match login identity key");
            }

            @SuppressWarnings("unchecked")
            JSONObject skinData = new JSONObject((Map<String, Object>) JsonUtil.parseJson(clientData.getUnverifiedPayload()));
            ChainValidationResult.IdentityData identityData = chain.identityClaims().extraData;
            if (requireXuid && (identityData.xuid == null || identityData.xuid.isBlank())) {
                throw new IllegalStateException(
                        "Mojang-signed login chain for " + identityData.displayName + " carries no XUID");
            }
            String xuid = nonBlank(identityData.xuid, "0");
            UUID identity = identityData.identity != null
                    ? identityData.identity
                    : UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8));

            String displayName = nonBlank(identityData.displayName, "Player");
            InetSocketAddress bridgeAddress = null;
            if (trustSelfSigned && !chain.signed()) {
                Map<String, Object> claims = chain.rawIdentityClaims();
                requireBridgeSecret(claims, displayName);
                bridgeAddress = bridgeClientAddress(claims);
                displayName = javaNamePrefix + displayName;
            }

            return new ClientLogin(
                    new AuthData(displayName, identity, xuid),
                    skinData,
                    identityPublicKey,
                    bridgeAddress
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to authenticate Bedrock login", exception);
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * A self-signed login is only acceptable when it proves it came from the the bridge this proxy
     * started. Compared with a constant-time equality: the comparison is against a value an attacker
     * would be trying to guess, and a leaky compare is exactly how such a value gets guessed.
     */
    private void requireBridgeSecret(Map<String, Object> claims, String displayName) {
        if (bridgeSecret == null || bridgeSecret.isEmpty()) {
            return;
        }
        Object presented = claims.get("ep_secret");
        byte[] expectedBytes = bridgeSecret.getBytes(StandardCharsets.UTF_8);
        byte[] presentedBytes = presented == null
                ? new byte[0]
                : presented.toString().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, presentedBytes)) {
            throw new IllegalStateException("Self-signed login for " + displayName
                    + " did not carry this run's bridge secret, so it did not come from the "
                    + "embedded the bridge. Refusing it.");
        }
    }

    /**
     * The player's real address, as stamped in by the bridge. Everything arriving on the bridge shares
     * one loopback source address, so without this the logs, the throttle and any backend doing its own
     * IP checks all see 127.0.0.1 for every bridged player.
     */
    private static InetSocketAddress bridgeClientAddress(Map<String, Object> claims) {
        Object ip = claims.get("ep_ip");
        if (ip == null || ip.toString().isBlank()) {
            return null;
        }
        int port = 0;
        Object rawPort = claims.get("ep_port");
        if (rawPort instanceof Number number) {
            port = number.intValue();
        } else if (rawPort != null) {
            try {
                port = Integer.parseInt(rawPort.toString().trim());
            } catch (NumberFormatException ignored) {
                port = 0;
            }
        }
        try {
            return new InetSocketAddress(
                    InetAddress.getByName(ip.toString().trim()),
                    port < 0 || port > 65535 ? 0 : port
            );
        } catch (UnknownHostException exception) {
            // Unresolvable means malformed rather than remote-lookup-failed: this is a literal address
            // the bridge read off a live socket. Fall back to the socket address rather than fail the join.
            return null;
        }
    }
}
