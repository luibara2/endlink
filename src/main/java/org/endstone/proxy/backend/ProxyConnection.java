package org.endstone.proxy.backend;

import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.math.vector.Vector3f;
import org.endstone.proxy.auth.ClientLogin;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.palette.BackendPaletteStore;
import org.endstone.proxy.palette.CrossBackendPalette;
import org.endstone.proxy.resource.BackendPackCache;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.endstone.proxy.session.ProxySessionProfile;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProxyConnection {
    /** Packet tracing is an opt-in diagnostic and is disabled in production by default. */
    private static final boolean LOG_PACKETS = Boolean.getBoolean("proxy.logPackets");
    private static final int CONFIGURED_PACKET_TRACE_MILLIS =
            Math.max(0, Integer.getInteger("proxy.traceMillis", 0));
    private static final long FAILOVER_EPISODE_WINDOW_MILLIS = 30_000;
    private static final int MAX_FAILOVERS_PER_EPISODE = 3;
    /** Comfortably longer than a full /server retry sequence; see {@link #beginBackendSwitch}. */
    private static final long SWITCH_LOCK_MAX_MILLIS = 120_000;
    /**
     * Cap on world-state packets held across a switch reset; see
     * {@link #addDeferredSwitchWorldState}. Sized to cover a full 32-chunk view (4225 columns) plus
     * the publisher updates and block edits interleaved with them, so a normal join burst always fits
     * and only a backend streaming for the entire ack-fallback window can overflow it.
     */
    private static final int MAX_DEFERRED_SWITCH_WORLD_STATE = 8192;

    private final org.endstone.proxy.listener.ListenerSession client;
    private ProxySessionProfile sessionProfile;
    private final ClientLogin clientLogin;
    private final KeyPair keyPair;
    private LoginPacket backendLogin;
    private final ProxyResourcePackRegistry proxyResourcePackRegistry;
    private final BackendPackCache backendPackCache;
    private final CrossBackendPalette crossBackendPalette;
    private Boolean clientBlockIdsHashed;
    private final ClientWorldState clientWorldState = new ClientWorldState();
    private BackendSession backend;
    private String backendName;
    private BackendSession pendingBackend;
    private boolean backendSwitchInProgress;
    private String backendSwitchTarget;
    private long backendSwitchStartedAtMillis;
    private boolean failoverInProgress;
    private long lastFailoverStartedAtMillis;
    private long lastProxyCommandAtMillis = Long.MIN_VALUE / 2;
    private boolean joinSequenceActive;
    private List<BackendConfig> remainingJoinCandidates = new ArrayList<>();
    private long joinAttemptId;
    private long lastHandledJoinAttemptId = -1;
    private int failoversInEpisode;
    private boolean clientJoinedWorld;
    private int lastRequestedChunkRadius = 12;
    private int lastRequestedMaxChunkRadius = 12;
    private long backendPlayerRuntimeEntityId;
    private long clientPlayerRuntimeEntityId;
    private long backendPlayerUniqueEntityId;
    private long clientPlayerUniqueEntityId;
    private long nextSyntheticClientRuntimeEntityId = 1_000_000_000L;
    private final Map<Long, Long> backendToClientRuntimeIds = new HashMap<>();
    private final Map<Long, Long> clientToBackendRuntimeIds = new HashMap<>();
    private final Map<Long, byte[]> syntheticClientChunkBlobs = new HashMap<>();
    private final List<BedrockPacket> deferredSwitchPlayerState = new ArrayList<>();
    private final List<BedrockPacket> deferredSwitchWorldState = new ArrayList<>();
    private final List<BedrockPacket> deferredInitialEntitySpawns = new ArrayList<>();
    private boolean deferredSwitchWorldStateOverflowed;
    private int playerDimensionId;
    private BackendSwitchReset backendSwitchReset;
    private long packetTraceUntilNanos;
    private final long createdAtNanos = System.nanoTime();
    /** Null until the backend's pack list has been merged; see {@link #isProxyServedPack}. */
    private Set<UUID> proxyServedPacks;
    private long clientboundTraceSequence;
    private long serverboundTraceSequence;
    private boolean firstLevelChunkForwarded;
    private boolean awaitingDeathRespawnLoadingScreenEnd;
    private long pendingPostSwitchInitBackendRuntimeEntityId;
    private BackendSession pendingPostSwitchInitBackend;
    private Vector3f saneJoinPosition;
    private boolean backendJoinPositionSynced;
    private boolean initialServerSearchingSeen;
    private Vector3f initialServerSearchingPosition;
    private boolean initialLoadingScreenStarted;
    private boolean initialBackendLoadingScreenStarted;
    private boolean initialBackendRespawnReadySent;
    private boolean initialSyntheticServerReadySent;
    private boolean initialSyntheticPlayerSpawnSent;
    private boolean initialLocalPlayerInitialized;
    private boolean initialClientChunkCacheStatusSeen;
    private int forwardedLevelChunks;

    public ProxyConnection(
            org.endstone.proxy.listener.ListenerSession client,
            ProxySessionProfile sessionProfile,
            ClientLogin clientLogin,
            KeyPair keyPair,
            LoginPacket backendLogin,
            ProxyResourcePackRegistry proxyResourcePackRegistry
    ) {
        this(client, sessionProfile, clientLogin, keyPair, backendLogin, proxyResourcePackRegistry, null);
    }

    public ProxyConnection(
            org.endstone.proxy.listener.ListenerSession client,
            ProxySessionProfile sessionProfile,
            ClientLogin clientLogin,
            KeyPair keyPair,
            LoginPacket backendLogin,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore
    ) {
        this(client, sessionProfile, clientLogin, keyPair, backendLogin, proxyResourcePackRegistry,
                backendPaletteStore, null);
    }

    public ProxyConnection(
            org.endstone.proxy.listener.ListenerSession client,
            ProxySessionProfile sessionProfile,
            ClientLogin clientLogin,
            KeyPair keyPair,
            LoginPacket backendLogin,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache
    ) {
        this.client = client;
        this.sessionProfile = sessionProfile;
        this.clientLogin = clientLogin;
        this.keyPair = keyPair;
        this.backendLogin = backendLogin;
        this.proxyResourcePackRegistry = proxyResourcePackRegistry != null
                ? proxyResourcePackRegistry
                : ProxyResourcePackRegistry.empty();
        this.crossBackendPalette = new CrossBackendPalette(backendPaletteStore);
        this.backendPackCache = backendPackCache != null ? backendPackCache : BackendPackCache.disabled();
    }

    public org.endstone.proxy.listener.ListenerSession client() {
        return client;
    }

    public ProxySessionProfile sessionProfile() {
        return sessionProfile;
    }

    public synchronized void setSessionProfile(ProxySessionProfile sessionProfile) {
        if (sessionProfile == null) {
            throw new IllegalArgumentException("sessionProfile cannot be null");
        }
        this.sessionProfile = sessionProfile;
        client.setSessionProfile(sessionProfile);
    }

    public ClientLogin clientLogin() {
        return clientLogin;
    }

    /**
     * The player's address as anything outside this process should see it.
     *
     * <p>For a Bedrock player that is simply their socket address. For a bridged player it is the address
     * the bridge stamped into their login: their real socket address is the loopback one the embedded
     * the bridge dialled from, which is the same for every bridged player and tells a backend nothing.
     * Anything reporting, logging or verifying a player's address wants this, not
     * {@code client().getSocketAddress()}.</p>
     */
    public java.net.SocketAddress clientAddress() {
        java.net.InetSocketAddress bridgeAddress = clientLogin == null ? null : clientLogin.bridgeClientAddress();
        return bridgeAddress != null ? bridgeAddress : client.getSocketAddress();
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public LoginPacket backendLogin() {
        return backendLogin;
    }

    public synchronized void setBackendLogin(LoginPacket backendLogin) {
        if (backendLogin == null) {
            throw new IllegalArgumentException("backendLogin cannot be null");
        }
        this.backendLogin = backendLogin;
    }

    public ProxyResourcePackRegistry proxyResourcePackRegistry() {
        return proxyResourcePackRegistry;
    }

    /**
     * Where backend packs seen on this connection are kept. Shared by every connection: a pack learned
     * from one player is served to all of them.
     */
    public BackendPackCache backendPackCache() {
        return backendPackCache;
    }

    /**
     * Records which packs the proxy told this client it would serve itself.
     *
     * <p>Decided once, when the backend's pack list is merged, and read again when the client asks for
     * the bytes. The two have to agree: the merge can decide the proxy's cached copy of a pack is out
     * of date and leave the backend to serve it, and answering the chunk request from the proxy anyway
     * would hand the client exactly the copy that was just rejected.</p>
     */
    public synchronized void rememberProxyServedPacks(Set<UUID> packIds) {
        this.proxyServedPacks = packIds == null ? null : Set.copyOf(packIds);
    }

    /**
     * Whether the proxy is the one serving this pack to this client.
     *
     * <p>Before the merge has run there is no decision to consult, so this falls back to what the
     * registry holds - the behaviour every join had before the decision existed.</p>
     */
    public boolean isProxyServedPack(UUID packId) {
        Set<UUID> decided;
        synchronized (this) {
            decided = proxyServedPacks;
        }
        if (decided == null) {
            return proxyResourcePackRegistry.isProxyPack(packId);
        }
        return decided.contains(packId);
    }

    /**
     * This player's cross-backend item and entity registries. Decided at login and unchangeable
     * afterwards, because that is when Bedrock reads them; see {@link CrossBackendPalette}.
     */
    public CrossBackendPalette crossBackendPalette() {
        return crossBackendPalette;
    }

    /**
     * Whether this client reads block ids as hashes, fixed by the StartGame it logged in with.
     *
     * <p>Null until the first StartGame reaches the client. Like the registries above this cannot
     * change afterwards, which is why a backend on the other scheme has to be reached by a reconnect
     * rather than a handoff.</p>
     */
    public synchronized Boolean clientBlockIdsHashed() {
        return clientBlockIdsHashed;
    }

    /** Recorded once, from the first StartGame forwarded to the client; later ones cannot change it. */
    public synchronized void rememberClientBlockIdsHashed(boolean hashed) {
        if (clientBlockIdsHashed == null) {
            clientBlockIdsHashed = hashed;
        }
    }

    public synchronized BackendSession backend() {
        return backend;
    }

    public synchronized String backendName() {
        return backendName;
    }

    public synchronized BackendSession pendingBackend() {
        return pendingBackend;
    }

    public synchronized boolean isSwitchingBackend() {
        return backendSwitchInProgress;
    }

    public synchronized void setBackend(String backendName, BackendSession backend) {
        if (backendName == null || backendName.isBlank()) {
            throw new IllegalArgumentException("backendName cannot be blank");
        }
        this.backendName = backendName;
        this.backend = backend;
        backendSwitchInProgress = false;
        backendSwitchTarget = null;
        firstLevelChunkForwarded = false;
        saneJoinPosition = null;
        backendJoinPositionSynced = false;
        initialServerSearchingSeen = false;
        initialServerSearchingPosition = null;
        initialLoadingScreenStarted = false;
        initialBackendLoadingScreenStarted = false;
        initialBackendRespawnReadySent = false;
        initialSyntheticServerReadySent = false;
        initialSyntheticPlayerSpawnSent = false;
        initialLocalPlayerInitialized = false;
        initialClientChunkCacheStatusSeen = false;
        forwardedLevelChunks = 0;
        syntheticClientChunkBlobs.clear();
        deferredSwitchPlayerState.clear();
        releaseDeferredSwitchWorldState();
        deferredInitialEntitySpawns.clear();
        if (backend != null) {
            backend.setDisconnectClientOnClose(true);
        }
    }

    public synchronized BackendSession replaceBackend(String backendName, BackendSession backend) {
        BackendSession previous = this.backend;
        setBackend(backendName, backend);
        if (pendingBackend == backend) {
            pendingBackend = null;
        }
        if (previous != null) {
            previous.setDisconnectClientOnClose(false);
            previous.discardInboundPackets();
        }
        return previous;
    }

    public synchronized void setPendingBackend(BackendSession backend) {
        pendingBackend = backend;
    }

    public synchronized void clearPendingBackend(BackendSession backend) {
        if (pendingBackend == backend) {
            pendingBackend = null;
        }
    }

    /**
     * Claims the right to move this player to another backend.
     *
     * <p>The lock is released by whoever took it, but a stuck one strands the player on "already
     * connecting" with no way out short of reconnecting — which is exactly what happened when a
     * switch to an offline backend skipped its failure callback. {@link #SWITCH_LOCK_MAX_MILLIS}
     * bounds that: no legitimate switch runs longer than a retry sequence, so one that has is a lost
     * caller, not a slow one.</p>
     */
    public synchronized SwitchStart beginBackendSwitch(String backendName) {
        if (backendSwitchInProgress) {
            long heldForMillis = System.currentTimeMillis() - backendSwitchStartedAtMillis;
            if (heldForMillis < SWITCH_LOCK_MAX_MILLIS) {
                return SwitchStart.ALREADY_SWITCHING;
            }
            System.out.printf(
                    "Backend switch to %s has been in progress for %dms with no outcome; taking the"
                            + " switch over for %s.%n",
                    backendSwitchTarget,
                    heldForMillis,
                    backendName
            );
        }
        backendSwitchInProgress = true;
        backendSwitchTarget = backendName;
        backendSwitchStartedAtMillis = System.currentTimeMillis();
        return SwitchStart.STARTED;
    }

    public enum SwitchStart {
        STARTED,
        ALREADY_SWITCHING
    }

    public synchronized void finishBackendSwitch() {
        backendSwitchInProgress = false;
        backendSwitchTarget = null;
    }

    public synchronized String backendSwitchTarget() {
        return backendSwitchTarget;
    }

    /**
     * Marks the start of a failover: the backend the player was on has gone away and the proxy is
     * walking the fallback chain looking for one that will take them.
     *
     * <p>Until this finishes, {@link #backend()} points at a dead session. Serverbound packets are
     * therefore dropped rather than treated as "backend is not connected", which is what would
     * otherwise tear down the player's connection to the proxy — the exact outcome failover
     * exists to avoid.</p>
     *
     * <p>An unreachable fallback is not a loop risk: that attempt simply fails and the next
     * candidate is tried. A fallback that <em>accepts</em> the player and then immediately drops
     * them is, because each hop is a fresh backend loss that qualifies for failover all over again.
     * Hops that keep happening inside {@link #FAILOVER_EPISODE_WINDOW_MILLIS} are therefore counted
     * as one episode and capped.</p>
     */
    public synchronized FailoverStart beginFailover() {
        if (failoverInProgress) {
            return FailoverStart.ALREADY_RUNNING;
        }
        long now = System.currentTimeMillis();
        failoversInEpisode = now - lastFailoverStartedAtMillis <= FAILOVER_EPISODE_WINDOW_MILLIS
                ? failoversInEpisode + 1
                : 1;
        lastFailoverStartedAtMillis = now;
        if (failoversInEpisode > MAX_FAILOVERS_PER_EPISODE) {
            return FailoverStart.TOO_MANY;
        }
        failoverInProgress = true;
        return FailoverStart.STARTED;
    }

    public enum FailoverStart {
        STARTED,
        ALREADY_RUNNING,
        TOO_MANY
    }

    public synchronized void finishFailover() {
        failoverInProgress = false;
    }

    public synchronized boolean isFailingOver() {
        return failoverInProgress;
    }

    /**
     * Starts the join sequence: the ordered backends to try before giving up on a player who has
     * not reached a world yet.
     *
     * <p>While this is active every "the backend went away, kick the client" path must defer to
     * {@link JoinFailover} instead — there are four of them, and any one of them firing on its own
     * ends the session the sequence was about to rescue.</p>
     */
    public synchronized void beginJoinSequence(List<BackendConfig> candidates) {
        joinSequenceActive = true;
        remainingJoinCandidates = new ArrayList<>(candidates);
    }

    public synchronized boolean isJoinSequenceActive() {
        return joinSequenceActive;
    }

    public synchronized void endJoinSequence() {
        joinSequenceActive = false;
        remainingJoinCandidates.clear();
    }

    public synchronized BackendConfig nextJoinCandidate() {
        return remainingJoinCandidates.isEmpty() ? null : remainingJoinCandidates.remove(0);
    }

    /** Numbers the current attempt, so a failure of an earlier one cannot end a later one. */
    public synchronized void beginJoinAttempt() {
        joinAttemptId++;
    }

    /**
     * Claims the right to react to the current attempt's failure.
     *
     * <p>One dead backend surfaces on several paths at once — the activation, the session close and
     * the relay's disconnect can all fire for a single failure. The first caller acts; the rest are
     * told the failure is already handled so they neither kick the player nor burn a second
     * candidate on the same outage.</p>
     */
    public synchronized boolean claimJoinFailure() {
        if (lastHandledJoinAttemptId == joinAttemptId) {
            return false;
        }
        lastHandledJoinAttemptId = joinAttemptId;
        return true;
    }

    /**
     * Claims this player's proxy-command slot, refusing if they used one less than
     * {@code cooldownMillis} ago.
     *
     * <p>Every accepted {@code /server} costs a backend dial-out and, if it fails, up to a full
     * retry window of them. A client can send command packets as fast as it likes, so without a
     * gate one player holding down a macro is a connection flood against a backend that the backend
     * has no way to attribute to them.</p>
     */
    public synchronized boolean claimProxyCommandSlot(long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        // A clock that moved backwards must not lock the player out until it catches up.
        if (now >= lastProxyCommandAtMillis && now - lastProxyCommandAtMillis < cooldownMillis) {
            return false;
        }
        lastProxyCommandAtMillis = now;
        return true;
    }

    /**
     * Records that the client has been handed a StartGame and is in a world. Failover reuses the
     * backend-switch path, which assumes exactly that; before it there is nothing to switch away
     * from and an unexpected backend loss can only be a disconnect.
     *
     * <p>Deliberately not reset by {@link #setBackend}: once a client is in a world it stays in one
     * across every subsequent switch.</p>
     */
    public synchronized void markClientJoinedWorld() {
        clientJoinedWorld = true;
        // A world means the join succeeded, so the remaining candidates are no longer wanted: from
        // here on a backend loss belongs to mid-session failover.
        joinSequenceActive = false;
        remainingJoinCandidates.clear();
    }

    public synchronized boolean hasClientJoinedWorld() {
        return clientJoinedWorld;
    }

    public synchronized void rememberChunkRadius(int radius, int maxRadius) {
        if (radius > 0) {
            lastRequestedChunkRadius = radius;
        }
        if (maxRadius > 0) {
            lastRequestedMaxChunkRadius = maxRadius;
        }
    }

    public synchronized int lastRequestedChunkRadius() {
        return lastRequestedChunkRadius;
    }

    public synchronized int lastRequestedMaxChunkRadius() {
        return lastRequestedMaxChunkRadius;
    }

    public synchronized void setBackendPlayerRuntimeEntityId(long backendPlayerRuntimeEntityId) {
        this.backendPlayerRuntimeEntityId = backendPlayerRuntimeEntityId;
        if (clientPlayerRuntimeEntityId <= 0) {
            clientPlayerRuntimeEntityId = backendPlayerRuntimeEntityId;
        }
        backendToClientRuntimeIds.clear();
        clientToBackendRuntimeIds.clear();
        registerRuntimeMapping(backendPlayerRuntimeEntityId, clientPlayerRuntimeEntityId);
    }

    public synchronized long backendPlayerRuntimeEntityId() {
        return backendPlayerRuntimeEntityId;
    }

    /**
     * The client keeps the identity it was given by its first StartGame for the whole proxy session,
     * but every backend assigns the player its own unique entity id. Packets that address the local
     * player by unique id therefore have to be mapped, the same way runtime ids already are.
     *
     * <p>Only the local player's id is remapped: every other entity's unique id reaches the client
     * from the backend it is currently on, via AddEntity/AddPlayer, so it is already consistent.</p>
     *
     * <p>Unique ids are signed and routinely negative, so 0 is the "not yet known" sentinel here
     * rather than the {@code <= 0} test the runtime ids use.</p>
     *
     * <p>Clientbound only: no serverbound packet addresses the local player by unique id
     * ({@code UpdateAbilitiesPacket} is clientbound and {@code RequestAbilityPacket} carries none),
     * so there is deliberately no reverse mapping.</p>
     */
    public synchronized void setBackendPlayerUniqueEntityId(long backendPlayerUniqueEntityId) {
        this.backendPlayerUniqueEntityId = backendPlayerUniqueEntityId;
        if (clientPlayerUniqueEntityId == 0) {
            clientPlayerUniqueEntityId = backendPlayerUniqueEntityId;
        }
    }

    public synchronized long backendPlayerUniqueEntityId() {
        return backendPlayerUniqueEntityId;
    }

    public synchronized long clientPlayerUniqueEntityId() {
        return clientPlayerUniqueEntityId == 0 ? backendPlayerUniqueEntityId : clientPlayerUniqueEntityId;
    }

    public synchronized long toClientUniqueEntityId(long backendUniqueEntityId) {
        return backendPlayerUniqueEntityId != 0 && backendUniqueEntityId == backendPlayerUniqueEntityId
                ? clientPlayerUniqueEntityId
                : backendUniqueEntityId;
    }

    public synchronized long clientPlayerRuntimeEntityId() {
        return clientPlayerRuntimeEntityId <= 0 ? backendPlayerRuntimeEntityId : clientPlayerRuntimeEntityId;
    }

    public synchronized long toClientRuntimeEntityId(long backendRuntimeEntityId, boolean registerEntity) {
        if (backendRuntimeEntityId <= 0) {
            return backendRuntimeEntityId;
        }
        Long existing = backendToClientRuntimeIds.get(backendRuntimeEntityId);
        if (existing != null) {
            return existing;
        }
        if (!registerEntity) {
            return backendRuntimeEntityId;
        }
        long clientRuntimeEntityId = backendRuntimeEntityId;
        if (clientRuntimeEntityId == clientPlayerRuntimeEntityId
                || clientToBackendRuntimeIds.containsKey(clientRuntimeEntityId)) {
            do {
                clientRuntimeEntityId = nextSyntheticClientRuntimeEntityId++;
            } while (clientToBackendRuntimeIds.containsKey(clientRuntimeEntityId)
                    || clientRuntimeEntityId == clientPlayerRuntimeEntityId);
        }
        registerRuntimeMapping(backendRuntimeEntityId, clientRuntimeEntityId);
        return clientRuntimeEntityId;
    }

    public synchronized boolean hasBackendRuntimeEntityId(long backendRuntimeEntityId) {
        return backendRuntimeEntityId > 0 && backendToClientRuntimeIds.containsKey(backendRuntimeEntityId);
    }

    public synchronized long toBackendRuntimeEntityId(long clientRuntimeEntityId) {
        if (clientRuntimeEntityId <= 0) {
            return clientRuntimeEntityId;
        }
        return clientToBackendRuntimeIds.getOrDefault(clientRuntimeEntityId, clientRuntimeEntityId);
    }

    private void registerRuntimeMapping(long backendRuntimeEntityId, long clientRuntimeEntityId) {
        if (backendRuntimeEntityId <= 0 || clientRuntimeEntityId <= 0) {
            return;
        }
        backendToClientRuntimeIds.put(backendRuntimeEntityId, clientRuntimeEntityId);
        clientToBackendRuntimeIds.put(clientRuntimeEntityId, backendRuntimeEntityId);
    }

    public synchronized void setPlayerDimensionId(int playerDimensionId) {
        this.playerDimensionId = playerDimensionId;
    }

    public synchronized int playerDimensionId() {
        return playerDimensionId;
    }

    public synchronized void setBackendSwitchReset(BackendSwitchReset backendSwitchReset) {
        this.backendSwitchReset = backendSwitchReset;
    }

    public synchronized BackendSwitchReset backendSwitchReset() {
        return backendSwitchReset;
    }

    public synchronized void clearBackendSwitchReset(BackendSwitchReset backendSwitchReset) {
        if (this.backendSwitchReset == backendSwitchReset) {
            this.backendSwitchReset = null;
        }
    }

    public ClientWorldState clientWorldState() {
        return clientWorldState;
    }

    /**
     * Records a client-ready (already translated) packet that carries local-player state which the
     * backend only emits once during its join burst. During a backend switch that burst arrives while
     * the {@link BackendSwitchReset} is suppressing world-state packets, so these would otherwise be
     * dropped and never reach the client, leaving the player with stale attributes/metadata after the
     * switch. They are replayed once the switch reset completes.
     */
    public synchronized void addDeferredSwitchPlayerState(BedrockPacket packet) {
        if (packet != null) {
            deferredSwitchPlayerState.add(packet);
        }
    }

    public synchronized List<BedrockPacket> drainDeferredSwitchPlayerState() {
        if (deferredSwitchPlayerState.isEmpty()) {
            return List.of();
        }
        List<BedrockPacket> drained = new ArrayList<>(deferredSwitchPlayerState);
        deferredSwitchPlayerState.clear();
        return drained;
    }

    /**
     * Records a client-ready (already translated) packet that carries persistent world state —
     * chunks, entity spawns/removals, block updates and the publisher updates that scope them.
     *
     * <p>The backend streams a chunk exactly once per player: BDS marks it sent in that player's chunk
     * view and only ever re-sends it if the chunk leaves and re-enters the view radius. During a
     * backend switch the {@link BackendSwitchReset} suppresses world state so it cannot land in the
     * fake dimension the client is bounced through — but a backend whose spawn chunks are already
     * resident and cheap to serialize (a skyblock/void world, say) can stream the player's entire
     * surroundings inside that window. Entity visibility has the same edge-triggered behavior: an
     * AddEntity/AddPlayer packet suppressed while the entity is already in view is not sent again until
     * the player leaves and re-enters that view. Dropping either kind is therefore permanent in place,
     * so buffer instead and replay once the client is back in the target dimension.</p>
     *
     * <p>Bounded by {@link #MAX_DEFERRED_SWITCH_WORLD_STATE} so a backend that streams for the whole
     * ack-fallback window cannot grow this without limit; past the cap we fall back to dropping.</p>
     *
     * @return whether the packet was buffered (a false return leaves ownership with the caller)
     */
    public synchronized boolean addDeferredSwitchWorldState(BedrockPacket packet) {
        if (packet == null) {
            return false;
        }
        if (deferredSwitchWorldState.size() >= MAX_DEFERRED_SWITCH_WORLD_STATE) {
            if (!deferredSwitchWorldStateOverflowed) {
                deferredSwitchWorldStateOverflowed = true;
                System.out.printf(
                        "Deferred switch world-state buffer full at %d packets; dropping further world state "
                                + "until the switch reset completes.%n",
                        MAX_DEFERRED_SWITCH_WORLD_STATE
                );
            }
            return false;
        }
        deferredSwitchWorldState.add(packet);
        return true;
    }

    public synchronized List<BedrockPacket> drainDeferredSwitchWorldState() {
        deferredSwitchWorldStateOverflowed = false;
        if (deferredSwitchWorldState.isEmpty()) {
            return List.of();
        }
        List<BedrockPacket> drained = new ArrayList<>(deferredSwitchWorldState);
        deferredSwitchWorldState.clear();
        return drained;
    }

    /**
     * Drops the buffered world state without sending it, releasing the chunk buffers it holds. Used
     * when the switch it belonged to is abandoned or superseded, where replaying would push chunks at
     * a client that is no longer in that world.
     */
    public synchronized void releaseDeferredSwitchWorldState() {
        deferredSwitchWorldStateOverflowed = false;
        if (deferredSwitchWorldState.isEmpty()) {
            return;
        }
        for (BedrockPacket packet : deferredSwitchWorldState) {
            ReferenceCountUtil.release(packet);
        }
        deferredSwitchWorldState.clear();
    }

    /**
     * Pairs the death respawn loading-screen handshake. The 1.26.20 client clicking "Respawn" sends a
     * START_LOADING_SCREEN (translated to a backend CLIENT_READY) followed by an END_LOADING_SCREEN
     * (translated to a backend PlayerAction RESPAWN). A backend switch's dimension-change loading
     * screen, however, can also leave a trailing END_LOADING_SCREEN that reaches the same code; without
     * pairing it would be mis-sent to the backend as a spurious RESPAWN. Only translate the END when we
     * actually started a death respawn.
     */
    public synchronized void markDeathRespawnLoadingScreenStarted() {
        awaitingDeathRespawnLoadingScreenEnd = true;
    }

    public synchronized boolean consumeDeathRespawnLoadingScreenEnd() {
        boolean wasAwaiting = awaitingDeathRespawnLoadingScreenEnd;
        awaitingDeathRespawnLoadingScreenEnd = false;
        return wasAwaiting;
    }

    /**
     * After a backend switch the client never re-sends SetLocalPlayerAsInitialized (it was already
     * initialized on the original join, and a dimension change does not reset that). The proxy must
     * therefore synthesize it for the new backend — but only once the backend has finished its respawn
     * handshake (SERVER_READY). Sending it earlier, while the backend is still SERVER_SEARCHING, leaves
     * the player respawned-but-not-initialized: it can move (client-predicted) yet the server rejects
     * block interactions and never ticks the air supply (frozen "underwater" bubbles). This records the
     * pending initialization so the SERVER_READY handler can flush it at the right time.
     */
    public synchronized void setPendingPostSwitchInit(BackendSession backend, long backendRuntimeEntityId) {
        pendingPostSwitchInitBackend = backend;
        pendingPostSwitchInitBackendRuntimeEntityId = backendRuntimeEntityId;
    }

    /**
     * Hands the pending initialization to {@code backend} only if it is the session the switch was
     * completed for. Each switch arms a timed fallback bound to its own backend, so a second switch
     * started while an earlier fallback is still pending would otherwise let the stale timer consume
     * the newer switch's token and send the initialization to a backend the player already left,
     * leaving the current backend waiting forever.
     */
    public synchronized long consumePendingPostSwitchInit(BackendSession backend) {
        if (pendingPostSwitchInitBackend != backend) {
            return 0;
        }
        long rid = pendingPostSwitchInitBackendRuntimeEntityId;
        pendingPostSwitchInitBackend = null;
        pendingPostSwitchInitBackendRuntimeEntityId = 0;
        return rid;
    }

    /**
     * Records a client-ready (already translated and runtime-id-registered) entity spawn that was
     * suppressed during the initial cross-protocol join. The backend only sends AddEntity/AddPlayer
     * once, so suppressing them outright leaves those entities permanently unregistered — their later
     * movement/metadata is dropped as "unknown" and they appear frozen/invisible. These are replayed to
     * the client once the local player is initialized.
     */
    public synchronized void addDeferredInitialEntitySpawn(BedrockPacket packet) {
        if (packet != null) {
            deferredInitialEntitySpawns.add(packet);
        }
    }

    public synchronized List<BedrockPacket> drainDeferredInitialEntitySpawns() {
        if (deferredInitialEntitySpawns.isEmpty()) {
            return List.of();
        }
        List<BedrockPacket> drained = new ArrayList<>(deferredInitialEntitySpawns);
        deferredInitialEntitySpawns.clear();
        return drained;
    }

    public synchronized boolean hasForwardedLevelChunk() {
        return firstLevelChunkForwarded;
    }

    public synchronized int markLevelChunkForwarded() {
        firstLevelChunkForwarded = true;
        forwardedLevelChunks++;
        return forwardedLevelChunks;
    }

    public synchronized int forwardedLevelChunks() {
        return forwardedLevelChunks;
    }

    public synchronized void rememberSyntheticClientChunkBlob(long blobId, byte[] blob) {
        syntheticClientChunkBlobs.put(blobId, blob.clone());
    }

    public synchronized byte[] syntheticClientChunkBlob(long blobId) {
        byte[] blob = syntheticClientChunkBlobs.get(blobId);
        return blob == null ? null : blob.clone();
    }

    public synchronized void markInitialClientChunkCacheStatusSeen() {
        initialClientChunkCacheStatusSeen = true;
    }

    public synchronized boolean hasInitialClientChunkCacheStatusSeen() {
        return initialClientChunkCacheStatusSeen;
    }

    public synchronized void setSaneJoinPosition(Vector3f saneJoinPosition) {
        this.saneJoinPosition = saneJoinPosition;
        backendJoinPositionSynced = false;
    }

    public synchronized Vector3f saneJoinPosition() {
        return saneJoinPosition;
    }

    public synchronized boolean markBackendJoinPositionSynced() {
        if (backendJoinPositionSynced) {
            return false;
        }
        backendJoinPositionSynced = true;
        return true;
    }

    public synchronized void markInitialServerSearchingSeen(Vector3f position) {
        initialServerSearchingSeen = true;
        if (position != null) {
            initialServerSearchingPosition = position;
        }
    }

    public synchronized boolean hasInitialServerSearchingSeen() {
        return initialServerSearchingSeen;
    }

    public synchronized Vector3f initialServerSearchingPosition() {
        return initialServerSearchingPosition;
    }

    public synchronized void markInitialLoadingScreenStarted() {
        initialLoadingScreenStarted = true;
    }

    public synchronized boolean hasInitialLoadingScreenStarted() {
        return initialLoadingScreenStarted;
    }

    public synchronized boolean markInitialBackendLoadingScreenStarted() {
        if (initialBackendLoadingScreenStarted) {
            return false;
        }
        initialBackendLoadingScreenStarted = true;
        return true;
    }

    public synchronized boolean markInitialBackendRespawnReadySent() {
        if (initialBackendRespawnReadySent) {
            return false;
        }
        initialBackendRespawnReadySent = true;
        return true;
    }

    public synchronized boolean markInitialSyntheticServerReadySent() {
        if (initialSyntheticServerReadySent) {
            return false;
        }
        initialSyntheticServerReadySent = true;
        return true;
    }

    public synchronized boolean hasSentInitialSyntheticServerReady() {
        return initialSyntheticServerReadySent;
    }

    public synchronized boolean markInitialSyntheticPlayerSpawnSent() {
        if (initialSyntheticPlayerSpawnSent) {
            return false;
        }
        initialSyntheticPlayerSpawnSent = true;
        return true;
    }

    public synchronized boolean hasSentInitialSyntheticPlayerSpawn() {
        return initialSyntheticPlayerSpawnSent;
    }

    public synchronized void markInitialLocalPlayerInitialized() {
        initialLocalPlayerInitialized = true;
    }

    public synchronized boolean hasInitialLocalPlayerInitialized() {
        return initialLocalPlayerInitialized;
    }

    public void closeBackend(CharSequence reason) {
        BackendSession backend;
        BackendSession pendingBackend;
        synchronized (this) {
            backend = this.backend;
            pendingBackend = this.pendingBackend;
            this.pendingBackend = null;
        }
        if (backend != null && backend.isConnected()) {
            backend.disconnect(reason);
        }
        if (pendingBackend != null && pendingBackend != backend && pendingBackend.isConnected()) {
            pendingBackend.setDisconnectClientOnClose(false);
            pendingBackend.discardInboundPackets();
            pendingBackend.disconnect(reason);
        }
        synchronized (this) {
            backendSwitchReset = null;
        }
        // A reset that never completed still owns retained chunk buffers; nobody will replay them now.
        releaseDeferredSwitchWorldState();
    }

    public void tracePacketsForMillis(long millis) {
        if (LOG_PACKETS || CONFIGURED_PACKET_TRACE_MILLIS <= 0 || millis <= 0) {
            return;
        }
        packetTraceUntilNanos = System.nanoTime() + (millis * 1_000_000L);
    }

    public boolean isPacketTraceActive() {
        return LOG_PACKETS
                || (CONFIGURED_PACKET_TRACE_MILLIS > 0 && System.nanoTime() <= packetTraceUntilNanos);
    }

    public static int configuredPacketTraceMillis() {
        return CONFIGURED_PACKET_TRACE_MILLIS;
    }

    public static boolean isPacketTracingConfigured() {
        return LOG_PACKETS || CONFIGURED_PACKET_TRACE_MILLIS > 0;
    }

    public static boolean isContinuousPacketTracingConfigured() {
        return LOG_PACKETS;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - createdAtNanos) / 1_000_000L;
    }

    public synchronized long nextClientboundTraceSequence() {
        return ++clientboundTraceSequence;
    }

    public synchronized long nextServerboundTraceSequence() {
        return ++serverboundTraceSequence;
    }

    public synchronized long clientboundTraceSequence() {
        return clientboundTraceSequence;
    }

    public synchronized long serverboundTraceSequence() {
        return serverboundTraceSequence;
    }

}
