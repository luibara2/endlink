package org.endstone.proxy.auth;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.BeforeAll;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The login a bridged player actually arrives with.
 *
 * <p>a bridge addon has no Xbox account to sign with, so when it has no {@code AuthData} it mints its own
 * token: {@code xid} is {@code |FNV1(javaUsername)|}, {@code xname} is the bridged username, and the
 * identity UUID is the usual {@code pocket-auth-1-xuid:} derivation. The token built here is that
 * one, claim for claim, so this test fails if either side of that contract moves.</p>
 *
 * <p>The point being pinned is the boundary: the same login is accepted on the loopback bridge
 * listener and refused on the public one. Getting that backwards is an authentication bypass, not a
 * missing feature, so it is worth a test that states it in those terms.</p>
 */
class SelfSignedBridgeLoginTest {

    private static final String JAVA_USERNAME = "Notch";

    /**
     * CloudburstMC's {@code EncryptionUtils} fetches Mojang's discovery document over HTTPS in its
     * static initialiser and throws an {@link AssertionError} if it cannot, so every test here needs
     * working outbound TLS. Skip rather than fail where that is unavailable — an intercepted TLS chain
     * says nothing about whether the bridge's login policy is right.
     *
     * <p>Worth knowing beyond the tests: this is a hard runtime dependency of the proxy itself, and it
     * predates Java support. A proxy host that cannot reach that endpoint cannot authenticate anyone.</p>
     */
    @BeforeAll
    static void requireLoginValidator() {
        try {
            Class.forName("org.cloudburstmc.protocol.bedrock.util.EncryptionUtils");
        } catch (Throwable throwable) {
            assumeTrue(false, "EncryptionUtils could not initialise (needs outbound HTTPS to "
                    + "client.discovery.minecraft-services.net): " + throwable);
        }
    }

    @Test
    void theBridgeAcceptsTheAddonsSelfSignedLogin() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME);

        ClientLogin login = new ClientLoginAuthenticator(true, true).authenticate(packet);

        assertEquals(JAVA_USERNAME, login.authData().displayName());
        assertEquals(expectedXuid(JAVA_USERNAME), login.authData().xuid());
        assertEquals(
                UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + expectedXuid(JAVA_USERNAME)).getBytes(StandardCharsets.UTF_8)),
                login.authData().identity()
        );
    }

    @Test
    void thePublicListenerRefusesTheSameLogin() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ClientLoginAuthenticator(true).authenticate(packet));

        assertTrue(rootCause(exception).getMessage().contains("not Mojang-signed"),
                "an unsigned login must still be refused on the public port: accepting it there would "
                        + "let anyone on the internet join as anyone. Actual: " + rootCause(exception).getMessage());
    }

    /**
     * Two bridged players must not collapse into one identity. The XUID is derived from the username, so
     * this also pins that the derivation is per-player rather than per-session.
     */
    @Test
    void twoJavaPlayersGetDistinctStableIdentities() throws Exception {
        ClientLoginAuthenticator authenticator = new ClientLoginAuthenticator(true, true);

        ClientLogin first = authenticator.authenticate(viaBedrockStyleLogin("Alex"));
        ClientLogin second = authenticator.authenticate(viaBedrockStyleLogin("Steve"));
        ClientLogin firstAgain = authenticator.authenticate(viaBedrockStyleLogin("Alex"));

        assertTrue(!first.authData().xuid().equals(second.authData().xuid()),
                "distinct bridged players sharing an XUID would trip the duplicate-login check");
        assertEquals(first.authData().xuid(), firstAgain.authData().xuid(),
                "a reconnecting bridged player must keep the identity the permission store keys on");
    }

    // ---------------------------------------------------------------------------------------------
    // The bridge secret. Self-signed logins are accepted on the bridge listener, so without this the
    // only thing standing between "any process on this host" and "any identity on the server" is that
    // the port is loopback. The secret narrows that to the the bridge this proxy started.
    // ---------------------------------------------------------------------------------------------

    private static final String SECRET = "s3cr3t-bridge-token";

    @Test
    void aLoginWithoutTheBridgeSecretIsRefused() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME, null, null);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bridgeAuthenticator(SECRET, "").authenticate(packet));

        assertTrue(rootCause(exception).getMessage().contains("bridge secret"),
                "a self-signed login with no secret must be refused: it did not come from our the bridge. "
                        + "Actual: " + rootCause(exception).getMessage());
    }

    @Test
    void aLoginWithTheWrongBridgeSecretIsRefused() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME, "not-the-secret", "1.2.3.4");

        assertThrows(IllegalStateException.class,
                () -> bridgeAuthenticator(SECRET, "").authenticate(packet));
    }

    @Test
    void theRealClientAddressIsTakenFromTheLoginRatherThanTheSocket() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME, SECRET, "203.0.113.7");

        ClientLogin login = bridgeAuthenticator(SECRET, "").authenticate(packet);

        assertTrue(login.isJavaEdition());
        assertEquals("203.0.113.7", login.bridgeClientAddress().getAddress().getHostAddress(),
                "every bridged player shares one loopback socket address, so the real address has to come "
                        + "from the login or backends see 127.0.0.1 for all of them");
    }

    /**
     * The prefix is cosmetic and must stay that way: identity is derived before it is applied, so an
     * operator changing it does not orphan anyone's permissions or backend player data.
     */
    @Test
    void theNamePrefixChangesTheDisplayNameAndNothingElse() throws Exception {
        ClientLogin unprefixed = bridgeAuthenticator(SECRET, "")
                .authenticate(viaBedrockStyleLogin(JAVA_USERNAME, SECRET, "203.0.113.7"));
        ClientLogin prefixed = bridgeAuthenticator(SECRET, "*")
                .authenticate(viaBedrockStyleLogin(JAVA_USERNAME, SECRET, "203.0.113.7"));

        assertEquals("*" + JAVA_USERNAME, prefixed.authData().displayName());
        assertEquals(unprefixed.authData().xuid(), prefixed.authData().xuid());
        assertEquals(unprefixed.authData().identity(), prefixed.authData().identity());
    }

    /**
     * The secret is a bridge concept. It must never become a way onto the public listener, which still
     * demands a Mojang-signed chain no matter what claims a token carries.
     */
    @Test
    void thePublicListenerIgnoresTheBridgeSecretEntirely() throws Exception {
        LoginPacket packet = viaBedrockStyleLogin(JAVA_USERNAME, SECRET, "203.0.113.7");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ClientLoginAuthenticator(true).authenticate(packet));

        assertTrue(rootCause(exception).getMessage().contains("not Mojang-signed"),
                rootCause(exception).getMessage());
    }

    private static ClientLoginAuthenticator bridgeAuthenticator(String secret, String prefix) {
        return new ClientLoginAuthenticator(true, true, secret, prefix);
    }

    /** Exactly what {@code LoginPackets.java} builds when a bridge addon has no account configured. */
    private static LoginPacket viaBedrockStyleLogin(String javaUsername) throws Exception {
        return viaBedrockStyleLogin(javaUsername, null, null);
    }

    private static LoginPacket viaBedrockStyleLogin(String javaUsername, String secret, String clientIp)
            throws Exception {
        KeyPair keyPair = ecdsa384KeyPair();
        String encodedPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String xuid = expectedXuid(javaUsername);

        JwtClaims claims = new JwtClaims();
        claims.setAudience("api://auth-minecraft-services/multiplayer");
        claims.setClaim("cpk", encodedPublicKey);
        claims.setClaim("leguuid", UUID.nameUUIDFromBytes(
                ("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8)).toString());
        claims.setClaim("mid", Long.toHexString(fnv1_64(javaUsername)).toUpperCase(java.util.Locale.ROOT));
        claims.setClaim("xid", xuid);
        claims.setClaim("xname", javaUsername);
        // The claims the patched the bridge adds when it is embedded in this proxy.
        if (secret != null) {
            claims.setClaim("ep_secret", secret);
        }
        if (clientIp != null) {
            claims.setClaim("ep_ip", clientIp);
            claims.setClaim("ep_port", 52255);
        }
        claims.setIssuedAt(NumericDate.now());
        claims.setExpirationTimeMinutesInTheFuture(60);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader("x5u", encodedPublicKey);

        LoginPacket packet = new LoginPacket();
        packet.setProtocolVersion(1001);
        packet.setAuthPayload(new TokenPayload(jws.getCompactSerialization(), AuthType.SELF_SIGNED));
        packet.setClientJwt(clientDataJwt(keyPair));
        return packet;
    }

    private static String clientDataJwt(KeyPair keyPair) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("DeviceOS", 7);
        claims.setClaim("GameVersion", "1.26.30");

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader("x5u", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return jws.getCompactSerialization();
    }

    private static KeyPair ecdsa384KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static String expectedXuid(String javaUsername) {
        return String.valueOf(Math.abs(fnv1_64(javaUsername)));
    }

    /** a bridge addon's {@code FNV1.fnv1_64}, reproduced so the expected XUID is not just asserted against itself. */
    private static long fnv1_64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash *= 0x100000001b3L;
            hash ^= (b & 0xffL);
        }
        return hash;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
