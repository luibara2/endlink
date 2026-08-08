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
 */
public record BackendConfig(String name, InetSocketAddress address, CanonicalProtocol protocol) {
    public BackendConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
    }

    public BackendConfig(String name, InetSocketAddress address) {
        this(name, address, null);
    }
}
