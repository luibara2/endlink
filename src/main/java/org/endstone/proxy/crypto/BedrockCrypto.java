package org.endstone.proxy.crypto;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class BedrockCrypto {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private BedrockCrypto() {
    }

    public static KeyPair createKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException exception) {
            throw new IllegalStateException("Unable to create Bedrock EC key pair", exception);
        }
    }

    public static ECPublicKey parseKey(String base64) {
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("Unable to parse Bedrock EC public key", exception);
        }
    }

    public static byte[] randomToken() {
        byte[] token = new byte[16];
        SECURE_RANDOM.nextBytes(token);
        return token;
    }

    public static SecretKey secretKey(PrivateKey localPrivateKey, PublicKey remotePublicKey, byte[] token) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(localPrivateKey);
            agreement.doPhase(remotePublicKey, true);
            byte[] sharedSecret = agreement.generateSecret();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(token);
            digest.update(sharedSecret);
            return new SecretKeySpec(digest.digest(), "AES");
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to create Bedrock encryption key", exception);
        }
    }

    public static String handshakeJwt(KeyPair keyPair, byte[] token) {
        JsonWebSignature signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        signature.setHeader(
                HeaderParameterNames.X509_URL,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
        );
        signature.setKey(keyPair.getPrivate());

        JwtClaims claims = new JwtClaims();
        claims.setClaim("salt", Base64.getEncoder().encodeToString(token));
        signature.setPayload(claims.toJson());

        try {
            return signature.getCompactSerialization();
        } catch (JoseException exception) {
            throw new IllegalStateException("Unable to create Bedrock handshake JWT", exception);
        }
    }
}
