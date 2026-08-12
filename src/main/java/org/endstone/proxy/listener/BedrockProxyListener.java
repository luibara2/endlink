package org.endstone.proxy.listener;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.endstone.proxy.auth.ClientLoginAuthenticator;
import org.endstone.proxy.auth.OfflineLoginForge;
import org.endstone.proxy.backend.BackendDirectory;
import org.endstone.proxy.backend.BackendConnector;
import org.endstone.proxy.command.NetworkCommands;
import org.endstone.proxy.command.ProxyCommandRegistry;
import org.endstone.proxy.command.ProxyConsole;
import org.endstone.proxy.command.ProxyPlayerEnum;
import org.endstone.proxy.permission.ProxyPermissions;
import org.endstone.proxy.plugin.PluginManager;
import org.endstone.proxy.plugin.TrustedListenerSpec;
import org.endstone.proxy.config.ProxyConfig;
import org.endstone.proxy.config.SecurityConfig;
import org.endstone.proxy.network.LoggingExceptionHandler;
import org.endstone.proxy.security.ConnectionThrottle;
import org.endstone.proxy.security.PreAuthBatchLimiter;
import org.endstone.proxy.protocol.ProtocolNegotiator;
import org.endstone.proxy.protocol.ProtocolRegistry;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.endstone.proxy.session.ConnectedPlayerRegistry;
import org.endstone.proxy.verification.BackendVerificationServer;
import org.endstone.proxy.verification.PendingJoinRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

public final class BedrockProxyListener {
    private final ProxyConfig config;
    private final ProtocolRegistry protocolRegistry;
    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    private final ConcurrentHashMap.KeySetView<ListenerSession, Boolean> sessions = ConcurrentHashMap.newKeySet();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final PendingJoinRegistry pendingJoins;
    private final BackendVerificationServer backendVerificationServer;
    private final ProxyCommandRegistry commandRegistry = ProxyCommandRegistry.defaults();
    private final long serverId = ThreadLocalRandom.current().nextLong();
    private final ConnectedPlayerRegistry connectedPlayers;
    private final ConnectionThrottle connectionThrottle;
    private final ProxyPlayerEnum playerEnum;
    private final ProxyPermissions permissions;
    private ProxyConsole console;
    private Channel channel;
    private final java.util.List<Channel> trustedChannels = new java.util.ArrayList<>();
    private final PluginManager pluginManager;

    public BedrockProxyListener(ProxyConfig config) {
        this(config, ProtocolRegistry.createDefault());
    }

    public BedrockProxyListener(ProxyConfig config, ProtocolRegistry protocolRegistry) {
        this(config, protocolRegistry, ProxyPermissions.inMemory(config.permissions()));
    }

    public BedrockProxyListener(ProxyConfig config, ProxyPermissions permissions) {
        this(config, ProtocolRegistry.createDefault(), permissions);
    }

    /**
     * The constructor {@code Endlink.main} uses.
     *
     * <p>The registry is built from the addons' contributions rather than from a flag, which is what
     * lets the proxy know nothing about a bridged edition. Every constructor above builds a plain default
     * registry; only this one can gain edges, and only from an addon that asked for them.</p>
     */
    public BedrockProxyListener(ProxyConfig config, ProxyPermissions permissions, PluginManager pluginManager) {
        this(config, registryWith(pluginManager), permissions, pluginManager);
    }

    private static ProtocolRegistry registryWith(PluginManager pluginManager) {
        ProtocolRegistry.Builder builder = ProtocolRegistry.defaultBuilder();
        if (pluginManager != null) {
            for (PluginManager.ProtocolUpgrade upgrade : pluginManager.protocolUpgrades()) {
                builder.upgradeEdge(upgrade.older(), upgrade.newer(), upgrade.translator());
            }
        }
        return builder.build();
    }

    public BedrockProxyListener(
            ProxyConfig config,
            ProtocolRegistry protocolRegistry,
            ProxyPermissions permissions
    ) {
        this(config, protocolRegistry, permissions, null);
    }

    public BedrockProxyListener(
            ProxyConfig config,
            ProtocolRegistry protocolRegistry,
            ProxyPermissions permissions,
            PluginManager pluginManager
    ) {
        this.pluginManager = pluginManager;
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        if (protocolRegistry == null) {
            throw new IllegalArgumentException("protocolRegistry cannot be null");
        }
        this.config = config;
        this.protocolRegistry = protocolRegistry;
        this.permissions = permissions == null ? ProxyPermissions.inMemory(config.permissions()) : permissions;
        this.connectedPlayers = new ConnectedPlayerRegistry(config.maxPlayers());
        this.connectionThrottle = new ConnectionThrottle(config.security());
        this.playerEnum = new ProxyPlayerEnum(connectedPlayers, this.permissions);
        this.pendingJoins = new PendingJoinRegistry(Clock.systemUTC(), config.backendVerification().pendingJoinTtlMillis());
        this.backendVerificationServer = new BackendVerificationServer(
                config.backendVerification(),
                pendingJoins,
                Clock.systemUTC()
        );
    }

    public void start() throws IOException {
        InetSocketAddress listen = config.listenAddress();
        backendVerificationServer.start();
        BackendDirectory backendDirectory = new BackendDirectory(
                config.backends(),
                config.backend().name(),
                config.hubBackendName()
        );
        ProxyResourcePackRegistry resourcePackRegistry = ProxyResourcePackRegistry.load(config.resourcePacksDir());
        if (!resourcePackRegistry.isEmpty()) {
            System.out.printf("Proxy resource pack registry: %d pack(s) loaded.%n",
                    resourcePackRegistry.packs().size());
        }
        OfflineLoginForge offlineLoginForge = new OfflineLoginForge();
        BackendConnector backendConnector = new BackendConnector(
                eventLoopGroup,
                backendDirectory,
                commandRegistry,
                pendingJoins,
                protocolRegistry,
                config.backendProtocol(),
                config.backendVerification().enabled(),
                offlineLoginForge,
                connectedPlayers::xuidByName,
                config.policy(),
                connectedPlayers,
                permissions,
                playerEnum
        );
        NetworkCommands networkCommands = new NetworkCommands(
                connectedPlayers,
                backendDirectory,
                backendConnector.switcher(),
                permissions,
                commandRegistry,
                playerEnum::broadcast
        );
        console = new ProxyConsole(networkCommands, this::stop);
        backendConnector.setNetworkCommands(networkCommands);
        SecurityConfig security = config.security();
        ChannelFuture future = bindListener(
                listen,
                null,
                backendConnector,
                offlineLoginForge,
                resourcePackRegistry,
                security
        );

        if (!future.isSuccess()) {
            backendVerificationServer.close();
            eventLoopGroup.shutdownGracefully();
            throw new IOException("Failed to bind Bedrock proxy listener to " + listen, future.cause());
        }

        channel = future.channel();
        bindTrustedListeners(backendConnector, offlineLoginForge, resourcePackRegistry, security);
        // The limiter used to be removed here unconditionally, which left the unconnected-ping path
        // — a classic UDP amplification target — unmetered on a public address. It stays installed
        // unless someone deliberately turns it off.
        //
        // packetLimit=0 has to remove it rather than just set the limit: RakNet installs the
        // handler from its own default before this option is applied, and the handler blocks an
        // address as soon as its count exceeds the limit — so a limit of zero blocks everyone on
        // their first datagram. Removing it drops the global limit with it; they share a handler.
        boolean rateLimiting = security.rateLimitEnabled() && security.packetLimit() > 0;
        if (!rateLimiting && channel.pipeline().get(RakServerRateLimiter.NAME) != null) {
            channel.pipeline().remove(RakServerRateLimiter.NAME);
            System.out.printf(
                    "WARNING: RakNet server packet rate limiter disabled (security.rateLimit.enabled=%s,"
                            + " security.rateLimit.packetLimit=%d). The proxy is exposed to UDP floods.%n",
                    security.rateLimitEnabled(),
                    security.packetLimit()
            );
        }
        System.out.printf(
                "Security: rateLimiter=%s packetLimit=%d globalPacketLimit=%d connectionCookie=%s "
                        + "maxConnectionsPerAddress=%d "
                        + "maxConnectionAttempts=%d/%dms requireXuid=%s commandCooldownMillis=%d.%n",
                rateLimiting ? "on" : "OFF",
                security.packetLimit(),
                security.globalPacketLimit(),
                security.sendConnectionCookie() ? "on" : "OFF",
                security.maxConnectionsPerAddress(),
                security.maxConnectionAttempts(),
                security.connectionAttemptWindowMillis(),
                security.requireXuid(),
                security.commandCooldownMillis()
        );
        // A diagnostic you cannot tell is running is not a diagnostic. A capture was once taken to
        // answer whether the outbound verifier found anything, and the answer — no output — was
        // indistinguishable from "the flag was never passed", so the run proved nothing and had to be
        // repeated. Print the posture whether or not anything is enabled.
        System.out.printf(
                "Diagnostics: verifyReencode=%s verifyEncode=%s%s strictEncode=%s maxBatchBytes=%d traceBatches=%s logPackets=%s traceMillis=%d forceChunkRadius=%d %s %s.%n",
                Boolean.getBoolean("bedrock.verifyReencode") ? "on" : "off",
                Boolean.getBoolean("bedrock.verifyEncode") ? "on" : "off",
                Boolean.getBoolean("bedrock.verifyEncode")
                        ? " (maxBytes=" + Integer.getInteger("bedrock.verifyEncodeMaxBytes", 65536) + ")"
                        : "",
                Boolean.getBoolean("bedrock.strictEncode") ? "on" : "off",
                Integer.getInteger("bedrock.maxBatchBytes", 0),
                Boolean.getBoolean("bedrock.traceBatches") ? "on" : "off",
                org.endstone.proxy.backend.ProxyConnection.isContinuousPacketTracingConfigured() ? "on" : "off",
                org.endstone.proxy.backend.ProxyConnection.configuredPacketTraceMillis(),
                Integer.getInteger("proxy.forceChunkRadius", 0),
                // Both were previously announced only from BackendRelayPacketHandler's static
                // initialiser, which does not run until the first backend connects and prints nothing
                // when the set is empty — so a run with a mistyped flag looked exactly like a control.
                org.endstone.proxy.backend.BackendRelayPacketHandler.diagnosticSuppressionSummary(),
                org.endstone.proxy.backend.ClientRelayPacketHandler.movementSampleSummary()
        );
        if (config.permissions().admins().isEmpty()) {
            System.out.println("No proxy administrators configured; /"
                    + String.join(", /", new java.util.TreeSet<>(config.permissions().adminCommands()))
                    + " are unavailable to everyone. Set permissions.admins to your XUID to use them.");
        }
        if (!config.forcedHosts().isEmpty()) {
            config.forcedHosts().byHostname().forEach((hostname, backend) ->
                    System.out.printf("Forced host %s -> backend %s.%n", hostname, backend));
        }
        console.start();
        System.out.printf(
                "Endlink listening on %s:%d as '%s' for Bedrock %s (protocol %d), backend protocol %s. Backend placeholder: %s %s.%n",
                listen.getHostString(),
                listen.getPort(),
                config.motd(),
                protocolRegistry.advertisedClientCodec().getMinecraftVersion(),
                protocolRegistry.advertisedClientCodec().getProtocolVersion(),
                backendProtocolDescription(),
                config.backend().name(),
                config.backend().address()
        );
        // Everything is bound, so an addon that needs to connect into the proxy can now do so. This is
        // deliberately the last thing start() does.
        if (pluginManager != null) {
            pluginManager.proxyReady();
        }
    }

    /**
     * Binds one RakNet listener.
     *
     * @param trusted null for the public listener, or the addon's spec for a loopback one. Three
     *                things differ for a trusted listener, and all three matter.
     *                <p>Self-signed logins are accepted, because a translator has no Xbox account to
     *                sign with. That is an authentication bypass, survivable only because the spec
     *                refuses to bind anything but loopback and because the login must carry the
     *                addon's secret — see {@link ClientLoginAuthenticator}.</p>
     *                <p>The per-address throttle is skipped. It counts by IP, and every player from an
     *                addon arrives from 127.0.0.1, so leaving it on would cap that whole edition at
     *                {@code security.maxConnectionsPerAddress} and present as "the sixth bridged player
     *                cannot join" long after anyone remembers why.</p>
     *                <p>So is the RakNet packet limit, for the same reason and more sharply: a single
     *                Bedrock login is a burst of MTU-sized fragments that can spend the budget alone.</p>
     */
    private ChannelFuture bindListener(
            InetSocketAddress address,
            TrustedListenerSpec trusted,
            BackendConnector backendConnector,
            OfflineLoginForge offlineLoginForge,
            ProxyResourcePackRegistry resourcePackRegistry,
            SecurityConfig security
    ) {
        return new ServerBootstrap()
                .group(eventLoopGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement(trusted).toByteBuf())
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, config.maxPlayers())
                .option(RakChannelOption.RAK_PACKET_LIMIT,
                        trusted != null ? Integer.MAX_VALUE : security.packetLimit())
                .option(RakChannelOption.RAK_GLOBAL_PACKET_LIMIT,
                        trusted != null ? Integer.MAX_VALUE : security.globalPacketLimit())
                // Off by default in this RakNet build. With it on, the handshake proves the client
                // can receive at its claimed address, which is what makes a spoofed source IP
                // useless for opening sessions.
                .option(RakChannelOption.RAK_SEND_COOKIE, security.sendConnectionCookie())
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    public ListenerSession createSession0(BedrockPeer peer, int subClientId) {
                        return new ListenerSession(peer, subClientId, BedrockProxyListener.this::onSessionClosed);
                    }

                    @Override
                    protected void initSession(org.cloudburstmc.protocol.bedrock.BedrockServerSession session) {
                        ListenerSession listenerSession = (ListenerSession) session;
                        // Before anything else: RAK_MAX_CONNECTIONS is one pool shared by every
                        // address, so an unthrottled host can hold all of it. Closing the channel
                        // rather than sending a disconnect is deliberate — no codec has been
                        // negotiated yet, so there is nothing to encode a kick message with.
                        if (trusted == null && !connectionThrottle.accept(listenerSession.getSocketAddress())) {
                            listenerSession.getPeer().getChannel().close();
                            return;
                        }
                        listenerSession.setThrottled(trusted == null);
                        // Bound what an anonymous peer can make the proxy allocate. Sits between the
                        // compression codec and the batch decoder so it sees the decompressed size,
                        // which is the number every downstream consumer scales its work off. Lifts
                        // as soon as the login succeeds and setProxyConnection runs.
                        listenerSession.getPeer().getChannel().pipeline().addBefore(
                                BedrockBatchDecoder.NAME,
                                PreAuthBatchLimiter.NAME,
                                new PreAuthBatchLimiter(() -> listenerSession.proxyConnection() != null)
                        );
                        listenerSession.getPeer().getChannel().pipeline().addLast(
                                "endstone-client-exception-logger",
                                new LoggingExceptionHandler("client")
                        );
                        sessions.add(listenerSession);
                        listenerSession.setPacketHandler(new InitialClientPacketHandler(
                                listenerSession,
                                new org.endstone.proxy.network.NetworkSettingsNegotiator(
                                        new ProtocolNegotiator(protocolRegistry),
                                        config.compressionAlgorithm(),
                                        config.compressionThreshold()
                                ),
                                backendConnector,
                                new ClientLoginAuthenticator(
                                        security.requireXuid(),
                                        trusted != null,
                                        trusted != null ? trusted.loginSecret() : null,
                                        trusted != null ? trusted.namePrefix() : ""
                                ),
                                offlineLoginForge,
                                connectedPlayers,
                                BedrockProxyListener.this::onPlayerRosterChanged,
                                resourcePackRegistry
                        ));
                        updateAdvertisement();
                    }
                })
                .bind(address)
                .awaitUninterruptibly();
    }

    /**
     * Binds the loopback listeners the addons asked for.
     *
     * <p>Every failure here is logged and swallowed. Addons are optional and secondary; a Bedrock
     * server losing its Bedrock players because an optional addon would not bind is a far worse
     * outcome than that addon's players not being able to join.</p>
     */
    private void bindTrustedListeners(
            BackendConnector backendConnector,
            OfflineLoginForge offlineLoginForge,
            ProxyResourcePackRegistry resourcePackRegistry,
            SecurityConfig security
    ) {
        if (pluginManager == null) {
            return;
        }
        for (TrustedListenerSpec spec : pluginManager.trustedListeners()) {
            ChannelFuture future = bindListener(
                    spec.address(), spec, backendConnector, offlineLoginForge, resourcePackRegistry, security);
            if (!future.isSuccess()) {
                System.out.printf("Could not bind the trusted listener on %s:%d (%s). The addon that asked "
                                + "for it will not receive players.%n",
                        spec.address().getHostString(), spec.address().getPort(), future.cause());
                continue;
            }
            Channel channel = future.channel();
            // Setting RAK_PACKET_LIMIT is not enough: RakNet installs the limiter from its own default
            // before the option is applied, so the handler keeps the default limit — the same trap the
            // public listener's packetLimit=0 path documents. It has to be removed.
            //
            // It must go, not merely be raised. The limiter counts datagrams per source address, and
            // every player from an addon is 127.0.0.1, so one login's worth of MTU-sized fragments
            // blocks the address for that whole edition. It presents as a client connecting, the addon
            // reporting "connection closed" a moment later, and this proxy logging nothing at all —
            // because the datagrams never reach a session.
            if (channel.pipeline().get(RakServerRateLimiter.NAME) != null) {
                channel.pipeline().remove(RakServerRateLimiter.NAME);
            }
            trustedChannels.add(channel);
            System.out.printf("Trusted listener on %s:%d advertising Bedrock %s (protocol %d)%s.%n",
                    spec.address().getHostString(),
                    spec.address().getPort(),
                    spec.advertisedCodec().getMinecraftVersion(),
                    spec.advertisedCodec().getProtocolVersion(),
                    spec.namePrefix().isEmpty() ? "" : ", name prefix '" + spec.namePrefix() + "'");
        }
    }

    /** Everyone currently past login. Exposed so an end-to-end test can observe a join without parsing logs. */
    public ConnectedPlayerRegistry connectedPlayers() {
        return connectedPlayers;
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    public void stop() {
        try {
            if (console != null) {
                console.stop();
            }
            if (pluginManager != null) {
                pluginManager.disableAll();
            }
            for (Channel trusted : trustedChannels) {
                trusted.close().awaitUninterruptibly();
            }
            trustedChannels.clear();
            if (channel != null) {
                channel.close().awaitUninterruptibly();
            }
        } finally {
            backendVerificationServer.close();
            eventLoopGroup.shutdownGracefully();
            stopped.countDown();
        }
    }

    private void onSessionClosed(ListenerSession session) {
        // Only sessions that got past the throttle were counted, and a refused one still reaches
        // here when its channel closes — releasing that would hand its address a free slot.
        if (sessions.remove(session) && session.isThrottled()) {
            connectionThrottle.release(session.getSocketAddress());
        }
        connectedPlayers.unregister(session.proxyConnection());
        onPlayerRosterChanged();
    }

    /**
     * Someone joined or left: refresh the server-list count and push the new roster to the clients
     * that autocomplete against it.
     *
     * <p>The command tree is only sent once, when a player joins, so without this {@code /send}
     * would autocomplete whoever happened to be online at that moment and nobody since.</p>
     */
    private void onPlayerRosterChanged() {
        updateAdvertisement();
        if (playerEnum != null) {
            playerEnum.broadcast();
        }
    }

    private void updateAdvertisement() {
        if (channel != null) {
            channel.config().setOption(RakChannelOption.RAK_ADVERTISEMENT, advertisement().toByteBuf());
        }
    }

    private BedrockPong advertisement() {
        return advertisement(null);
    }

    /**
     * @param trusted when set, advertise the version that addon's translator speaks rather than the
     *                newest the proxy supports. A translator pings before it connects and generally
     *                speaks one Bedrock version, so a pong naming another describes a server it cannot
     *                talk to. The public listener always advertises the newest version.
     */
    private BedrockPong advertisement(TrustedListenerSpec trusted) {
        int port = config.listenAddress().getPort();
        BedrockCodec advertisedCodec = trusted != null
                ? trusted.advertisedCodec()
                : protocolRegistry.advertisedClientCodec();
        return new BedrockPong()
                .edition("MCPE")
                .motd(config.motd())
                .protocolVersion(advertisedCodec.getProtocolVersion())
                .version(advertisedCodec.getMinecraftVersion())
                .playerCount(connectedPlayers.size())
                .maximumPlayerCount(config.maxPlayers())
                .serverId(serverId)
                .subMotd(config.subMotd())
                .gameType(config.gameType())
                .nintendoLimited(false)
                .ipv4Port(port)
                .ipv6Port(port);
    }

    private String backendProtocolDescription() {
        return config.backendProtocol() == null
                ? "auto"
                : config.backendProtocol().minecraftVersion() + " (protocol " + config.backendProtocol().protocolVersion() + ")";
    }
}
