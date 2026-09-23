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
 * @param declaredRelease the exact Minecraft release the operator named in that same setting, or
 *                 null when they named a bare protocol number or left it on {@code auto}. Not the
 *                 same thing as {@code protocol}: one protocol number covers several releases
 *                 (2168 is 1.26.40 through 1.26.44) and they do not all share a wire format, so
 *                 the codec's own name for a protocol must never be read back as the release.
 *                 See {@link org.endstone.proxy.protocol.BedrockRelease}.
 * @param dropSubChunkRequests mark this backend as not serving sub-chunk requests, set with
 *                 {@code backend.<name>.dropSubChunkRequests=true}.
 *                 <p>A Bedrock client asks for terrain one sub-chunk at a time only because a server
 *                 told it to, and BDS does. That mode belongs to the client's session rather than to
 *                 one backend, so it survives a switch. A backend that sends whole chunks instead -
 *                 PowerNukkitX, Geyser - never answers the requests: the player waits on "Building
 *                 terrain", and Geyser drops them for a protocol violation. Such a backend is reached
 *                 by reconnect for a client in request mode, and the requests are not forwarded to it.
 *                 <p>Normally learned from the backend's first chunk and persisted, so this is only
 *                 needed for a backend nobody has visited since the cache was cleared. Off by default:
 *                 withholding the requests from a backend that does serve them leaves terrain unloaded.
 */
public record BackendConfig(
        String name,
        InetSocketAddress address,
        CanonicalProtocol protocol,
        String declaredRelease,
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

    public BackendConfig(String name, InetSocketAddress address, CanonicalProtocol protocol, boolean dropSubChunkRequests) {
        this(name, address, protocol, null, dropSubChunkRequests);
    }

    public BackendConfig(String name, InetSocketAddress address, CanonicalProtocol protocol) {
        this(name, address, protocol, null, false);
    }

    public BackendConfig(String name, InetSocketAddress address) {
        this(name, address, null, null, false);
    }
}
