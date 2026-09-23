package org.endstone.proxy.backend;

import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.listener.ListenerSession;
import org.endstone.proxy.session.ProxySessionProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Where a backend switch leaves the player when the backend places them during the reset.
 *
 * <p>Taken from a real switch onto a PowerNukkitX 3.0.5 backend: StartGame said y=32769.62 (the
 * staging point it gives a player with no saved position), then {@code Respawn(SERVER_READY)} at
 * (0, 1.62, 0) and a teleport to the real spawn - all inside the reset window, all suppressed. The
 * client was landed at the staging point and sat on "Building terrain" for good.</p>
 */
final class BackendSwitchResetPositionTest {
    private static final long BACKEND_PLAYER = 318;
    private static final Vector3f STAGING = Vector3f.from(0.64f, 32769.62f, 0f);

    private final EmbeddedChannel clientChannel = new EmbeddedChannel();
    private final EmbeddedChannel backendChannel = new EmbeddedChannel();

    @AfterEach
    void close() {
        clientChannel.finishAndReleaseAll();
        backendChannel.finishAndReleaseAll();
    }

    @Test
    void theTargetDimensionUsesTheRespawnPositionNotTheStagingPoint() {
        RecordingClient client = new RecordingClient(clientChannel);
        BackendSwitchReset reset = startReset(client);

        reset.rememberBackendPosition(connectionOf(client), Vector3f.from(0f, 1.62f, 0f), null, "respawn");
        client.sent.clear();
        reset.handleDimensionChangeSuccess(connectionOf(client));

        ChangeDimensionPacket change = last(client.sent, ChangeDimensionPacket.class);
        assertEquals(Vector3f.from(0f, 1.62f, 0f), change.getPosition());
    }

    @Test
    void completionLandsThePlayerWhereTheBackendLastTeleportedThem() {
        RecordingClient client = new RecordingClient(clientChannel);
        BackendSwitchReset reset = startReset(client);
        ProxyConnection connection = connectionOf(client);

        reset.rememberBackendPosition(connection, Vector3f.from(0f, 1.62f, 0f), null, "respawn");
        reset.handleDimensionChangeSuccess(connection);
        reset.rememberBackendPosition(connection, Vector3f.from(-0.5f, 17.62f, -0.5f),
                Vector3f.from(10f, 90f, 90f), "move");
        client.sent.clear();
        reset.handleDimensionChangeSuccess(connection);

        assertFalse(reset.isActive());
        MovePlayerPacket move = last(client.sent, MovePlayerPacket.class);
        assertEquals(Vector3f.from(-0.5f, 17.62f, -0.5f), move.getPosition());
        assertEquals(Vector3f.from(10f, 90f, 90f), move.getRotation());
    }

    @Test
    void aPositionAfterCompletionIsIgnored() {
        RecordingClient client = new RecordingClient(clientChannel);
        BackendSwitchReset reset = startReset(client);
        ProxyConnection connection = connectionOf(client);
        reset.handleDimensionChangeSuccess(connection);
        reset.handleDimensionChangeSuccess(connection);
        client.sent.clear();

        // Live movement after the reset goes straight through; the reset must not act on it.
        reset.rememberBackendPosition(connection, Vector3f.from(5f, 5f, 5f), null, "move");

        assertEquals(List.of(), client.sent);
    }

    private final java.util.Map<RecordingClient, ProxyConnection> connections = new java.util.HashMap<>();

    private ProxyConnection connectionOf(RecordingClient client) {
        return connections.get(client);
    }

    private BackendSwitchReset startReset(RecordingClient client) {
        ProxyConnection connection = new ProxyConnection(client.session, null, null, null, null, null);
        BedrockCodec codec = CanonicalProtocol.newest().codec();
        connection.setSessionProfile(new ProxySessionProfile(codec, codec, codec, null));
        connection.setBackendPlayerRuntimeEntityId(BACKEND_PLAYER);
        connections.put(client, connection);

        BackendSession backend = new BackendSession(new BedrockPeer(backendChannel, BackendSession::new), 0);
        StartGamePacket startGame = new StartGamePacket();
        startGame.setDimensionId(0);
        startGame.setPlayerPosition(STAGING);
        startGame.setRotation(Vector2f.ZERO);
        return BackendSwitchReset.start(connection, backend, "survival", 0, startGame);
    }

    private static <T extends BedrockPacket> T last(List<BedrockPacket> packets, Class<T> type) {
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (type.isInstance(packets.get(i))) {
                return type.cast(packets.get(i));
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " was sent in " + packets);
    }

    /**
     * Stands in for the player's connection on this branch, where the client is a concrete
     * {@link ListenerSession}: everything it sends goes through its peer, so the peer records it.
     */
    private static final class RecordingClient {
        private final List<BedrockPacket> sent = new ArrayList<>();
        private final ListenerSession session;

        RecordingClient(EmbeddedChannel channel) {
            BedrockPeer peer = new BedrockPeer(channel, ListenerSessionFactory.INSTANCE) {
                @Override
                public void sendPacket(int senderClientId, int targetClientId, BedrockPacket packet) {
                    sent.add(packet);
                }

                @Override
                public void sendPacketImmediately(int senderClientId, int targetClientId, BedrockPacket packet) {
                    sent.add(packet);
                }
            };
            this.session = new ListenerSession(peer, 0, closed -> { });
        }
    }

    private enum ListenerSessionFactory implements org.cloudburstmc.protocol.bedrock.BedrockSessionFactory {
        INSTANCE;

        @Override
        public org.cloudburstmc.protocol.bedrock.BedrockSession createSession(BedrockPeer peer, int subClientId) {
            return new ListenerSession(peer, subClientId, closed -> { });
        }
    }
}
