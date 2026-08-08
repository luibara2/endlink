package org.endstone.proxy.plugin;

import org.endstone.proxy.config.ProxyConfig;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.PacketTranslator;

import java.nio.file.Path;

/**
 * What an addon is handed during {@link EndlinkPlugin#onEnable}.
 *
 * <p>Deliberately small. This is the API one addon needs, not a general plugin platform; it can grow
 * when a second addon needs something. Every method is only meaningful during {@code onEnable} —
 * contributions are collected once and then the registry is built and the listeners are bound.</p>
 */
public interface PluginContext {

    /**
     * {@code plugins/<name>/}, created before the addon is enabled. The addon's config, caches and
     * anything else it writes belong here and nowhere else.
     */
    Path dataFolder();

    /** The proxy's own configuration, so an addon can align with it (ports it must not collide with, and so on). */
    ProxyConfig proxyConfig();

    /** Prints through the proxy's console, prefixed with the addon name. */
    void info(String message);

    /**
     * Teaches the protocol registry that a client on {@code older} can reach a backend on
     * {@code newer}.
     *
     * <p>The proxy's own registry only ever goes newer&rarr;older, because a Bedrock client is never
     * behind its server for long. An addon that puts a translator in front of the proxy inverts that:
     * an addon's translator may speak an older Bedrock version to newer backends.</p>
     *
     * <p>Note this also changes which <em>Bedrock</em> clients can join, since the edge is a property
     * of the graph rather than of one connection. That is the operator's business, so an addon adding
     * one should say so in its own documentation.</p>
     */
    void addProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator);

    /** Asks the proxy to bind an extra loopback listener for this addon. See {@link TrustedListenerSpec}. */
    void addTrustedListener(TrustedListenerSpec spec);
}
