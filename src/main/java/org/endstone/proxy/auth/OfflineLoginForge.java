package org.endstone.proxy.auth;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.DualPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class OfflineLoginForge {
    public LoginPacket forge(KeyPair keyPair, ClientLogin clientLogin) {
        return forge(keyPair, clientLogin, CanonicalProtocol.V1_21_130.protocolVersion());
    }

    public LoginPacket forge(KeyPair keyPair, ClientLogin clientLogin, int protocolVersion) {
        return forge(keyPair, clientLogin, protocolVersion, (String) null);
    }

    public LoginPacket forge(KeyPair keyPair, ClientLogin clientLogin, int protocolVersion, String minecraftVersion) {
        return forge(keyPair, clientLogin, protocolVersion, minecraftVersion, AuthType.SELF_SIGNED);
    }

    public LoginPacket forge(KeyPair keyPair, ClientLogin clientLogin, int protocolVersion, AuthType authType) {
        return forge(keyPair, clientLogin, protocolVersion, null, authType);
    }

    public LoginPacket forge(
            KeyPair keyPair,
            ClientLogin clientLogin,
            int protocolVersion,
            String minecraftVersion,
            String serverAddress,
            AuthType authType
    ) {
        AuthPayload payload = new CertificateChainPayload(
                List.of(forgeAuthData(keyPair, clientLogin.authData())),
                authType
        );

        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(protocolVersion);
        login.setAuthPayload(payload);
        login.setClientJwt(forgeSkinData(keyPair, clientLogin.skinData(), clientLogin.authData(), protocolVersion, minecraftVersion, serverAddress));
        return login;
    }

    public LoginPacket forgeTokenPayload(
            KeyPair keyPair,
            ClientLogin clientLogin,
            int protocolVersion,
            String minecraftVersion,
            String serverAddress
    ) {
        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(protocolVersion);
        login.setAuthPayload(new TokenPayload(forgeAuthData(keyPair, clientLogin.authData()), AuthType.SELF_SIGNED));
        login.setClientJwt(forgeSkinData(keyPair, clientLogin.skinData(), clientLogin.authData(), protocolVersion, minecraftVersion, serverAddress));
        return login;
    }

    /**
     * Forges the modern self-signed login format used by Bedrock 1.26.10+ (protocol 944+).
     * Sends the modern self-signed OIDC multiplayer token in the Token field for the
     * 1.26.10+ discovery-environment check, while keeping a legacy certificate in the
     * Certificate field so backends that still source Player.xuid from extraData.XUID can
     * populate it.
     */
    public LoginPacket forgeOidcLogin(
            KeyPair keyPair,
            ClientLogin clientLogin,
            int protocolVersion,
            String minecraftVersion,
            String serverAddress
    ) {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String oidcToken = forgeOidcToken(keyPair, clientLogin.authData(), clientLogin.skinData(), publicKey);

        AuthPayload payload = new DualPayload(
                List.of(forgeAuthData(keyPair, clientLogin.authData())),
                oidcToken,
                AuthType.SELF_SIGNED
        );

        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(protocolVersion);
        login.setAuthPayload(payload);
        login.setClientJwt(forgeSkinData(keyPair, clientLogin.skinData(), clientLogin.authData(), protocolVersion, minecraftVersion, serverAddress));
        return login;
    }

    private static String forgeOidcToken(KeyPair keyPair, AuthData authData, JSONObject skinData, String publicKey) {
        long timestamp = System.currentTimeMillis();
        Date notBefore = new Date(timestamp - TimeUnit.HOURS.toMillis(6));
        Date expires = new Date(timestamp + TimeUnit.HOURS.toMillis(6));
        UUID legacyUuid = UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + authData.xuid()).getBytes(StandardCharsets.UTF_8));

        JwtClaims claims = new JwtClaims();
        claims.setNotBefore(NumericDate.fromMilliseconds(notBefore.getTime()));
        claims.setExpirationTime(NumericDate.fromMilliseconds(expires.getTime()));
        claims.setIssuedAt(NumericDate.fromMilliseconds(timestamp));
        // OIDC multiplayer-token claims expected by 1.26.10+ servers. The names come from
        // Microsoft's franchise multiplayer token (see gophertunnel's tokenClaims).
        claims.setClaim("cpk", publicKey);
        claims.setClaim("leguuid", legacyUuid.toString());
        claims.setClaim("mid", playFabId(skinData, authData.xuid()));
        claims.setClaim("nid", "");
        claims.setClaim("nname", "");
        claims.setClaim("pid", "");
        claims.setClaim("pname", "");
        claims.setClaim("xid", authData.xuid());
        claims.setClaim("xname", authData.displayName());
        claims.setClaim("identity", authData.identity().toString());
        claims.setClaim("ipt", "PlayFab");
        claims.setClaim("tid", "20CA2");

        JsonWebSignature signature = baseSignature(keyPair, publicKey);
        signature.setPayload(claims.toJson());
        return compact(signature);
    }

    private static String playFabId(JSONObject skinData, String xuid) {
        Object value = skinData == null ? null : skinData.get("PlayFabId");
        if (value instanceof String playFabId && !playFabId.isBlank()) {
            return playFabId;
        }
        try {
            return new BigInteger(xuid).toString(16).toUpperCase();
        } catch (NumberFormatException exception) {
            return Integer.toHexString(xuid.hashCode()).toUpperCase();
        }
    }

    public LoginPacket forge(
            KeyPair keyPair,
            ClientLogin clientLogin,
            int protocolVersion,
            String minecraftVersion,
            AuthType authType
    ) {
        return forge(keyPair, clientLogin, protocolVersion, minecraftVersion, null, authType);
    }

    private static String forgeAuthData(KeyPair keyPair, AuthData authData) {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis();
        Date notBefore = new Date(timestamp - TimeUnit.SECONDS.toMillis(1));
        Date expires = new Date(timestamp + TimeUnit.DAYS.toMillis(1));

        Map<String, Object> extraDataMap = new HashMap<>();
        extraDataMap.put("XUID", authData.xuid());
        extraDataMap.put("identity", authData.identity().toString());
        extraDataMap.put("displayName", authData.displayName());
        extraDataMap.put("titleId", "1739947436");
        extraDataMap.put("sandboxId", "RETAIL");

        JwtClaims claims = new JwtClaims();
        claims.setNotBefore(NumericDate.fromMilliseconds(notBefore.getTime()));
        claims.setExpirationTime(NumericDate.fromMilliseconds(expires.getTime()));
        claims.setIssuedAt(NumericDate.fromMilliseconds(timestamp));
        claims.setIssuer("Mojang");
        claims.setClaim("extraData", new JSONObject(extraDataMap));
        claims.setClaim("identityPublicKey", publicKey);
        claims.setClaim("randomNonce", ThreadLocalRandom.current().nextLong());

        JsonWebSignature signature = baseSignature(keyPair, publicKey);
        signature.setPayload(claims.toJson());
        return compact(signature);
    }

    private static String forgeSkinData(
            KeyPair keyPair,
            JSONObject skinData,
            AuthData authData,
            int protocolVersion,
            String minecraftVersion,
            String serverAddress
    ) {
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        JSONObject backendSkinData = backendSkinData(skinData, authData, protocolVersion, minecraftVersion, serverAddress);
        JsonWebSignature signature = baseSignature(keyPair, publicKey);
        signature.setPayload(backendSkinData.toJSONString());
        return compact(signature);
    }

    /**
     * A stable offline identity for the backend, derived from the player's XUID.
     *
     * <p>This is what makes a proxied player a <em>returning</em> player rather than a new one.
     * BDS 1.26.10+ takes identity from a PlayFab-signed OIDC token and refuses the self-signed one a
     * proxy can produce, so {@code trusted_player_info_.xuid} is blank for every proxied join — the
     * server logs {@code Player connected: <name>, xuid:} with nothing after it. Its fallback for an
     * untrusted login is {@code SelfSignedId} from the client data, and a 1.26.40 client sends that
     * field <b>empty</b>. Blank xuid plus blank SelfSignedId leaves the server with nothing to key
     * persistent storage on, so it creates a fresh player on every connect: spawn position, empty
     * inventory, default gamemode. Only state the proxy itself owns (the verifier plugin's operator
     * store) survives, which is exactly the split that shows up in practice.</p>
     *
     * <p>Derived from the XUID rather than random so it is identical on every join and across every
     * backend, which is the entire point — and deliberately namespaced so it cannot collide with a
     * genuine self-signed id from an unauthenticated client.</p>
     */
    private static String stableSelfSignedId(AuthData authData) {
        return UUID.nameUUIDFromBytes(
                ("endstone-proxy-self-signed:" + authData.xuid()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JSONObject backendSkinData(
            JSONObject skinData,
            AuthData authData,
            int protocolVersion,
            String minecraftVersion,
            String serverAddress
    ) {
        JSONObject backendSkinData = new JSONObject();
        backendSkinData.putAll(skinData);

        // Only fill it in when the client left it blank: a client that supplies its own self-signed
        // id is already telling the backend who it is, and overriding that would move the player's
        // data rather than find it.
        Object clientSelfSignedId = backendSkinData.get("SelfSignedId");
        if (!(clientSelfSignedId instanceof String id) || id.isBlank()) {
            backendSkinData.put("SelfSignedId", stableSelfSignedId(authData));
        }

        if (serverAddress != null && !serverAddress.isBlank()) {
            backendSkinData.put("ServerAddress", serverAddress);
        }
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            backendSkinData.put("GameVersion", minecraftVersion);
        } else if (protocolVersion == CanonicalProtocol.V1_21_130.protocolVersion()) {
            backendSkinData.put("GameVersion", CanonicalProtocol.V1_21_130.minecraftVersion());
        }
        if (protocolVersion == CanonicalProtocol.V1_21_130.protocolVersion()) {
            backendSkinData.put("CompatibleWithClientSideChunkGen", true);
        }

        return backendSkinData;
    }

    private static JsonWebSignature baseSignature(KeyPair keyPair, String publicKey) {
        JsonWebSignature signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        signature.setHeader(HeaderParameterNames.X509_URL, publicKey);
        signature.setKey(keyPair.getPrivate());
        return signature;
    }

    private static String compact(JsonWebSignature signature) {
        try {
            return signature.getCompactSerialization();
        } catch (JoseException exception) {
            throw new IllegalStateException("Unable to sign offline login data", exception);
        }
    }
}
