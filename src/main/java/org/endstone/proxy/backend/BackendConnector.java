package org.endstone.proxy.backend;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.DecompressionLimit;
import org.endstone.proxy.codec.CodecDefinitionState;
import org.endstone.proxy.command.NetworkCommands;
import org.endstone.proxy.command.ProxyCommandRegistry;
import org.endstone.proxy.command.ProxyPlayerEnum;
import org.endstone.proxy.permission.ProxyPermissions;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.BackendSwitchConfig;
import org.endstone.proxy.config.ForcedHostsConfig;
import org.endstone.proxy.config.ProxyPolicy;
import org.endstone.proxy.auth.OfflineLoginForge;
import org.endstone.proxy.session.ConnectedPlayerRegistry;
import org.endstone.proxy.protocol.BedrockRelease;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.ProtocolBinding;
import org.endstone.proxy.protocol.ProtocolRegistry;
import org.endstone.proxy.network.LoggingExceptionHandler;
import org.endstone.proxy.session.ProxySessionProfile;
import org.endstone.proxy.palette.BackendPaletteStore;
import org.endstone.proxy.verification.PendingJoinRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class BackendConnector {
    /**
     * The decompressed-batch cap for a backend connection: 256 MB, or
     * {@code -Dbedrock.maxBackendDecompressedBytes=<bytes>} (0 for no limit).
     *
     * <p>Deliberately generous rather than absent. A backend is trusted enough not to need the
     * anti-zip-bomb bound the listener has - the operator chose its address - but a bound still
     * turns a corrupt or hostile stream into one failed connection instead of an out-of-memory that
     * takes the whole proxy, and every player on it, down with it.
     */
    static final int BACKEND_MAX_DECOMPRESSED_BYTES =
            Integer.getInteger("bedrock.maxBackendDecompressedBytes", 256 * 1024 * 1024);

    private final EventLoopGroup eventLoopGroup;
    private final BackendDirectory backendDirectory;
    private final ProxyCommandRegistry commandRegistry;
    private final PendingJoinRegistry pendingJoinRegistry;
    private final ProtocolRegistry protocolRegistry;
    private final CanonicalProtocol backendProtocolOverride;
    private final boolean backendVerificationEnabled;
    private final BackendProtocolDetector backendProtocolDetector;
    private final OfflineLoginForge offlineLoginForge;
    private final Function<String, String> verifiedXuidLookup;
    private final ProxyPolicy policy;
    private final BackendSwitchConfig switchConfig;
    private final ConnectedPlayerRegistry connectedPlayers;
    private final ProxyPermissions permissions;
    private final ProxyPlayerEnum playerEnum;
    private final BackendPaletteStore paletteStore;
    private final String publicAddress;
    private final int listenPort;
    private final ReconnectRoutes reconnectRoutes = new ReconnectRoutes();
    private final BackendSwitcher switcher;
    private final BackendFailover failover;
    private final JoinFailover joinFailover;
    // Set once at startup, after the connector exists: NetworkCommands needs the switcher this
    // owns, and the connector needs the commands to build a router. One of the two has to come
    // second, and a half-built connector is the safer of the pair to hand out.
    private volatile NetworkCommands networkCommands;

    public BackendConnector(
            EventLoopGroup eventLoopGroup,
            BackendDirectory backendDirectory,
            ProxyCommandRegistry commandRegistry,
            PendingJoinRegistry pendingJoinRegistry,
            ProtocolRegistry protocolRegistry,
            CanonicalProtocol backendProtocolOverride,
            boolean backendVerificationEnabled,
            OfflineLoginForge offlineLoginForge,
            Function<String, String> verifiedXuidLookup,
            ProxyPolicy policy,
            ConnectedPlayerRegistry connectedPlayers,
            ProxyPermissions permissions,
            ProxyPlayerEnum playerEnum,
            BackendPaletteStore paletteStore,
            String publicAddress,
            int listenPort
    ) {
        this(
                eventLoopGroup,
                backendDirectory,
                commandRegistry,
                pendingJoinRegistry,
                protocolRegistry,
                backendProtocolOverride,
                backendVerificationEnabled,
                new BackendProtocolDetector(),
                offlineLoginForge,
                verifiedXuidLookup,
                policy,
                connectedPlayers,
                permissions,
                playerEnum,
                paletteStore,
                publicAddress,
                listenPort
        );
    }

    BackendConnector(
            EventLoopGroup eventLoopGroup,
            BackendDirectory backendDirectory,
            ProxyCommandRegistry commandRegistry,
            PendingJoinRegistry pendingJoinRegistry,
            ProtocolRegistry protocolRegistry,
            CanonicalProtocol backendProtocolOverride,
            boolean backendVerificationEnabled,
            BackendProtocolDetector backendProtocolDetector,
            OfflineLoginForge offlineLoginForge,
            Function<String, String> verifiedXuidLookup,
            ProxyPolicy policy,
            ConnectedPlayerRegistry connectedPlayers,
            ProxyPermissions permissions,
            ProxyPlayerEnum playerEnum,
            BackendPaletteStore paletteStore,
            String publicAddress,
            int listenPort
    ) {
        this.paletteStore = paletteStore;
        this.publicAddress = publicAddress == null ? "" : publicAddress.trim();
        this.listenPort = listenPort;
        this.eventLoopGroup = eventLoopGroup;
        this.backendDirectory = backendDirectory;
        this.commandRegistry = commandRegistry;
        this.pendingJoinRegistry = pendingJoinRegistry;
        this.protocolRegistry = protocolRegistry;
        this.backendProtocolOverride = backendProtocolOverride;
        this.backendVerificationEnabled = backendVerificationEnabled;
        this.backendProtocolDetector = backendProtocolDetector;
        this.offlineLoginForge = offlineLoginForge;
        this.verifiedXuidLookup = verifiedXuidLookup != null ? verifiedXuidLookup : name -> "";
        this.policy = policy == null ? ProxyPolicy.defaults() : policy;
        this.switchConfig = this.policy.backendSwitch();
        this.connectedPlayers = connectedPlayers;
        this.permissions = permissions == null
                ? ProxyPermissions.inMemory(this.policy.permissions())
                : permissions;
        this.playerEnum = playerEnum;
        this.switcher = new BackendSwitcher(this, this.switchConfig);
        this.failover = new BackendFailover(backendDirectory, this, this.policy.failover());
        this.joinFailover = new JoinFailover(this);
    }

    /**
     * Connects a joining player, walking the configured try-list if the first backend will not have
     * them.
     */
    public void connect(ProxyConnection connection) {
        List<BackendConfig> candidates = joinCandidates(connection);
        BackendConfig first = candidates.get(0);
        connection.beginJoinSequence(candidates.subList(1, candidates.size()));
        connect(connection, first);
    }

    private List<BackendConfig> joinCandidates(ProxyConnection connection) {
        return JoinCandidates.expand(initialBackend(connection), policy.join(), backendDirectory);
    }

    /**
     * Whether this player can only reach a backend by reconnecting.
     *
     * <p>A Bedrock client fixes its block-id scheme from the StartGame it logged in with and cannot
     * be told otherwise while it is playing, so a seamless handoff to a backend on the other scheme
     * delivers chunks the client cannot decode: the player stands in an empty or scrambled world.
     * Backends that hash block ids (every Bedrock server) and ones that number them by palette order
     * (a Geyser instance fronting a Java server) are the two schemes in practice.</p>
     *
     * <p>Answered false while either side is unknown. Guessing "reconnect" for an unvisited backend
     * would put a loading screen in front of the ordinary same-scheme switch that makes up almost
     * every move on a network; the scheme is learned from the first StartGame and persisted, so the
     * uncertainty lasts one visit rather than one restart.</p>
     */
    public boolean needsReconnectToReach(ProxyConnection connection, BackendConfig backend) {
        Boolean clientHashed = connection.clientBlockIdsHashed();
        Boolean backendHashed = paletteStore == null ? null : paletteStore.blockIdsHashed(backend.name());
        return clientHashed != null && backendHashed != null && clientHashed != backendHashed;
    }

    /**
     * Sends the player back to the proxy to reach a backend a handoff cannot.
     *
     * <p>The transfer names the proxy's own address, so the player never leaves it: the same
     * listener answers, the same identity is verified again, and the backend stays unreachable from
     * outside. What changes is that the client re-runs level init, which is the only way it will
     * read a different block-id scheme.</p>
     */
    public boolean reconnectTo(ProxyConnection connection, BackendConfig backend) {
        ReconnectAddress target = reconnectAddress(connection);
        if (target == null) {
            sendMessage(connection, "Unable to reach " + backend.name() + " from here. Reconnect and pick it from the server list.");
            System.out.printf(
                    "Cannot send %s to %s: it needs a reconnect, and the proxy has no address to send them back to."
                            + " Set publicAddress in the config.%n",
                    connection.clientLogin().authData().displayName(),
                    backend.name()
            );
            return false;
        }

        reconnectRoutes.remember(connection.clientLogin().authData().xuid(), backend.name());
        System.out.printf(
                "Sending %s to %s by reconnect via %s:%d (it numbers block ids differently to the world"
                        + " they logged into).%n",
                connection.clientLogin().authData().displayName(),
                backend.name(),
                target.host(),
                target.port()
        );
        sendMessage(connection, "Taking you to " + backend.name() + "...");

        TransferPacket transfer = new TransferPacket();
        transfer.setAddress(target.host());
        transfer.setPort(target.port());
        connection.client().sendPacket(transfer);
        return true;
    }

    /**
     * Where to tell the client to reconnect: the operator's {@code publicAddress} if set, otherwise
     * the address this player themselves connected with.
     *
     * <p>The client's own claim is used by default so a working install needs no configuration at
     * all — it is whatever they typed, so it is reachable for them by definition, and it is correct
     * per player whether they came by hostname or by IP. It is unsigned and a modified client can
     * claim anything, which is harmless here: the worst outcome is that a player fails to reconnect
     * to an address they supplied. {@code publicAddress} exists for the case where that is not good
     * enough, such as a client that connected through a hostname the proxy would rather not
     * advertise.</p>
     */
    private ReconnectAddress reconnectAddress(ProxyConnection connection) {
        ReconnectAddress configured = ReconnectAddress.parse(publicAddress, listenPort);
        if (configured != null) {
            return configured;
        }
        // The claim carries the port the player actually used, which is the right one to send them
        // back to when the proxy sits behind a forwarded port.
        return ReconnectAddress.parse(clientServerAddress(connection), listenPort);
    }

    public ReconnectRoutes reconnectRoutes() {
        return reconnectRoutes;
    }

    /** False while the backend has never been seen, so the config key remains the way to say so. */
    private boolean doesNotImplementSubChunks(BackendConfig backend) {
        Boolean hashed = paletteStore == null ? null : paletteStore.blockIdsHashed(backend.name());
        return hashed != null && !hashed;
    }

    /**
     * The backend a joining player lands on: their forced host if the address they connected with
     * has one, otherwise the default backend.
     *
     * <p>The hostname comes from the client's {@code ServerAddress} claim, which is signed by the
     * client's own key rather than Mojang's — so it says where the player <em>thinks</em> they
     * connected, and a modified client can claim anything. That is fine for routing and not fine as
     * a permission check; see {@link ForcedHostsConfig}.</p>
     */
    private BackendConfig initialBackend(ProxyConnection connection) {
        // A player the proxy itself just asked to reconnect goes where they were headed, ahead of
        // any other rule: they did not choose to log in, they were sent round the loop to reach a
        // backend a handoff could not, and dropping them on the default one instead would look like
        // the move had simply failed.
        BackendConfig pending = backendDirectory
                .find(String.valueOf(reconnectRoutes.take(connection.clientLogin().authData().xuid())))
                .orElse(null);
        if (pending != null) {
            System.out.printf(
                    "Routing %s to backend %s: completing the reconnect they were sent on.%n",
                    connection.clientLogin().authData().displayName(),
                    pending.name()
            );
            return pending;
        }

        ForcedHostsConfig forcedHosts = policy.forcedHosts();
        if (forcedHosts.isEmpty()) {
            return backendDirectory.defaultBackend();
        }
        String serverAddress = clientServerAddress(connection);
        BackendConfig forced = forcedHosts.backendFor(serverAddress)
                .flatMap(backendDirectory::find)
                .orElse(null);
        if (forced == null) {
            return backendDirectory.defaultBackend();
        }
        System.out.printf(
                "Routing %s to backend %s by forced host '%s'.%n",
                connection.clientLogin().authData().displayName(),
                forced.name(),
                serverAddress
        );
        return forced;
    }

    private static String clientServerAddress(ProxyConnection connection) {
        Object serverAddress = connection.clientLogin().skinData().get("ServerAddress");
        return serverAddress instanceof String address ? address : "";
    }

    public void connect(ProxyConnection connection, BackendConfig backendConfig) {
        connection.beginJoinAttempt();
        connectInternal(connection, backendConfig, true, new BackendActivation() {
            @Override
            public void onReady(BackendSession backend) {
                connection.setBackend(backendConfig.name(), backend);
            }

            @Override
            public void onStartGame(BackendSession backend) {
            }

            @Override
            public void onFailure(BackendSession backend, Exception exception) {
                // Covers both "the backend never answered" and "the handshake failed", since the
                // dial-out reports through here too; the text has to fit either.
                if (joinFailover.handleJoinFailure(connection, backendConfig.name(),
                        failureMessage(exception, "unreachable"))) {
                    return;
                }
                connection.client().disconnect(failureMessage(
                        exception,
                        "Unable to connect to backend server"
                ));
            }
        });
    }

    JoinFailover joinFailover() {
        return joinFailover;
    }

    /**
     * Moves an already-playing client to another backend.
     *
     * @return a future that completes when the target's StartGame has arrived and the client has
     *         been handed over, or completes exceptionally when the switch fails. The outcome is
     *         mostly asynchronous — a failed handshake surfaces on the backend's event loop long
     *         after this method returns — so it is reported here rather than thrown.
     */
    public CompletableFuture<Void> connectForSwitch(ProxyConnection connection, BackendConfig backendConfig) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            connectInternal(connection, backendConfig, false, new BackendActivation() {
                @Override
                public void onReady(BackendSession backend) {
                    sendMessage(connection, "Joining " + backendConfig.name() + "...");
                }

                @Override
                public void onStartGame(BackendSession backend) {
                    BackendSession previous = connection.replaceBackend(backendConfig.name(), backend);
                    if (previous != null && previous != backend && previous.isConnected()) {
                        previous.disconnect("Switching backend");
                    }
                    sendMessage(connection, "Connected to " + backendConfig.name() + ".");
                    completion.complete(null);
                }

                @Override
                public void onFailure(BackendSession backend, Exception exception) {
                    // The switch lock is the caller's: /server holds it across retries of the same
                    // backend, failover releases it between candidates. Releasing it here would let
                    // a second switch start in the middle of a retry sequence.
                    connection.clearPendingBackend(backend);
                    if (exception instanceof UnsupportedVersionPairException) {
                        sendMessage(connection, exception.getMessage());
                    }
                    if (backend != null && backend.isConnected()) {
                        backend.setDisconnectClientOnClose(false);
                        backend.discardInboundPackets();
                        backend.disconnect("Backend switch failed");
                    }
                    completion.completeExceptionally(exception);
                }
            });
        } catch (Exception exception) {
            // onFailure has already run and completed the future; this only covers a throw that
            // never reached it.
            completion.completeExceptionally(exception);
        }
        return completion;
    }

    public BackendFailover failover() {
        return failover;
    }

    /** The roster {@code /send} autocompletes against, or null when no player registry is wired. */
    public ProxyPlayerEnum playerEnum() {
        return playerEnum;
    }

    public BackendSwitcher switcher() {
        return switcher;
    }

    public void setNetworkCommands(NetworkCommands networkCommands) {
        this.networkCommands = networkCommands;
    }

    private void connectInternal(
            ProxyConnection connection,
            BackendConfig backendConfig,
            boolean disconnectClientOnClose,
            BackendActivation activation
    ) {
        ProxySessionProfile previousProfile = connection.sessionProfile();
        BackendActivation guardedActivation = guardedActivation(
                connection,
                previousProfile,
                disconnectClientOnClose,
                activation
        );
        // Hoisted out of the try below because the bootstrap's initSession lambda needs it: the
        // backend's release decides how it writes a scoreboard removal, and protocol 2168 covers
        // five releases that do not agree on that. See BedrockRelease.
        String backendMinecraftVersion;
        try {
            BackendProtocol backendProtocol = backendProtocol(backendConfig, connection);
            backendMinecraftVersion = backendProtocol.minecraftVersion();
            ProtocolBinding binding = resolveBinding(connection, backendConfig, backendProtocol);
            connection.setSessionProfile(ProxySessionProfile.from(binding));
            connection.setBackendLogin(backendLogin(connection, binding, backendConfig, backendProtocol));
            if (ProxyConnection.isPacketTracingConfigured()) {
                System.out.printf(
                        "Selected backend %s protocol %s for client %s.%n",
                        backendConfig.name(),
                        versionName(backendProtocol.minecraftVersion(), backendProtocol.protocolVersion()),
                        versionName(connection.client().clientCodec().getMinecraftVersion(), connection.client().clientCodec().getProtocolVersion())
                );
            }
        } catch (UnsupportedVersionPairException exception) {
            if (previousProfile != null) {
                connection.setSessionProfile(previousProfile);
            }
            guardedActivation.onFailure(null, exception);
            throw exception;
        }

        AtomicReference<BackendSession> createdSession = new AtomicReference<>();
        ChannelFactory<? extends Channel> channelFactory = RakChannelFactory.client(NioDatagramChannel.class);
        ChannelFuture future = new Bootstrap()
                .group(eventLoopGroup)
                .channelFactory(channelFactory)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, connection.sessionProfile().backendCodec().getRaknetProtocolVersion())
                .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                .option(RakChannelOption.RAK_MTU_SIZES, new Integer[]{1492, 1200, 576})
                .option(RakChannelOption.RAK_CLIENT_INTERNAL_ADDRESSES, 20)
                .option(RakChannelOption.RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS, 500)
                // Without this the dial-out sits on RakNet's 10s session timeout before admitting a
                // backend is not there, which makes a retry sequence far slower than it needs to be.
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, switchConfig.connectTimeoutMillis())
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .handler(new BedrockClientInitializer() {
                    @Override
                    public BackendSession createSession0(BedrockPeer peer, int subClientId) {
                        return new BackendSession(peer, subClientId);
                    }

                    @Override
                    protected void initSession(org.cloudburstmc.protocol.bedrock.BedrockClientSession session) {
                        BackendSession backend = (BackendSession) session;
                        createdSession.set(backend);
                        backend.setConnection(connection);
                        backend.setDisconnectClientOnClose(disconnectClientOnClose);
                        // Inferred rather than configured wherever possible: a backend that numbers
                        // block ids by palette order is not really a Bedrock server and does not
                        // implement the sub-chunk system either. The config key stays as an override
                        // for a backend nobody has visited yet, but an ordinary install never needs
                        // to set it.
                        backend.setDropSubChunkRequests(
                                backendConfig.dropSubChunkRequests() || doesNotImplementSubChunks(backendConfig));
                        if (!disconnectClientOnClose) {
                            connection.setPendingBackend(backend);
                        }
                        backend.setCodec(connection.sessionProfile().backendCodec());
                        // Must follow setCodec: it replaces the helper this writes to. The version
                        // is the backend's own, off its pong, and it decides how this backend writes
                        // a scoreboard removal - protocol 2168 alone does not say. See BedrockRelease.
                        BedrockRelease.applyTo(backend, backendMinecraftVersion);
                        backend.getPeer().getChannel().pipeline().addLast(
                                "endstone-backend-exception-logger",
                                new LoggingExceptionHandler("backend")
                        );
                        CodecDefinitionState.installFallbacks(backend);
                        backend.getPeer().getCodecHelper().setEncodingSettings(EncodingSettings.UNLIMITED);
                        // Same reasoning as the line above, one layer down. The decompression cap is
                        // a defence against an anonymous client inflating a few kilobytes into
                        // arbitrary heap; a backend is neither anonymous nor unbounded in what it
                        // legitimately sends. A heavily modded server's join batch - item registry,
                        // creative content and crafting data in one tick - goes past the 10 MB
                        // default, and capping it there does not degrade anything: it throws out of
                        // the decoder, kills the backend connection mid-join, and the player is
                        // failed over with disconnect.lost and no reason they can see. This bounds
                        // the backend leg somewhere it will not be reached instead.
                        DecompressionLimit.set(backend.getPeer().getChannel(), BACKEND_MAX_DECOMPRESSED_BYTES);
                        backend.setPacketHandler(new BackendInitialPacketHandler(
                                connection,
                                backend,
                                backendConfig.name(),
                                pendingJoinRegistry,
                                backendVerificationEnabled,
                                new BackendCommandRouter(
                                        backendDirectory,
                                        switcher,
                                        networkCommands,
                                        permissions,
                                        policy.security()
                                ),
                                commandRegistry,
                                backendDirectory,
                                switcher,
                                guardedActivation,
                                verifiedXuidLookup,
                                failover,
                                joinFailover,
                                permissions,
                                playerEnum,
                                policy.commands()
                        ));
                    }
                })
                .connect(backendConfig.address())
                .awaitUninterruptibly();

        if (!future.isSuccess()) {
            BackendSession backend = createdSession.get();
            if (!disconnectClientOnClose && previousProfile != null) {
                connection.setSessionProfile(previousProfile);
            }
            // backend is null when the target never answered: RakNet only creates a session once the
            // connection is established. onFailure must run anyway — it is the only report the caller
            // gets, and skipping it for the most ordinary failure of all ("the backend is down") is
            // what used to leave a player stuck on "already connecting" until they reconnected.
            guardedActivation.onFailure(backend, new IllegalStateException("Unable to connect to backend " + backendConfig.address(), future.cause()));
            throw new IllegalStateException("Unable to connect to backend " + backendConfig.address(), future.cause());
        }

        BackendSession backend = createdSession.get();
        if (backend == null) {
            throw new IllegalStateException("Connected to backend " + backendConfig.address() + " without creating a Bedrock session");
        }

        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(connection.sessionProfile().backendCodec().getProtocolVersion());
        backend.sendPacketImmediately(request);
    }

    private ProtocolBinding resolveBinding(
            ProxyConnection connection,
            BackendConfig backendConfig,
            BackendProtocol backendProtocol
    ) {
        int clientProtocol = connection.client().clientCodec().getProtocolVersion();
        return protocolRegistry.findBinding(clientProtocol, backendProtocol.protocolVersion())
                .orElseThrow(() -> new UnsupportedVersionPairException(
                        "This client and backend version pair is not supported: client "
                                + versionName(connection.client().clientCodec().getMinecraftVersion(), clientProtocol)
                                + " cannot connect to backend "
                                + versionName(backendProtocol.minecraftVersion(), backendProtocol.protocolVersion())
                                + "."
                ));
    }

    private BackendProtocol backendProtocol(BackendConfig backendConfig, ProxyConnection connection) {
        // A backend's own setting wins over the global one. During an upgrade the fleet is always
        // mixed — backends move one at a time — and speaking the global version to a backend that
        // has already moved gets the login rejected as LOGIN_FAILED_CLIENT_OLD.
        if (backendConfig.protocol() != null) {
            // The release comes from what the operator actually wrote, not from the codec's name for
            // the protocol they pinned. Those differ for every protocol number Mojang reused: pinning
            // 2168 says nothing about whether the backend is 1.26.40 or 1.26.44, and reading "1.26.40"
            // back out of the codec would assert the former for both. A pin that names no release
            // leaves it null, which BedrockRelease reads as "not stated" rather than as "old".
            return new BackendProtocol(
                    backendConfig.protocol().protocolVersion(),
                    backendConfig.declaredRelease()
            );
        }
        if (backendProtocolOverride != null) {
            // Same rule for the global pin, and null for the same reason. A fleet-wide value cannot
            // name one fleet-wide release honestly anyway - the fleet is mixed during every upgrade,
            // which is the situation the per-backend key exists for.
            return new BackendProtocol(backendProtocolOverride.protocolVersion(), null);
        }

        BedrockPong pong;
        try {
            pong = backendProtocolDetector.detect(backendConfig.address());
        } catch (IOException exception) {
            // Probing is a convenience, not a requirement: some server builds answer the RakNet
            // unconnected ping with a truncated pong that carries no version payload. Assume the
            // backend matches the client rather than refusing a join we have not actually tried.
            return assumeClientProtocol(backendConfig, connection, exception);
        }

        int protocolVersion = pong.protocolVersion();
        String minecraftVersion = pong.version();
        if (protocolRegistry.findBackendCodec(protocolVersion).isEmpty()) {
            throw new UnsupportedVersionPairException(
                    "Unsupported backend version "
                            + versionName(minecraftVersion, protocolVersion)
                            + " on " + backendConfig.name() + "."
            );
        }
        return new BackendProtocol(protocolVersion, minecraftVersion);
    }

    private BackendProtocol assumeClientProtocol(
            BackendConfig backendConfig,
            ProxyConnection connection,
            IOException cause
    ) {
        BedrockCodec clientCodec = connection.client().clientCodec();
        if (protocolRegistry.findBackendCodec(clientCodec.getProtocolVersion()).isEmpty()) {
            throw new UnsupportedVersionPairException(
                    "Unable to detect backend protocol for " + backendConfig.name() + " at " + backendConfig.address() + ".",
                    cause
            );
        }
        // The client's *reported* release, not the codec's name for its protocol. Those are not the
        // same thing on 2168, which one codec serves for 1.26.40 through 1.26.44, and the difference
        // decides how this backend's scoreboard removals are read (see BedrockRelease). Taking the
        // codec's name here would assume 1.26.40 of every backend whose probe failed, which is the
        // one guess this proxy should not make. Falls back to the codec's name if the client did not
        // say, which is no worse than what this line used to do unconditionally.
        String assumedMinecraftVersion = connection.clientLogin() == null
                ? null
                : connection.clientLogin().gameVersion();
        if (assumedMinecraftVersion == null) {
            assumedMinecraftVersion = clientCodec.getMinecraftVersion();
        }
        System.out.printf(
                "WARNING: %s at %s did not answer the protocol probe (%s). Assuming it speaks the client's %s;"
                        + " set backend.protocol in the config to skip probing.%n",
                backendConfig.name(),
                backendConfig.address(),
                cause.getMessage(),
                versionName(assumedMinecraftVersion, clientCodec.getProtocolVersion())
        );
        return new BackendProtocol(clientCodec.getProtocolVersion(), assumedMinecraftVersion);
    }

    private static String versionName(String minecraftVersion, int protocolVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return "protocol " + protocolVersion;
        }
        return minecraftVersion + " (protocol " + protocolVersion + ")";
    }

    private static String failureMessage(Exception exception, String fallback) {
        return exception instanceof UnsupportedVersionPairException ? exception.getMessage() : fallback;
    }

    private static BackendActivation guardedActivation(
            ProxyConnection connection,
            ProxySessionProfile previousProfile,
            boolean disconnectClientOnClose,
            BackendActivation delegate
    ) {
        return new BackendActivation() {
            @Override
            public void onReady(BackendSession backend) {
                delegate.onReady(backend);
            }

            @Override
            public void onStartGame(BackendSession backend) {
                delegate.onStartGame(backend);
            }

            @Override
            public void onFailure(BackendSession backend, Exception exception) {
                if (!disconnectClientOnClose && previousProfile != null) {
                    connection.setSessionProfile(previousProfile);
                }
                delegate.onFailure(backend, exception);
            }
        };
    }

    private static String serverAddress(BackendConfig backendConfig) {
        return backendConfig.address().getHostString() + ":" + backendConfig.address().getPort();
    }

    /**
     * Prints every field a backend can key persistent player data on.
     *
     * <p>A player whose position resets to spawn and whose inventory is empty on every rejoin is a
     * backend that did not recognise them as a returning player. That is decided entirely by the
     * identity in the login the proxy forges, and the identity comes from several places at once:
     * the Mojang-signed chain supplies the name, XUID and identity UUID, while the client-data JWT
     * carries {@code SelfSignedId}, {@code DeviceId} and {@code ClientRandomId} — which is what a
     * server falls back to when the XUID is blank, as it always is for a proxied join (BDS 1.26.10+
     * refuses a self-signed {@code xid}).</p>
     *
     * <p>Rejoin twice and compare two of these lines. Anything that differs between joins is the
     * bug; if they are all identical the backend is being handed a stable identity and the data loss
     * is happening somewhere else entirely. That distinction cannot be made by reading code — both
     * paths look correct — so it is worth one line per join.</p>
     */
    private void logBackendIdentity(ProxyConnection connection, BackendConfig backendConfig, int backendProtocolVersion) {
        if (!ProxyConnection.isPacketTracingConfigured()) {
            return;
        }
        var authData = connection.clientLogin().authData();
        var skinData = connection.clientLogin().skinData();
        System.out.printf(
                "BACKEND IDENTITY for %s (protocol %d): name=%s xuid=%s identity=%s "
                        + "selfSignedId=%s deviceId=%s clientRandomId=%s playFabId=%s%n",
                backendConfig.name(),
                backendProtocolVersion,
                authData.displayName(),
                authData.xuid(),
                authData.identity(),
                skinData.get("SelfSignedId"),
                skinData.get("DeviceId"),
                skinData.get("ClientRandomId"),
                skinData.get("PlayFabId")
        );
    }

    private org.cloudburstmc.protocol.bedrock.packet.LoginPacket backendLogin(
            ProxyConnection connection,
            ProtocolBinding binding,
            BackendConfig backendConfig,
            BackendProtocol backendProtocol
    ) {
        int backendProtocolVersion = binding.backendCodec().getProtocolVersion();
        logBackendIdentity(connection, backendConfig, backendProtocolVersion);
        if (backendProtocolVersion >= CanonicalProtocol.V1_26_10.protocolVersion()) {
            // Bedrock 1.26.10+ (protocol 944+) servers expect the modern OIDC multiplayer
            // token format. The legacy extraData chain JWT triggers a discovery-environment
            // check failure because it lacks the PlayFab `tid` claim. The OIDC format is
            // a dummy chain ([""]) plus a self-signed token carrying cpk/xid/xname/tid.
            // See gophertunnel's login.EncodeOffline(legacy=false).
            return offlineLoginForge.forgeOidcLogin(
                    connection.keyPair(),
                    connection.clientLogin(),
                    backendProtocolVersion,
                    backendProtocol.minecraftVersion(),
                    serverAddress(backendConfig)
            );
        }
        // Legacy CertificateChainPayload for pre-1.26.10 servers (898/924). v291 serializer
        // (898) ignores AuthType on the wire; v818 (924) writes it but offline mode accepts
        // both SELF_SIGNED and FULL for the legacy chain.
        return offlineLoginForge.forge(
                connection.keyPair(),
                connection.clientLogin(),
                backendProtocolVersion,
                backendProtocol.minecraftVersion(),
                serverAddress(backendConfig),
                AuthType.SELF_SIGNED
        );
    }

    private static void sendMessage(ProxyConnection connection, String message) {
        if (!connection.client().isConnected()) {
            return;
        }
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.SYSTEM);
        packet.setNeedsTranslation(false);
        packet.setSourceName("");
        packet.setMessage(message);
        packet.setXuid("");
        connection.client().sendPacket(packet);
    }

    private record BackendProtocol(int protocolVersion, String minecraftVersion) {
    }
}
