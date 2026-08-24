package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BackendSwitchReset {
    private static final int DIMENSION_OVERWORLD = 0;
    private static final int DIMENSION_NETHER = 1;
    private static final int DIMENSION_END = 2;
    private static final int RESET_CHUNK_RADIUS = 3;
    // Last-resort timeout for completing a phase when the client never acknowledges the injected
    // dimension change. The client normally acks within a few hundred ms, but under load it has
    // been observed to take several seconds to process the ChangeDimension + the new backend's
    // join burst. If the fallback fires too early it completes the switch before the client is
    // actually in the new world, which then makes the client's late loading-screen-start get
    // misread as a death respawn. Keep this comfortably longer than the worst observed client
    // delay so real client acks drive completion whenever they arrive.
    private static final long ACK_FALLBACK_MILLIS = 8000;

    private static final ByteBuf EMPTY_OVERWORLD_CHUNK = createChunkData(24);
    private static final ByteBuf EMPTY_NETHER_CHUNK = createChunkData(8);
    private static final ByteBuf EMPTY_END_CHUNK = createChunkData(16);
    private static final AtomicInteger LOADING_SCREEN_IDS = new AtomicInteger(1);

    private final BackendSession backend;
    private final String backendName;
    private final long backendRuntimeEntityId;
    private final long clientRuntimeEntityId;
    private final int targetDimension;
    private final Vector3f targetPosition;
    private final Vector2f targetRotation;
    private final boolean secondDimensionChangeRequired;
    private final BackendSwitchInputState inputState;

    private Phase phase = Phase.AWAITING_FIRST_ACK;

    private BackendSwitchReset(
            BackendSession backend,
            String backendName,
            long backendRuntimeEntityId,
            long clientRuntimeEntityId,
            int targetDimension,
            Vector3f targetPosition,
            Vector2f targetRotation,
            boolean secondDimensionChangeRequired,
            int targetInputLockData
    ) {
        this.backend = backend;
        this.backendName = backendName;
        this.backendRuntimeEntityId = backendRuntimeEntityId;
        this.clientRuntimeEntityId = clientRuntimeEntityId;
        this.targetDimension = targetDimension;
        this.targetPosition = targetPosition;
        this.targetRotation = targetRotation;
        this.secondDimensionChangeRequired = secondDimensionChangeRequired;
        this.inputState = new BackendSwitchInputState(targetInputLockData);
    }

    public static BackendSwitchReset start(
            ProxyConnection connection,
            BackendSession backend,
            String backendName,
            int sourceDimension,
            StartGamePacket startGame
    ) {
        return start(connection, backend, backendName, sourceDimension, startGame, 0);
    }

    static BackendSwitchReset start(
            ProxyConnection connection,
            BackendSession backend,
            String backendName,
            int sourceDimension,
            StartGamePacket startGame,
            int targetInputLockData
    ) {
        int targetDimension = startGame.getDimensionId();
        Vector3f targetPosition = startGame.getPlayerPosition() == null ? Vector3f.ZERO : startGame.getPlayerPosition();
        Vector2f targetRotation = startGame.getRotation() == null ? Vector2f.ZERO : startGame.getRotation();
        long backendRuntimeEntityId = connection.backendPlayerRuntimeEntityId();
        long clientRuntimeEntityId = connection.clientPlayerRuntimeEntityId();
        boolean needsFakeDimension = sourceDimension == targetDimension;

        BackendSwitchReset reset = new BackendSwitchReset(
                backend,
                backendName,
                backendRuntimeEntityId,
                clientRuntimeEntityId,
                targetDimension,
                targetPosition,
                targetRotation,
                needsFakeDimension,
                targetInputLockData
        );
        connection.setBackendSwitchReset(reset);

        int firstDimension = needsFakeDimension ? alternateDimension(targetDimension) : targetDimension;
        Vector3f firstPosition = needsFakeDimension ? targetPosition.add(2000, 0, 2000) : targetPosition;
        // A normal TransferPacket reconnect clears inputpermission state as a side effect. A
        // seamless proxy handoff does not, so explicitly clear the source backend's mask before
        // entering the dimension transition. This also releases transient form/input locks that
        // would otherwise leave chat working while movement and camera input stay frozen.
        connection.client().sendPacket(reset.inputState.clearSource(firstPosition));
        reset.injectPosition(connection, firstPosition);
        reset.injectDimensionChange(connection, firstDimension, firstPosition, true);
        reset.scheduleAckFallback(connection);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Started backend switch reset for %s: sourceDimension=%d targetDimension=%d firstDimension=%d runtimeEntityId=%d secondPhase=%s.%n",
                    backendName,
                    sourceDimension,
                    targetDimension,
                    firstDimension,
                    backendRuntimeEntityId,
                    needsFakeDimension
            );
        }
        return reset;
    }

    public synchronized void rememberTargetInputLocks(int lockComponentData) {
        if (phase != Phase.COMPLETE) {
            inputState.rememberTarget(lockComponentData);
        }
    }

    public synchronized boolean handleDimensionChangeSuccess(ProxyConnection connection) {
        if (phase == Phase.AWAITING_FIRST_ACK) {
            if (secondDimensionChangeRequired) {
                phase = Phase.AWAITING_SECOND_ACK;
                injectPosition(connection, targetPosition.add(-2000, 0, -2000));
                injectDimensionChange(connection, targetDimension, targetPosition, true);
                scheduleAckFallback(connection);
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Backend switch reset phase 1 complete for %s; sent target dimension %d.%n",
                            backendName,
                            targetDimension
                    );
                }
                return true;
            }
            complete(connection);
            return true;
        }
        if (phase == Phase.AWAITING_SECOND_ACK) {
            complete(connection);
            return true;
        }
        return false;
    }

    public boolean handleLoadingScreen(ProxyConnection connection, ServerboundLoadingScreenPacket packet) {
        if (packet.getType() != org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.END_LOADING_SCREEN) {
            return true;
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Treating loading-screen end as backend switch reset ack for %s: id=%s phase=%s.%n",
                    backendName,
                    packet.getLoadingScreenId(),
                    phase
            );
        }
        return handleDimensionChangeSuccess(connection);
    }

    public synchronized boolean handleTargetWorldRequest(ProxyConnection connection, int dimension) {
        boolean waitingForTargetDimension = phase == Phase.AWAITING_SECOND_ACK
                || (!secondDimensionChangeRequired && phase == Phase.AWAITING_FIRST_ACK);
        if (!waitingForTargetDimension || dimension != targetDimension) {
            return false;
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Treating target-world subchunk request as backend switch reset ack for %s: dimension=%d.%n",
                    backendName,
                    dimension
            );
        }
        complete(connection);
        return true;
    }

    public synchronized boolean isActive() {
        return phase != Phase.COMPLETE;
    }

    /**
     * Drops this reset without completing it, for when the backend it is driving dies mid-switch.
     *
     * <p>Letting it complete instead would push position and chunk-radius packets at a dead session
     * and — the part that actually breaks a player — hand {@code setPendingPostSwitchInit} to that
     * dead session, where it would swallow the token the <em>next</em> backend is waiting on and
     * leave the player respawned but never initialized. The scheduled ack fallbacks check
     * {@link #isActive()}, so marking the phase complete disarms them too.</p>
     */
    public synchronized void abandon(ProxyConnection connection) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.COMPLETE;
        connection.clearBackendSwitchReset(this);
        // The world these chunks belong to is gone with the backend that sent them; replaying them at
        // whatever the player lands on next would paint the wrong terrain, so drop and free them.
        connection.releaseDeferredSwitchWorldState();
        System.out.printf("Abandoned backend switch reset for %s; that backend is gone.%n", backendName);
    }

    private synchronized void complete(ProxyConnection connection) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.COMPLETE;
        connection.clearBackendSwitchReset(this);
        connection.setPlayerDimensionId(targetDimension);
        // Mark the player as fully initialized so cross-protocol entity suppression
        // (e.g. AddItemEntityPacket) stops after the switch reset completes. Without
        // this, dropped items are invisible because suppressInitialCrossProtocolEntitySpawn
        // keeps filtering them until hasInitialLocalPlayerInitialized() is true.
        connection.markInitialLocalPlayerInitialized();

        StopSoundPacket stopSound = new StopSoundPacket();
        stopSound.setSoundName("portal.travel");
        stopSound.setStoppingAllSound(true);
        connection.client().sendPacket(stopSound);

        injectPosition(connection, targetPosition);

        replayDeferredPlayerState(connection);
        replayDeferredWorldState(connection);

        // Restore what the target backend requested (normally zero). Sending the zero packet is
        // intentional even when neither backend advertised a mask: it forces the client to discard
        // stale locks left by a form or by the source backend, just as a real reconnect would.
        connection.client().sendPacket(inputState.restoreTarget(targetPosition));

        RequestChunkRadiusPacket chunkRadius = new RequestChunkRadiusPacket();
        chunkRadius.setRadius(connection.lastRequestedChunkRadius());
        chunkRadius.setMaxRadius(connection.lastRequestedMaxChunkRadius());
        backend.sendPacket(chunkRadius);

        // Deferring SetLocalPlayerAsInitialized is only right for a backend that actually emits a
        // post-switch SERVER_READY. A legacy backend (pre-v712) completes its respawn handshake that
        // way, and initializing before it leaves the player respawned-but-not-initialized — able to
        // move but unable to interact, with frozen air bubbles.
        //
        // A backend from v712 onward drives the loading-screen flow instead and never sends that
        // SERVER_READY, so waiting for it means waiting the full ACK_FALLBACK_MILLIS every time.
        // That is what made switching *to* a 1.26.30 backend fail while the reverse worked: the
        // player spent eight seconds unable to interact and the client gave up first. Initialize
        // immediately there — there is nothing to wait for.
        if (backendUsesLegacyRespawnHandshake(connection)) {
            connection.setPendingPostSwitchInit(backend, backendRuntimeEntityId);
            scheduleInitFallback(connection);
        } else {
            SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
            initialized.setRuntimeEntityId(backendRuntimeEntityId);
            backend.sendPacket(initialized);
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Initialized player immediately after switch to %s: backend protocol %d drives its own "
                                + "respawn and sends no post-switch SERVER_READY to wait for.%n",
                        backendName,
                        connection.sessionProfile().backendCodec().getProtocolVersion()
                );
            }
        }

        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Completed backend switch reset for %s: deferred player initialization until backend SERVER_READY runtimeEntityId=%d chunkRadius=%d maxRadius=%d.%n",
                    backendName,
                    backendRuntimeEntityId,
                    chunkRadius.getRadius(),
                    chunkRadius.getMaxRadius()
            );
        }
    }

    /**
     * Whether this backend completes a switch with the old {@code RespawnPacket(SERVER_READY)}
     * handshake rather than the loading-screen flow. {@code ServerboundLoadingScreenPacket} arrived
     * in {@code Bedrock_v712}; from there on the backend drives its own respawn.
     */
    private boolean backendUsesLegacyRespawnHandshake(ProxyConnection connection) {
        return connection.sessionProfile().backendCodec().getProtocolVersion() < 712;
    }

    private void scheduleInitFallback(ProxyConnection connection) {
        connection.client().getPeer().getChannel().eventLoop().schedule(() -> {
            long runtimeEntityId = connection.consumePendingPostSwitchInit(backend);
            if (runtimeEntityId <= 0) {
                return;
            }
            SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
            initialized.setRuntimeEntityId(runtimeEntityId);
            backend.sendPacket(initialized);
            System.out.printf(
                    "WARNING: Post-switch initialization fallback for %s: backend SERVER_READY not observed, sent SetLocalPlayerAsInitialized runtimeEntityId=%d.%n",
                    backendName,
                    runtimeEntityId
            );
        }, ACK_FALLBACK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void replayDeferredPlayerState(ProxyConnection connection) {
        java.util.List<BedrockPacket> deferred = connection.drainDeferredSwitchPlayerState();
        if (deferred.isEmpty()) {
            return;
        }
        for (BedrockPacket statePacket : deferred) {
            connection.client().sendPacket(statePacket);
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Replayed %d deferred local-player state packet(s) for %s after switch reset completed.%n",
                    deferred.size(),
                    backendName
            );
        }
    }

    /**
     * Replays the chunks, entity spawns and other persistent world state the new backend streamed
     * while the client was being bounced through the fake dimension. Order is preserved so each
     * publisher update still precedes the chunks it scopes, entity removals remain after their spawn,
     * and the real chunk data lands after — and therefore overwrites — the empty chunks
     * {@link #injectEmptyChunks} seeded around the target position.
     *
     * <p>Without this the player is permanently stranded in a void on any backend quick enough to
     * finish its join chunk burst inside the reset window, because the backend counts those chunks as
     * delivered and never sends them again.</p>
     */
    private void replayDeferredWorldState(ProxyConnection connection) {
        java.util.List<BedrockPacket> deferred = connection.drainDeferredSwitchWorldState();
        if (deferred.isEmpty()) {
            return;
        }
        int replayed = 0;
        for (BedrockPacket worldPacket : deferred) {
            if (!connection.client().isConnected()) {
                // These hold pooled chunk buffers, so a client that left mid-replay still has to have
                // the remainder freed rather than dropped on the floor.
                ReferenceCountUtil.release(worldPacket);
                continue;
            }
            connection.client().sendPacket(worldPacket);
            // Deferred entity spawns only become part of the client's world here. Remember them now
            // so the next backend switch removes them before installing that backend's entities. Doing
            // this when the packet was captured would track entities the client never saw if this reset
            // were abandoned instead of replayed.
            connection.clientWorldState().track(worldPacket);
            replayed++;
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Replayed %d of %d deferred world-state packet(s) for %s after switch reset completed.%n",
                    replayed,
                    deferred.size(),
                    backendName
            );
        }
    }

    private void injectPosition(ProxyConnection connection, Vector3f position) {
        MovePlayerPacket move = new MovePlayerPacket();
        move.setRuntimeEntityId(clientRuntimeEntityId);
        move.setPosition(position);
        move.setRotation(Vector3f.from(targetRotation.getX(), targetRotation.getY(), targetRotation.getY()));
        move.setMode(MovePlayerPacket.Mode.RESPAWN);
        move.setOnGround(false);
        move.setRidingRuntimeEntityId(0);
        connection.client().sendPacket(move);
    }

    private void injectDimensionChange(ProxyConnection connection, int dimension, Vector3f position, boolean chunks) {
        ChangeDimensionPacket change = new ChangeDimensionPacket();
        change.setDimension(dimension);
        change.setPosition(position);
        change.setRespawn(true);
        change.setLoadingScreenId(LOADING_SCREEN_IDS.getAndIncrement());
        connection.client().sendPacket(change);

        if (chunks) {
            injectChunkPublisherUpdate(connection, position);
            injectEmptyChunks(connection, position, dimension);
        }

        PlayerActionPacket action = new PlayerActionPacket();
        action.setRuntimeEntityId(clientRuntimeEntityId);
        action.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
        action.setBlockPosition(Vector3i.ZERO);
        action.setResultPosition(Vector3i.ZERO);
        action.setFace(0);
        connection.client().sendPacket(action);
    }

    private void scheduleAckFallback(ProxyConnection connection) {
        connection.client().getPeer().getChannel().eventLoop().schedule(() -> {
            if (!isActive()) {
                return;
            }
            System.out.printf(
                    "WARNING: Backend switch reset ack fallback for %s: phase=%s.%n",
                    backendName,
                    phase
            );
            handleDimensionChangeSuccess(connection);
        }, ACK_FALLBACK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void injectChunkPublisherUpdate(ProxyConnection connection, Vector3f position) {
        NetworkChunkPublisherUpdatePacket update = new NetworkChunkPublisherUpdatePacket();
        update.setPosition(toBlockPosition(position));
        update.setRadius(RESET_CHUNK_RADIUS);
        connection.client().sendPacket(update);
    }

    private static void injectEmptyChunks(ProxyConnection connection, Vector3f position, int dimension) {
        int chunkX = floor(position.getX()) >> 4;
        int chunkZ = floor(position.getZ()) >> 4;
        for (int x = -RESET_CHUNK_RADIUS; x <= RESET_CHUNK_RADIUS; x++) {
            for (int z = -RESET_CHUNK_RADIUS; z <= RESET_CHUNK_RADIUS; z++) {
                LevelChunkPacket chunk = new LevelChunkPacket();
                chunk.setChunkX(chunkX + x);
                chunk.setChunkZ(chunkZ + z);
                chunk.setDimension(dimension);
                chunk.setSubChunksLength(1);
                chunk.setCachingEnabled(false);
                chunk.setData(emptyChunkData(dimension).retainedSlice());
                connection.client().sendPacket(chunk);
            }
        }
    }

    private static ByteBuf emptyChunkData(int dimension) {
        return switch (dimension) {
            case DIMENSION_NETHER -> EMPTY_NETHER_CHUNK;
            case DIMENSION_END -> EMPTY_END_CHUNK;
            default -> EMPTY_OVERWORLD_CHUNK;
        };
    }

    private static ByteBuf createChunkData(int biomeSections) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(8);
        buffer.writeByte(0);
        writePalette(buffer, 0);
        for (int i = 1; i < biomeSections; i++) {
            buffer.writeByte((127 << 1) | 1);
        }
        buffer.writeByte(0);
        return buffer.asReadOnly();
    }

    private static void writePalette(ByteBuf buffer, int runtimeId) {
        buffer.writeByte((1 << 1) | 1);
        buffer.writeZero(512);
        VarInts.writeInt(buffer, 1);
        VarInts.writeInt(buffer, runtimeId);
    }

    private static int alternateDimension(int dimension) {
        return dimension == DIMENSION_OVERWORLD ? DIMENSION_END : DIMENSION_OVERWORLD;
    }

    private static Vector3i toBlockPosition(Vector3f position) {
        return Vector3i.from(floor(position.getX()), floor(position.getY()), floor(position.getZ()));
    }

    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    private enum Phase {
        AWAITING_FIRST_ACK,
        AWAITING_SECOND_ACK,
        COMPLETE
    }
}
