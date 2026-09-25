package org.endstone.proxy.auth;

/**
 * Bounds on the client-data JWT — the one carrying the skin — checked before any part of it is
 * parsed as JSON.
 *
 * <p>The login batch may be up to {@code bedrock.maxPreAuthLoginBytes} (5 MiB) so that custom skins
 * with geometry fit, and nearly all of that is this JWT. Its size is fine: a real skin is a handful
 * of very long base64 strings, which parse to about their own size. What is not fine is the same
 * number of bytes spent on structure — millions of tiny nested objects parse at ~79 bytes of heap per
 * source byte. So the size is left alone and the <em>shape</em> is bounded instead:</p>
 *
 * <ul>
 *   <li>the JWS header is parsed by {@code setCompactSerialization} before the signature is checked,
 *       so it is held to a size a real header (an algorithm and an x5u key) never approaches;</li>
 *   <li>the payload is only parsed after its signature verifies, but a self-generated key signs
 *       anything, so its token count and nesting depth are bounded first.</li>
 * </ul>
 */
final class ClientDataLimits {

    /** A real header is ~250 bytes; {@code -Dbedrock.maxClientDataHeaderBytes}. */
    static final int MAX_HEADER_BYTES = Integer.getInteger("bedrock.maxClientDataHeaderBytes", 8 * 1024);

    /**
     * Structural tokens ({@code { [ , :}) the payload may hold; {@code -Dbedrock.maxClientDataTokens}.
     * A real skin payload, persona pieces and all, is a few thousand.
     */
    static final int MAX_TOKENS = Integer.getInteger("bedrock.maxClientDataTokens", 65_536);

    /** Nesting depth; a real payload is four or five deep. {@code -Dbedrock.maxClientDataDepth}. */
    static final int MAX_DEPTH = Integer.getInteger("bedrock.maxClientDataDepth", 32);

    private ClientDataLimits() {
    }

    static void checkHeader(String compactSerialization) {
        int dot = compactSerialization.indexOf('.');
        int headerLength = dot < 0 ? compactSerialization.length() : dot;
        if (headerLength > MAX_HEADER_BYTES) {
            throw new IllegalArgumentException("Client data JWT header of " + headerLength
                    + " bytes exceeds the maximum of " + MAX_HEADER_BYTES + " bytes");
        }
    }

    /** One linear pass, no allocation: counts structure outside string literals. */
    static void checkPayloadShape(String json) {
        int tokens = 0;
        int depth = 0;
        boolean inString = false;
        for (int i = 0, length = json.length(); i < length; i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    if (++depth > MAX_DEPTH) {
                        throw new IllegalArgumentException(
                                "Client data JSON nests deeper than " + MAX_DEPTH + " levels");
                    }
                    tokens++;
                }
                case '}', ']' -> depth--;
                case ',', ':' -> tokens++;
                default -> {
                    continue;
                }
            }
            if (tokens > MAX_TOKENS) {
                throw new IllegalArgumentException(
                        "Client data JSON holds more than " + MAX_TOKENS + " structural tokens");
            }
        }
    }
}
