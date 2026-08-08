package org.endstone.proxy.verification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

public final class VerificationSigner {
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final Clock clock;
    private final long requestSkewMillis;

    public VerificationSigner(String secret, Clock clock, long requestSkewMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret cannot be blank");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock cannot be null");
        }
        if (requestSkewMillis < 1) {
            throw new IllegalArgumentException("requestSkewMillis must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.requestSkewMillis = requestSkewMillis;
    }

    public boolean isFresh(long timestampMillis) {
        long delta = Math.abs(clock.millis() - timestampMillis);
        return delta <= requestSkewMillis;
    }

    public String sign(VerificationRequest request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonicalPayload(request).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign backend verification request", exception);
        }
    }

    public boolean verify(VerificationRequest request, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected;
        byte[] actual;
        try {
            expected = HEX.parseHex(sign(request));
            actual = HEX.parseHex(signature.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    public static String canonicalPayload(VerificationRequest request) {
        return String.join(
                "\n",
                "v1",
                request.xuid(),
                request.uuid(),
                request.name(),
                request.address(),
                Long.toString(request.timestampMillis()),
                request.nonce()
        );
    }
}
