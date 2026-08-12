package org.endstone.proxy.verification;

public record VerificationRequest(
        String xuid,
        String uuid,
        String name,
        String address,
        long timestampMillis,
        String nonce
) {
    public VerificationRequest {
        // XUID and UUID may be blank for backends that don't trust the self-signed OIDC
        // claims (BDS 1.26.10+ in offline mode reports player.xuid as "" and may not
        // surface the OIDC `identity` claim as player.unique_id). Identity verification
        // matches on the display name. Both XUID and UUID stay in the signed payload for
        // signature compatibility, and UUID is cross-checked when both sides have it.
        xuid = requireNoNewline(nullableTrim(xuid), "xuid");
        uuid = requireNoNewline(nullableTrim(uuid), "uuid");
        name = requireNoNewline(requireNonBlank(name, "name"), "name");
        address = requireNoNewline(requireNonBlank(address, "address"), "address");
        nonce = requireNoNewline(requireNonBlank(nonce, "nonce"), "nonce");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String nullableTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * {@link VerificationSigner#canonicalPayload} joins these fields with {@code \n}. A field that
     * may itself contain {@code \n} makes that encoding ambiguous: {@code name="a"},
     * {@code address="b\nc"} and {@code name="a\nb"}, {@code address="c"} canonicalise to the same
     * string, so one HMAC would be valid for two different requests. No legitimate value contains a
     * newline — display names come from the Xbox-signed chain and addresses are literals — so
     * rejecting one costs nothing and removes the ambiguity at the source rather than relying on
     * every caller to check.
     */
    private static String requireNoNewline(String value, String name) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " cannot contain a line break");
        }
        return value;
    }
}
