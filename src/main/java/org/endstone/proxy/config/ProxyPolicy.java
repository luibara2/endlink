package org.endstone.proxy.config;

/**
 * The policy half of the configuration — the settings that decide what the proxy <em>does</em>, as
 * opposed to the addresses and codecs it needs to run at all.
 *
 * <p>Bundled because the same records are needed together everywhere a connection is set up,
 * and threading them individually through {@code BackendConnector} into every packet handler adds a
 * parameter per feature.</p>
 */
public record ProxyPolicy(
        FailoverConfig failover,
        BackendSwitchConfig backendSwitch,
        PermissionsConfig permissions,
        SecurityConfig security,
        ForcedHostsConfig forcedHosts,
        JoinConfig join,
        CommandsConfig commands
) {
    public ProxyPolicy {
        if (failover == null) {
            throw new IllegalArgumentException("failover cannot be null");
        }
        if (backendSwitch == null) {
            throw new IllegalArgumentException("backendSwitch cannot be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("permissions cannot be null");
        }
        if (security == null) {
            throw new IllegalArgumentException("security cannot be null");
        }
        if (forcedHosts == null) {
            throw new IllegalArgumentException("forcedHosts cannot be null");
        }
        if (join == null) {
            throw new IllegalArgumentException("join cannot be null");
        }
        if (commands == null) {
            throw new IllegalArgumentException("commands cannot be null");
        }
    }

    public static ProxyPolicy defaults() {
        return new ProxyPolicy(
                FailoverConfig.disabled(),
                BackendSwitchConfig.defaults(),
                PermissionsConfig.defaults(),
                SecurityConfig.defaults(),
                ForcedHostsConfig.empty(),
                JoinConfig.defaults(),
                CommandsConfig.defaults()
        );
    }
}
