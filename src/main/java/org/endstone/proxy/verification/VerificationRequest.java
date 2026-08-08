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
        xuid = nullableTrim(xuid);
        uuid = nullableTrim(uuid);
        name = requireNonBlank(name, "name");
        address = requireNonBlank(address, "address");
        nonce = requireNonBlank(nonce, "nonce");
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
}
