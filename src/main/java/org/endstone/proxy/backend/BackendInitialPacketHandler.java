package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.endstone.proxy.command.AvailableCommandsInjector;
import org.endstone.proxy.command.ProxyCommandInterceptor;
import org.endstone.proxy.command.ProxyCommandRegistry;
import org.endstone.proxy.command.ProxyPlayerEnum;
import org.endstone.proxy.config.CommandsConfig;
import org.endstone.proxy.permission.ProxyPermissions;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.endstone.proxy.verification.PendingJoin;
import org.endstone.proxy.verification.PendingJoinRegistry;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import javax.crypto.SecretKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;

public final class BackendInitialPacketHandler implements BedrockPacketHandler {
    private final ProxyConnection connection;
    private final BackendSession backend;
    private final String backendName;
    private final PendingJoinRegistry pendingJoinRegistry;
    private final boolean backendVerificationEnabled;
    private final BackendCommandRouter commandRouter;
    private final ProxyCommandRegistry commandRegistry;
    private final BackendDirectory backendDirectory;
    private final BackendSwitcher backendSwitcher;
    private final BackendActivation activation;
    private final Function<String, String> verifiedXuidLookup;
    private final BackendFailover failover;
    private final JoinFailover joinFailover;
    private final ProxyPermissions permissions;
    private final ProxyPlayerEnum playerEnum;
    private final CommandsConfig commandsConfig;
    /** The command names this backend has taken over; resolved once, since backendName is fixed. */
    private final java.util.Set<String> passthroughCommands;
    private PendingJoin pendingJoin;
    private boolean warnedPreHandshakeDisconnect;

    public BackendInitialPacketHandler(
            ProxyConnection connection,
            BackendSession backend,
            String backendName,
            PendingJoinRegistry pendingJoinRegistry,
            boolean backendVerificationEnabled,
            BackendCommandRouter commandRouter,
            ProxyCommandRegistry commandRegistry,
            BackendDirectory backendDirectory,
            BackendSwitcher backendSwitcher,
            BackendActivation activation,
            Function<String, String> verifiedXuidLookup,
            BackendFailover failover,
            JoinFailover joinFailover,
            ProxyPermissions permissions,
            ProxyPlayerEnum playerEnum,
            CommandsConfig commandsConfig
    ) {
        this.connection = connection;
        this.backend = backend;
        this.backendName = backendName;
        this.pendingJoinRegistry = pendingJoinRegistry;
        this.backendVerificationEnabled = backendVerificationEnabled;
        this.commandRouter = commandRouter;
        this.commandRegistry = commandRegistry;
        this.backendDirectory = backendDirectory;
        this.backendSwitcher = backendSwitcher;
        this.activation = activation;
        this.verifiedXuidLookup = verifiedXuidLookup != null ? verifiedXuidLookup : name -> "";
        this.failover = failover;
        this.joinFailover = joinFailover;
        this.permissions = permissions;
        this.playerEnum = playerEnum;
        this.commandsConfig = commandsConfig == null ? CommandsConfig.defaults() : commandsConfig;
        this.passthroughCommands = this.commandsConfig.passthroughFor(backendName);
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        if (packet.getCompressionThreshold() > 0) {
            backend.setCompression(packet.getCompressionAlgorithm());
        } else {
            backend.setCompression(PacketCompressionAlgorithm.NONE);
        }
        if (backendVerificationEnabled) {
            pendingJoin = pendingJoinRegistry.register(
                    connection.clientLogin().authData(),
                    backendName,
                    // The player's real address, not the socket's: a bridged player's socket address is the bridge's loopback one, and a backend checking IPs would see 127.0.0.1 for
                    // every one of them.
                    connection.clientAddress()
            );
            if (ProxyConnection.isPacketTracingConfigured()) {
                System.out.printf("Registered backend verification pending join for %s (%s) on backend %s, expires at %d.%n",
                        pendingJoin.name(),
                        pendingJoin.xuid(),
                        backendName,
                        pendingJoin.expiresAtMillis()
                );
            }
        } else {
            System.out.printf(
                    "WARNING: Backend verification is disabled; backend %s direct joins are not protected by the proxy verifier.%n",
                    backendName
            );
        }
        if (ProxyConnection.isPacketTracingConfigured()) {
            logBackendLoginCapabilities(connection.backendLogin());
        }
        backend.sendPacketImmediately(connection.backendLogin());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        if (ProxyConnection.isPacketTracingConfigured()) {
            System.out.printf(
                    "Backend %s sent PlayStatus before handshake: %s.%n",
                    backendName,
                    packet.getStatus()
            );
        }
        if (isLoginFailure(packet.getStatus())) {
            // The backend has already said no. Left alone the session simply goes quiet and we wait
            // out RakNet's 10s timeout before noticing — twice over, with two attempts per backend,
            // which is long enough for the client to give up on the whole join. Failing here turns
            // a version mismatch into an immediate move to the next candidate.
            System.out.printf(
                    "Backend %s rejected the proxy login (%s); treating as an immediate failure "
                            + "instead of waiting for the session to time out. If that backend runs a "
                            + "newer Minecraft version, set backend.%s.protocol.%n",
                    backendName,
                    packet.getStatus(),
                    backendName
            );
            warnedPreHandshakeDisconnect = true;
            backend.setDisconnectClientOnClose(false);
            IllegalStateException failure = new IllegalStateException(
                    "Backend " + backendName + " rejected the login: " + packet.getStatus());
            if (joinFailover == null || !joinFailover.handleJoinFailure(connection, backendName, failure.getMessage())) {
                activation.onFailure(backend, failure);
            }
            backend.disconnect("Login rejected");
        }
        return PacketSignal.HANDLED;
    }

    /**
     * A PlayStatus arriving before the encryption handshake can only be bad news; LOGIN_SUCCESS
     * comes later in the sequence, after the handshake this handler is still waiting for.
     */
    private static boolean isLoginFailure(PlayStatusPacket.Status status) {
        return status != null && status != PlayStatusPacket.Status.LOGIN_SUCCESS;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        System.out.printf(
                "Backend %s disconnected before handshake: reason=%s skipped=%s message=%s filtered=%s.%n",
                backendName,
                packet.getReason(),
                packet.isMessageSkipped(),
                packet.getKickMessage(),
                packet.getFilteredMessage()
        );
        warnPreHandshakeDisconnect(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        try {
            JsonWebSignature signature = new JsonWebSignature();
            signature.setCompactSerialization(packet.getJwt());
            JSONObject saltJwt = new JSONObject(JsonUtil.parseJson(signature.getUnverifiedPayload()));
            String x5u = signature.getHeader(HeaderParameterNames.X509_URL);
            ECPublicKey serverKey = BedrockCrypto.parseKey(x5u);
            SecretKey key = BedrockCrypto.secretKey(
                    connection.keyPair().getPrivate(),
                    serverKey,
                    Base64.getDecoder().decode(JsonUtils.childAsType(saltJwt, "salt", String.class))
            );

            backend.enableEncryption(key);
            backend.sendPacketImmediately(new ClientToServerHandshakePacket());
            backend.setPacketHandler(new BackendRelayPacketHandler(
                    connection,
                    backend,
                    backendName,
                    activation,
                    pendingJoinRegistry,
                    pendingJoin,
                    new AvailableCommandsInjector(
                            commandRegistry,
                            visibleBackendNames(),
                            this::advertiseCommand,
                            playerEnum
                    ),
                    verifiedXuidLookup,
                    failover,
                    joinFailover,
                    backendDirectory,
                    backendSwitcher
            ));
            activation.onReady(backend);
            connection.client().setPacketHandler(new ClientRelayPacketHandler(
                    connection,
                    new ProxyCommandInterceptor(
                            commandRegistry,
                            passthroughCommands,
                            commandsConfig.qualifier()
                    ),
                    commandRouter
            ));
            System.out.printf("Connected player %s to backend %s.%n",
                    connection.clientLogin().authData().displayName(),
                    backendName
            );
            return PacketSignal.HANDLED;
        } catch (Exception exception) {
            activation.onFailure(backend, exception);
            throw new IllegalStateException("Unable to complete backend encryption handshake", exception);
        }
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        System.out.printf(
                "Backend %s closed before completing the encryption handshake: %s.%n",
                backendName,
                reason
        );
        if (joinFailover != null && joinFailover.handleJoinFailure(connection, backendName, reason)) {
            return;
        }
        if (warnedPreHandshakeDisconnect) {
            return;
        }
        System.out.printf(
                "WARNING: Backend %s did not accept the proxy's offline backend login. If the backend has online mode enabled, proxied joins will not work; set the backend to offline mode and secure it with the EndlinkGuard plugin.%n",
                backendName
        );
    }

    /**
     * Whether a proxy command belongs in this player's command tree on this backend.
     *
     * <p>Two separate reasons to leave one out. An admin command is hidden from a player who may not
     * run it — cosmetic only, since the client can send any line it likes and the router re-checks on
     * execution. A command this backend has taken over is hidden because the backend registers that
     * name itself: injecting the proxy's entry alongside would either be dropped by the injector's
     * de-duplication or, worse, advertise proxy semantics for a name the proxy is going to
     * forward.</p>
     */
    private boolean advertiseCommand(String commandName) {
        return !passthroughCommands.contains(commandName.toLowerCase(Locale.ROOT))
                && permissions.allows(
                        connection.clientLogin().authData().xuid(),
                        connection.clientLogin().authData().displayName(),
                        commandName
                );
    }

    /**
     * The backends this player may send themselves to. A restricted backend is left out of the
     * command tree entirely, so {@code /server} does not autocomplete a name the player would then
     * be refused — and cannot be used to discover that the backend exists at all.
     */
    private java.util.List<String> visibleBackendNames() {
        String xuid = connection.clientLogin().authData().xuid();
        String displayName = connection.clientLogin().authData().displayName();
        return backendDirectory.backendNames().stream()
                .filter(name -> permissions.mayJoinBackend(xuid, displayName, name))
                .toList();
    }

    private void warnPreHandshakeDisconnect(DisconnectPacket packet) {
        warnedPreHandshakeDisconnect = true;
        String text = String.join(" ",
                String.valueOf(packet.getReason()),
                String.valueOf(packet.getKickMessage()),
                String.valueOf(packet.getFilteredMessage())
        ).toLowerCase(Locale.ROOT);
        if (text.contains("online")
                || text.contains("auth")
                || text.contains("xbox")
                || text.contains("login")
                || text.contains("notauthenticated")) {
            System.out.printf(
                    "WARNING: Backend %s appears to require online/authenticated backend logins. The proxy uses a forged offline backend login, so this backend must have online mode disabled or proxied joins will not work.%n",
                    backendName
            );
            return;
        }
        System.out.printf(
                "WARNING: Backend %s rejected the proxy login before the encryption handshake. If online mode is enabled on that backend, proxied joins will not work until it is disabled.%n",
                backendName
        );
    }

    private static void logBackendLoginCapabilities(LoginPacket login) {
        try {
            JsonWebSignature clientJwt = new JsonWebSignature();
            clientJwt.setCompactSerialization(login.getClientJwt());
            JSONObject skinData = new JSONObject(JsonUtil.parseJson(clientJwt.getUnverifiedPayload()));
            System.out.printf(
                    "Sending backend LoginPacket: protocol=%d authPayload=%s authType=%s GameVersion=%s ServerAddress=%s CompatibleWithClientSideChunkGen=%s MaxViewDistance=%s DeviceOS=%s.%n",
                    login.getProtocolVersion(),
                    login.getAuthPayload() == null ? null : login.getAuthPayload().getClass().getSimpleName(),
                    login.getAuthPayload() == null ? null : login.getAuthPayload().getAuthType(),
                    skinData.get("GameVersion"),
                    skinData.get("ServerAddress"),
                    skinData.get("CompatibleWithClientSideChunkGen"),
                    skinData.get("MaxViewDistance"),
                    skinData.get("DeviceOS")
            );
        } catch (Exception exception) {
            System.out.printf(
                    "Sending backend LoginPacket: protocol=%d authPayload=%s authType=%s clientJwtCapabilities=unreadable (%s).%n",
                    login.getProtocolVersion(),
                    login.getAuthPayload() == null ? null : login.getAuthPayload().getClass().getSimpleName(),
                    login.getAuthPayload() == null ? null : login.getAuthPayload().getAuthType(),
                    exception.getClass().getSimpleName()
            );
        }
    }
}
