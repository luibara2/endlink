package org.endstone.proxy.listener;

import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.auth.ClientLoginAuthenticator;
import org.endstone.proxy.auth.OfflineLoginForge;
import org.endstone.proxy.codec.CodecDefinitionState;
import org.endstone.proxy.backend.BackendConnector;
import org.endstone.proxy.backend.ProxyConnection;
import org.endstone.proxy.backend.UnsupportedVersionPairException;
import org.endstone.proxy.crypto.BedrockCrypto;
import org.endstone.proxy.network.NetworkSettingsNegotiationResult;
import org.endstone.proxy.network.NetworkSettingsNegotiator;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.IdentityTranslator898;
import org.endstone.proxy.palette.BackendPaletteStore;
import org.endstone.proxy.resource.BackendPackCache;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.endstone.proxy.session.ProxySessionProfile;
import org.endstone.proxy.session.ConnectedPlayerRegistry;

import javax.crypto.SecretKey;
import java.security.KeyPair;

public final class InitialClientPacketHandler implements BedrockPacketHandler {
    private final ListenerSession session;
    private final NetworkSettingsNegotiator networkSettingsNegotiator;
    private final BackendConnector backendConnector;
    private final ClientLoginAuthenticator authenticator;
    private final OfflineLoginForge offlineLoginForge;
    private final ConnectedPlayerRegistry connectedPlayers;
    private final Runnable playerCountChanged;
    private final ProxyResourcePackRegistry proxyResourcePackRegistry;
    private final BackendPaletteStore backendPaletteStore;
    private final BackendPackCache backendPackCache;
    private SecretKey clientEncryptionKey;
    private ProxyConnection connection;

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, ProxyResourcePackRegistry.empty());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, BackendPaletteStore.disabled());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, backendPaletteStore,
                BackendPackCache.disabled());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache
    ) {
        this.session = session;
        this.networkSettingsNegotiator = networkSettingsNegotiator;
        this.backendConnector = backendConnector;
        this.authenticator = authenticator;
        this.offlineLoginForge = offlineLoginForge;
        this.connectedPlayers = connectedPlayers;
        this.playerCountChanged = playerCountChanged;
        this.proxyResourcePackRegistry = proxyResourcePackRegistry != null
                ? proxyResourcePackRegistry
                : ProxyResourcePackRegistry.empty();
        this.backendPaletteStore = backendPaletteStore != null
                ? backendPaletteStore
                : BackendPaletteStore.disabled();
        this.backendPackCache = backendPackCache != null ? backendPackCache : BackendPackCache.disabled();
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        NetworkSettingsNegotiationResult result = networkSettingsNegotiator.handle(packet);
        if (result instanceof NetworkSettingsNegotiationResult.Accepted accepted) {
            session.setClientCodec(accepted.clientCodec());
            session.setCodec(accepted.clientCodec());
            CodecDefinitionState.installFallbacks(session);
            session.sendPacketImmediately(accepted.networkSettings());
            session.setCompression(accepted.networkSettings().getCompressionAlgorithm());
            if (ProxyConnection.isPacketTracingConfigured()) {
                System.out.printf(
                        "Accepted %s using protocol %d.%n",
                        session.getSocketAddress(),
                        accepted.clientCodec().getProtocolVersion()
                );
            }
            return PacketSignal.HANDLED;
        }

        NetworkSettingsNegotiationResult.Rejected rejected = (NetworkSettingsNegotiationResult.Rejected) result;
        session.sendPacketImmediately(rejected.playStatus());
        session.disconnect("disconnectionScreen.outdatedClient");
        // The protocol number is the point of this line. A client newer than the proxy is how a new
        // Minecraft release announces itself, and that number is the first thing needed to add
        // support for it — without it the only clue is "somebody could not join".
        System.out.printf(
                "Rejected %s with %s: client protocol %d, proxy speaks up to %d (%s).%n",
                session.getSocketAddress(),
                rejected.playStatus().getStatus(),
                rejected.requestedProtocol(),
                CanonicalProtocol.newest().protocolVersion(),
                CanonicalProtocol.newest().minecraftVersion()
        );
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            if (session.clientCodec() == null) {
                session.disconnect("Network settings have not been negotiated");
                return PacketSignal.HANDLED;
            }

            ClientLogin clientLogin = authenticator.authenticate(packet);
            KeyPair keyPair = BedrockCrypto.createKeyPair();
            byte[] token = BedrockCrypto.randomToken();
            clientEncryptionKey = BedrockCrypto.secretKey(keyPair.getPrivate(), clientLogin.identityPublicKey(), token);

            connection = new ProxyConnection(
                    session,
                    new ProxySessionProfile(
                            session.clientCodec(),
                            session.clientCodec(),
                            session.clientCodec(),
                            IdentityTranslator898.INSTANCE
                    ),
                    clientLogin,
                    keyPair,
                    offlineLoginForge.forge(keyPair, clientLogin),
                    proxyResourcePackRegistry,
                    backendPaletteStore,
                    backendPackCache
            );

            ConnectedPlayerRegistry.RegistrationResult registration = connectedPlayers.register(connection);
            if (registration == ConnectedPlayerRegistry.RegistrationResult.DUPLICATE_XUID) {
                session.disconnect("This Xbox account is already connected to the proxy");
                connection = null;
                clientEncryptionKey = null;
                return PacketSignal.HANDLED;
            }
            if (registration == ConnectedPlayerRegistry.RegistrationResult.FULL) {
                session.disconnect("Proxy is full");
                connection = null;
                clientEncryptionKey = null;
                return PacketSignal.HANDLED;
            }
            session.setProxyConnection(connection);
            playerCountChanged.run();
            System.out.printf(
                    "Player %s (XUID %s) joined the proxy from %s%s.%n",
                    clientLogin.authData().displayName(),
                    clientLogin.authData().xuid(),
                    // A bridged player's socket address is the bridge's loopback one, which is identical
                    // for all of them. Report the address the bridge stamped in instead.
                    connection.clientAddress(),
                    clientLogin.isJavaEdition() ? " (a bridged edition)" : ""
            );

            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(BedrockCrypto.handshakeJwt(keyPair, token));
            session.sendPacketImmediately(handshake);
            session.enableEncryption(clientEncryptionKey);
            return PacketSignal.HANDLED;
        } catch (Exception exception) {
            session.disconnect("Unable to authenticate with Xbox Live");
            throw new IllegalStateException("Unable to authenticate client login", exception);
        }
    }

    @Override
    public PacketSignal handle(ClientToServerHandshakePacket packet) {
        if (connection == null || clientEncryptionKey == null) {
            session.disconnect("Login handshake was not initialized");
            return PacketSignal.HANDLED;
        }

        try {
            backendConnector.connect(connection);
        } catch (Exception exception) {
            // connect() reports failure through the activation before it throws, so by now the join
            // try-list may already be working on the next candidate. Kicking here would end the
            // session it is trying to save.
            if (connection.isJoinSequenceActive()) {
                return PacketSignal.HANDLED;
            }
            session.disconnect(exception instanceof UnsupportedVersionPairException
                    ? exception.getMessage()
                    : "Unable to connect to backend server");
            throw new IllegalStateException("Unable to connect to backend server", exception);
        }
        return PacketSignal.HANDLED;
    }
}
