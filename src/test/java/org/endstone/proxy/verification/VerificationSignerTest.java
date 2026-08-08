package org.endstone.proxy.verification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerificationSignerTest {
    @Test
    void signsAndVerifiesRequests() {
        VerificationSigner signer = new VerificationSigner("secret", fixedClock(), 30_000);
        VerificationRequest request = request(1_000_000);

        String signature = signer.sign(request);

        assertTrue(signer.verify(request, signature));
        assertFalse(signer.verify(request, "00" + signature.substring(2)));
    }

    @Test
    void rejectsStaleRequests() {
        VerificationSigner signer = new VerificationSigner("secret", fixedClock(), 30_000);

        assertTrue(signer.isFresh(1_000_000));
        assertFalse(signer.isFresh(900_000));
    }

    private static VerificationRequest request(long timestamp) {
        return new VerificationRequest("123", "00000000-0000-0000-0000-000000000001", "Steve", "127.0.0.1", timestamp, "abc");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC);
    }
}
