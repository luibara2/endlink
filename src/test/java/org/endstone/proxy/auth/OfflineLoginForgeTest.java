package org.endstone.proxy.auth;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.DualPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OfflineLoginForgeTest {
    @Test
    void forgesSelfSignedProtocol898Login() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("DeviceOS", 1);
        skinData.put("GameVersion", "1.26.20");
        skinData.put("CompatibleWithClientSideChunkGen", true);
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Steve", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forge(keyPair, clientLogin);

        assertEquals(898, packet.getProtocolVersion());
        CertificateChainPayload payload = assertInstanceOf(CertificateChainPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.SELF_SIGNED, payload.getAuthType());
        assertEquals(1, payload.getChain().size());

        JSONObject backendSkinData = skinData(packet);
        assertEquals("1.21.130", backendSkinData.get("GameVersion"));
        assertEquals(true, backendSkinData.get("CompatibleWithClientSideChunkGen"));
    }

    @Test
    void forgesProtocol944LoginWithoutChangingClientGameVersion() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("DeviceOS", 1);
        skinData.put("GameVersion", "1.26.20");
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Alex", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forge(keyPair, clientLogin, 944);

        assertEquals(944, packet.getProtocolVersion());
        CertificateChainPayload payload = assertInstanceOf(CertificateChainPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.SELF_SIGNED, payload.getAuthType());
        JSONObject backendSkinData = skinData(packet);
        assertEquals("1.26.20", backendSkinData.get("GameVersion"));
    }

    @Test
    void canForgeFullAuthWithBackendGameVersionForModernBackends() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("DeviceOS", 1);
        skinData.put("GameVersion", "1.26.20");
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Alex", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forge(keyPair, clientLogin, 944, "1.26.12", AuthType.FULL);

        CertificateChainPayload payload = assertInstanceOf(CertificateChainPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.FULL, payload.getAuthType());
        assertEquals("1.26.12", skinData(packet).get("GameVersion"));
    }

    @Test
    void rewritesServerAddressForBackendDiscovery() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("ServerAddress", "proxy.example.com:19132");
        skinData.put("GameVersion", "1.26.20");
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Alex", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forge(
                keyPair,
                clientLogin,
                944,
                "1.26.12",
                "backend.example.com:19133",
                AuthType.SELF_SIGNED
        );

        JSONObject backendSkinData = skinData(packet);
        assertEquals("backend.example.com:19133", backendSkinData.get("ServerAddress"));
        assertEquals("1.26.12", backendSkinData.get("GameVersion"));
    }

    @Test
    void canForgeModernFullAuthWithBackendDiscoveryAddress() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("ServerAddress", "proxy.example.com:19132");
        skinData.put("GameVersion", "1.26.20");
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Alex", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forge(
                keyPair,
                clientLogin,
                944,
                "1.26.12",
                "backend.example.com:19133",
                AuthType.FULL
        );

        CertificateChainPayload payload = assertInstanceOf(CertificateChainPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.FULL, payload.getAuthType());
        assertEquals("backend.example.com:19133", skinData(packet).get("ServerAddress"));
    }

    @Test
    void canForgeModernSelfSignedTokenPayloadWithBackendDiscoveryAddress() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("ServerAddress", "proxy.example.com:19132");
        skinData.put("GameVersion", "1.26.20");
        ClientLogin clientLogin = new ClientLogin(
                new AuthData("Alex", UUID.randomUUID(), "1234"),
                skinData,
                keyPair.getPublic()
        );

        LoginPacket packet = new OfflineLoginForge().forgeTokenPayload(
                keyPair,
                clientLogin,
                944,
                "1.26.12",
                "backend.example.com:19133"
        );

        TokenPayload payload = assertInstanceOf(TokenPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.SELF_SIGNED, payload.getAuthType());
        assertEquals("backend.example.com:19133", skinData(packet).get("ServerAddress"));
    }

    @Test
    void forgesModernOidcLoginWithForwardedIdentityClaims() throws Exception {
        KeyPair keyPair = BedrockCrypto.createKeyPair();
        JSONObject skinData = new JSONObject();
        skinData.put("ServerAddress", "proxy.example.com:19132");
        skinData.put("GameVersion", "1.26.20");
        skinData.put("PlayFabId", "ABCDEF1234567890");
        AuthData authData = new AuthData("Alex", UUID.randomUUID(), "2535465250757019");
        ClientLogin clientLogin = new ClientLogin(authData, skinData, keyPair.getPublic());

        LoginPacket packet = new OfflineLoginForge().forgeOidcLogin(
                keyPair,
                clientLogin,
                975,
                "1.26.20",
                "backend.example.com:19133"
        );

        DualPayload payload = assertInstanceOf(DualPayload.class, packet.getAuthPayload());
        assertEquals(AuthType.SELF_SIGNED, payload.getAuthType());
        assertEquals(1, payload.getChain().size());
        JSONObject legacyClaims = jwtPayload(payload.getChain().get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> extraData = (Map<String, Object>) legacyClaims.get("extraData");
        assertEquals("2535465250757019", extraData.get("XUID"));
        assertEquals("Alex", extraData.get("displayName"));

        JSONObject claims = jwtPayload(payload.getToken());
        assertEquals("2535465250757019", claims.get("xid"));
        assertEquals("Alex", claims.get("xname"));
        assertEquals("ABCDEF1234567890", claims.get("mid"));
        assertEquals("PlayFab", claims.get("ipt"));
        assertEquals("20CA2", claims.get("tid"));
        assertEquals("backend.example.com:19133", skinData(packet).get("ServerAddress"));
    }

    private static JSONObject skinData(LoginPacket packet) throws Exception {
        JsonWebSignature clientJwt = new JsonWebSignature();
        clientJwt.setCompactSerialization(packet.getClientJwt());
        return new JSONObject(JsonUtil.parseJson(clientJwt.getUnverifiedPayload()));
    }

    private static JSONObject jwtPayload(String jwt) throws Exception {
        JsonWebSignature signature = new JsonWebSignature();
        signature.setCompactSerialization(jwt);
        return new JSONObject(JsonUtil.parseJson(signature.getUnverifiedPayload()));
    }
}
