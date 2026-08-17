package org.endstone.proxy.config;

import org.endstone.proxy.protocol.CanonicalProtocol;

import java.net.InetSocketAddress;

/**
 * @param protocol the Minecraft version this backend runs, or null to fall back to the global
 *                 {@code backend.protocol}. Set it per backend with
 *                 {@code backend.<name>.protocol=1.26.40} when the fleet is mixed — which it always
 *                 is during an upgrade, since backends are moved one at a time. Without it the proxy
 *                 speaks the global version to every backend, and the ones already upgraded reject
 *                 the login as {@code LOGIN_FAILED_CLIENT_OLD}.
 * @param dropSubChunkRequests stop forwarding the client's {@code SubChunkRequestPacket} to this
 *                 backend, set with {@code backend.<name>.dropSubChunkRequests=true}.
 *                 <p>A Bedrock client asks for terrain one sub-chunk at a time only because a server
 *                 told it to, and BDS does. That mode belongs to the client's session rather than to
 *                 one backend, so it survives a switch — and the proxy's handoff is deliberately
 *                 seamless, so the client is never told the new server works differently. A backend
 *                 that does not implement the sub-chunk system then receives requests it never
 *                 advertised. Geyser treats them as a protocol violation and drops the player.
 *                 <p>Off by default: every Bedrock server implements this, and silently withholding
 *                 the requests from one that does would leave terrain unloaded. Turn it on only for
 *                 a backend that is not really a Bedrock server.
 */
public record BackendConfig(
        String name,
        InetSocketAddress address,
        CanonicalProtocol protocol,
        boolean dropSubChunkRequests
) {
    public BackendConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
    }

    public BackendConfig(String name, InetSocketAddress address, CanonicalProtocol protocol) {
        this(name, address, protocol, false);
    }

    public BackendConfig(String name, InetSocketAddress address) {
        this(name, address, null, false);
    }
}
