package org.endstone.proxy.plugin;

/**
 * An addon dropped into {@code plugins/}.
 *
 * <p>Implement this, name the class in an {@code endlink-plugin.properties} at the root of your jar,
 * and Endlink will find and enable it at startup &mdash; no configuration, no flags. An empty or
 * missing {@code plugins/} directory means Endlink runs as a plain Bedrock proxy, with no branch
 * anywhere else in the code.</p>
 *
 * <pre>
 * endlink-plugin.properties:
 *   name=MyAddon
 *   main=com.example.myaddon.MyAddonPlugin
 * </pre>
 *
 * <p>{@code name} is also the addon's data folder under {@code plugins/}, so keep it filename-safe.</p>
 *
 * <p><b>A broken addon must never cost the proxy its Bedrock players.</b> Anything thrown from these
 * methods is logged and the addon is skipped; the proxy carries on without it.</p>
 */
public interface EndlinkPlugin {

    /**
     * Contribute to the proxy before anything is bound.
     *
     * <p>This is the only point at which protocol edges and listeners can be registered: the protocol
     * registry is built and the sockets are bound immediately afterwards. Do not dial anything here
     * &mdash; nothing is listening yet.</p>
     */
    void onEnable(PluginContext context) throws Exception;

    /**
     * Called once every listener is bound and accepting connections.
     *
     * <p>Where an addon starts anything that connects <em>into</em> the proxy. an addon that fronts the proxy launches its translator here, because that translator's first act is to dial the trusted listener it asked
     * for in {@link #onEnable}.</p>
     */
    default void onProxyReady() throws Exception {
    }

    /** Called on shutdown, in reverse enable order. Should not throw; failures are logged and ignored. */
    default void onDisable() {
    }
}
