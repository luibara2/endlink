package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector2i;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ServerConfigurationJoinInfo;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddHangingEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheMissResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;
import org.cloudburstmc.protocol.bedrock.packet.DeathInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.CompressedBiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityFallPacket;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.AvailableEntityIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncEntityPropertyPacket;
import org.endstone.proxy.palette.CrossBackendPalette;
import org.endstone.proxy.palette.ItemPaletteMapping;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetCommandsEnabledPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHealthPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerInventoryOptionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.TakeItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockSyncedPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientInputLocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.endstone.proxy.command.AvailableCommandsInjector;
import org.endstone.proxy.codec.CodecDefinitionState;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.diagnostics.PacketViolation;
import org.endstone.proxy.diagnostics.ProtocolFault;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.resource.BackendPackCache;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.endstone.proxy.verification.PendingJoin;
import org.endstone.proxy.verification.PendingJoinRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BackendRelayPacketHandler implements BedrockPacketHandler {
    private static final int COMMAND_OUTPUT_PACKET_ID = CanonicalProtocol.V1_21_130.codec()
            .getPacketDefinition(CommandOutputPacket.class)
            .getId();
    /**
     * Needed as a raw id because a backend's disconnect does not always decode: servers add
     * {@code DisconnectFailReason} values faster than the vendored enum tracks them, and an
     * unrecognised reason used to make the whole packet undecodable. That is fixed in
     * {@code DisconnectPacket.setReasonOrdinal}, but a disconnect must still be recognised when it
     * arrives as an {@link UnknownPacket} for any other reason — relaying one to the client is an
     * instant, unrecoverable kick.
     */
    private static final int DISCONNECT_PACKET_ID = CanonicalProtocol.V1_21_130.codec()
            .getPacketDefinition(DisconnectPacket.class)
            .getId();
    private static final boolean MATERIALIZE_CACHED_CHUNKS_FOR_CROSS_PROTOCOL = false;

    /**
     * Bisect switch: forward the backend's command tree untouched, without the proxy's /server and
     * /hub entries. Pairs with {@code -Dproxy.noStartGameFixups} — between them they remove
     * everything the proxy still changes relative to a direct connection, which is the only
     * remaining difference now that the death path is verified to relay byte-for-byte.
     *
     * <p>Note that {@code AvailableCommandsSerializer_v898} is separately known to lose roughly 16KB
     * of command data on re-encode, so this switch also takes that corruption out of the picture.
     * Enable with {@code -Dproxy.noCommandInjection=true}; the proxy commands stop working while it
     * is on, so it is a diagnostic, not a supported mode.
     */
    private static final boolean NO_COMMAND_INJECTION = Boolean.getBoolean("proxy.noCommandInjection");
    private static final boolean SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL = false;
    private static final int INITIAL_CROSS_PROTOCOL_PUBLISHER_RADIUS_BLOCKS = 64;
    private static final int INITIAL_CROSS_PROTOCOL_CHUNK_BATCH_MIN_CHUNKS = 13;
    private static final int INITIAL_CROSS_PROTOCOL_CHUNK_BATCH_INCREMENT_CHUNKS = 6;
    private static final int INITIAL_CROSS_PROTOCOL_KEEPALIVE_PLAYER_SPAWN_MIN_CHUNKS = 12;
    private static final int INITIAL_CROSS_PROTOCOL_MIN_SUBCHUNK_LIMIT = 8;
    private static final int MIN_SYNTHETIC_CLIENT_CACHE_BLOB_BYTES = 0;
    private static final long VANILLA_1_26_20_BLOCK_REGISTRY_CHECKSUM = -6083918988771959701L;

    private final ProxyConnection connection;
    private final BackendSession backend;
    private final String backendName;
    private final BackendActivation activation;
    private final PendingJoinRegistry pendingJoinRegistry;
    private final PendingJoin pendingJoin;
    private final AvailableCommandsInjector commandsInjector;
    private final Function<String, String> verifiedXuidLookup;
    private final BackendFailover failover;
    private final JoinFailover joinFailover;
    private final BackendDirectory backendDirectory;
    private final BackendSwitcher backendSwitcher;
    private final Set<String> requestedSkeletonChunks = new java.util.HashSet<>();
    private final Set<Long> requestedMissingChunkBlobs = new java.util.HashSet<>();
    private final Set<Long> acknowledgedChunkBlobs = new java.util.HashSet<>();
    private final Set<Long> advertisedSyntheticClientChunkBlobs = new java.util.HashSet<>();
    private final Map<Long, byte[]> cachedChunkBlobs = new HashMap<>();
    private final Map<Long, List<PendingCachedChunk>> pendingCachedChunks = new HashMap<>();
    private final Deque<PendingInitialClientbound> pendingInitialClientbound = new ArrayDeque<>();
    private final Set<Vector2i> initialForwardedLevelChunks = new java.util.LinkedHashSet<>();
    private final Set<Vector2i> initialSyntheticCachedLevelChunks = new java.util.LinkedHashSet<>();
    private Vector3f saneJoinPosition;
    private Vector3f initialSyntheticServerReadyPosition;
    private boolean initialBiomeDefinitionForwarded;
    private boolean initialBackendPlayerSpawnPrepared;
    // Set when we drive a cross-protocol death respawn handshake (CLIENT_READY) so we only do it
    // once per death; cleared when the backend confirms the respawn with SERVER_READY.
    private boolean deathRespawnHandshakeDriven;
    // Set once this backend's kick has been claimed by failover. Its socket stays open for a short
    // while afterwards, and anything else it sends in that window belongs to a world the player is
    // already leaving.
    private boolean kickIntercepted;
    private int initialChunkBatchPublisherSavedChunkCount;
    private ChunkRadiusUpdatedPacket deferredInitialChunkRadiusUpdated;
    private long deferredInitialChunkRadiusTraceSequence;
    private int backendInputLockData;
    /** Packs being assembled from bytes on their way to the client; see {@link #captureBackendPackBytes}. */
    private final Map<UUID, ObservedPack> observedPacks = new HashMap<>();
    /** Non-null only while this backend's packs are being downloaded during a switch. */
    private BackendPackFetch packFetch;

    public BackendRelayPacketHandler(
            ProxyConnection connection,
            BackendSession backend,
            String backendName,
            BackendActivation activation,
            PendingJoinRegistry pendingJoinRegistry,
            PendingJoin pendingJoin,
            AvailableCommandsInjector commandsInjector,
            Function<String, String> verifiedXuidLookup,
            BackendFailover failover,
            JoinFailover joinFailover,
            BackendDirectory backendDirectory,
            BackendSwitcher backendSwitcher
    ) {
        this.connection = connection;
        this.backend = backend;
        this.backendName = backendName;
        this.activation = activation;
        this.pendingJoinRegistry = pendingJoinRegistry;
        this.pendingJoin = pendingJoin;
        this.commandsInjector = commandsInjector;
        this.verifiedXuidLookup = verifiedXuidLookup != null ? verifiedXuidLookup : name -> "";
        this.failover = failover;
        this.joinFailover = joinFailover;
        this.backendDirectory = backendDirectory;
        this.backendSwitcher = backendSwitcher;
    }

    /**
     * Clientbound packet types to drop instead of relaying, from
     * {@code -Dproxy.dropClientbound=SetEntityData,AddEntity}. Names match with or without the
     * {@code Packet} suffix and ignore case.
     *
     * <p>A bisection tool, for when static analysis has run out. Every codec-level check available
     * has come back clean on the 1.26.40&rarr;1.26.30 disconnect — no failed encodes, no failed
     * decodes, and a clean outbound read-back — while the client still closes the connection
     * abruptly, mid-stream, with no message. At that point the question is no longer "which field is
     * wrong" but "which packet family is involved at all", and the fastest way to answer it is to
     * stop sending one and see whether the session survives.
     *
     * <p>The same approach settled the death-disconnect investigation, where
     * {@code -Dproxy.noStartGameFixups} and {@code -Dproxy.noCommandInjection} eliminated the proxy's
     * two behavioural differences in a single run each.
     *
     * <p>Diagnostic only, and deliberately blunt: suppressing a packet family breaks whatever it
     * drives. Dropping the entity families leaves mobs invisible or frozen, which is fine — the
     * question being asked is whether the player is still connected five minutes later, not whether
     * the world looks right.
     */
    private static final Set<String> DIAGNOSTIC_DROPPED_CLIENTBOUND = parseDroppedClientbound();

    /**
     * How long event-triggered packet tracing runs, from {@code -Dproxy.traceMillis=120000}.
     * The production default is zero; {@code -Dproxy.logPackets=true} enables an unbounded trace.
     */
    private static final int PACKET_TRACE_MILLIS = ProxyConnection.configuredPacketTraceMillis();
    /**
     * How long a switching player may be held while their new backend's packs are downloaded. Long
     * enough for a large pack on a local link, short enough that a silent backend is not mistaken for
     * a slow one.
     */
    private static final long PACK_FETCH_TIMEOUT_MILLIS = 20_000;

    private static Set<String> parseDroppedClientbound() {
        String value = System.getProperty("proxy.dropClientbound", "");
        if (value.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            names.add(normalized.endsWith("packet") ? normalized : normalized + "packet");
        }
        System.out.printf("Diagnostics: dropping clientbound packets %s before relay.%n", names);
        return Set.copyOf(names);
    }

    /**
     * How much of a neutered packet's real content to keep. See
     * {@link #DIAGNOSTIC_NEUTERED_CLIENTBOUND}.
     */
    public enum NeuterMode {
        /**
         * Smallest valid body: for {@code MoveEntityDelta}, the runtime id and every optional absent —
         * roughly 11 bytes against ~26 for a real one, because the presence booleans are still
         * written. Changes content <em>and</em> byte volume, so a survival under this mode does not by
         * itself distinguish the two.
         */
        MINIMAL,
        /**
         * Byte-for-byte the same length as the real packet: every optional is kept, with its real
         * value, and only the trailing booleans are normalised. Entities still move, the client still
         * interpolates, and the encoded size is unchanged — so this isolates <em>only</em> the flag
         * semantics, which is the one family that has already produced three bugs on this hop.
         */
        SAME_SIZE
    }

    /**
     * Clientbound packet types to relay with neutered content, from
     * {@code -Dproxy.neuterClientbound=MoveEntityDelta} or
     * {@code -Dproxy.neuterClientbound=MoveEntityDelta:samesize,SetEntityMotion}. Names match with or
     * without the {@code Packet} suffix and ignore case; the mode defaults to {@code minimal}.
     *
     * <p><b>Why this exists.</b> {@code -Dproxy.dropClientbound} established that survival on the
     * 1.26.40&rarr;1.26.30 hop goes from ~5s to 17-54s when {@code MoveEntityDelta} and
     * {@code SetEntityMotion} are suppressed, and that suppressing a 12% slice does nothing. But those
     * two packets are also ~60% of all clientbound traffic, so for them "the suspect" and "the volume"
     * are the same variable and <b>no drop experiment can separate content from rate</b>.
     *
     * <p>Neutering breaks that tie by holding the packet count fixed and changing only what the
     * packets say. Read the result as:
     *
     * <ul>
     *   <li>{@code samesize} survives &rarr; the four trailing booleans are the bug. Same count, same
     *       bytes, same positions; only the flags differ.</li>
     *   <li>{@code samesize} still dies at ~6s but {@code minimal} survives &rarr; not the flags. It is
     *       the positional payload's content, or the byte volume the optionals carry.</li>
     *   <li>both still die at ~6s &rarr; content is exonerated at the packet layer and the cause is
     *       packet <i>count</i>. The search moves below the packet layer, to compression and RakNet
     *       fragmentation.</li>
     * </ul>
     *
     * <p>Both modes report every entity as grounded and force nothing, and {@code minimal} freezes
     * entities where they stand. Diagnostic only: the question a neutered run answers is whether the
     * player is still connected, not whether the world looks right.
     */
    private static final Map<String, NeuterMode> DIAGNOSTIC_NEUTERED_CLIENTBOUND = parseNeuteredClientbound();

    /**
     * The packet types {@link #neuterForDiagnostics} actually knows how to neuter. A name outside this
     * set is rejected at startup rather than ignored: a run configured with a typo would otherwise
     * relay everything untouched and its ~6s disconnect would look like a result, which is the same
     * "no way to tell the flag did not take" trap that cost the 15:50Z capture.
     *
     * <p>A method rather than a {@code static final} field on purpose: the field it is consulted from
     * is initialised earlier in this class, so as a field it read back as {@code null} and every
     * configured run died in {@code <clinit>} with an NPE. A method has no declaration-order hazard.
     */
    private static Set<String> neuterableClientbound() {
        return Set.of("moveentitydeltapacket", "setentitymotionpacket", "subchunkpacket");
    }

    private static Map<String, NeuterMode> parseNeuteredClientbound() {
        Map<String, NeuterMode> modes = parseNeuterSpec(System.getProperty("proxy.neuterClientbound", ""));
        if (!modes.isEmpty()) {
            System.out.printf("Diagnostics: neutering clientbound packets %s before relay.%n", modes);
        }
        return modes;
    }

    /**
     * Package-private and taking the raw string so the syntax can be pinned by a test. Rejects an
     * unrecognised name or mode by throwing, which surfaces at class-init and therefore at startup.
     */
    static Map<String, NeuterMode> parseNeuterSpec(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, NeuterMode> modes = new HashMap<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String name = trimmed;
            NeuterMode mode = NeuterMode.MINIMAL;
            int separator = trimmed.indexOf(':');
            if (separator >= 0) {
                name = trimmed.substring(0, separator).trim();
                String requested = trimmed.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
                mode = switch (requested) {
                    case "minimal", "" -> NeuterMode.MINIMAL;
                    case "samesize", "same-size", "keepsize" -> NeuterMode.SAME_SIZE;
                    default -> throw new IllegalArgumentException(
                            "Unknown -Dproxy.neuterClientbound mode '" + requested
                                    + "' for " + name + "; expected minimal or samesize.");
                };
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!normalized.endsWith("packet")) {
                normalized = normalized + "packet";
            }
            if (!neuterableClientbound().contains(normalized)) {
                throw new IllegalArgumentException(
                        "-Dproxy.neuterClientbound cannot neuter '" + name + "'. Supported: "
                                + neuterableClientbound()
                                + ". Add a case to BackendRelayPacketHandler.neuterForDiagnostics first — "
                                + "silently ignoring it would make the run answer nothing.");
            }
            modes.put(normalized, mode);
        }
        return Map.copyOf(modes);
    }

    /** Rendered into the startup {@code Diagnostics:} line so a run's posture is always visible. */
    public static String diagnosticSuppressionSummary() {
        return String.format(
                "dropClientbound=%s neuterClientbound=%s",
                DIAGNOSTIC_DROPPED_CLIENTBOUND.isEmpty() ? "none" : DIAGNOSTIC_DROPPED_CLIENTBOUND,
                DIAGNOSTIC_NEUTERED_CLIENTBOUND.isEmpty() ? "none" : DIAGNOSTIC_NEUTERED_CLIENTBOUND
        );
    }

    private final Set<String> reportedDiagnosticDrops = new HashSet<>();
    private final Set<String> reportedDiagnosticNeuters = new HashSet<>();

    private boolean isSuppressedForDiagnostics(BedrockPacket packet) {
        if (DIAGNOSTIC_DROPPED_CLIENTBOUND.isEmpty()) {
            return false;
        }
        String name = packet.getClass().getSimpleName();
        if (!DIAGNOSTIC_DROPPED_CLIENTBOUND.contains(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // Once per type per session: these are the highest-volume packets there are, and a line each
        // would bury the rest of the log in exactly the run where the log matters.
        if (reportedDiagnosticDrops.add(name)) {
            System.out.printf("Diagnostics: suppressing clientbound %s from backend %s.%n", name, backendName);
        }
        return true;
    }

    /**
     * Strip the content out of a packet in place, keeping its identity and its place in the stream.
     *
     * <p>Applied to the freshly decoded backend packet, before runtime-id rewriting and before
     * translation, so the neutered content travels the exact path real content does.
     */
    private void neuterForDiagnostics(BedrockPacket packet) {
        if (DIAGNOSTIC_NEUTERED_CLIENTBOUND.isEmpty()) {
            return;
        }
        String name = packet.getClass().getSimpleName();
        NeuterMode mode = DIAGNOSTIC_NEUTERED_CLIENTBOUND.get(name.toLowerCase(Locale.ROOT));
        if (mode == null) {
            return;
        }
        neuter(packet, mode);
        if (reportedDiagnosticNeuters.add(name)) {
            System.out.printf(
                    "Diagnostics: neutering clientbound %s (%s) from backend %s.%n",
                    name,
                    mode,
                    backendName
            );
        }
    }

    /**
     * A complete, valid sub-chunk block payload containing nothing: format version 8, zero block
     * storages, which every version from v471 onwards reads as "entirely air".
     *
     * <p>Deliberately storage-free rather than a one-entry air palette, because a palette entry would
     * have to carry a block network id and those are <em>hashes</em> of block state NBT on this hop
     * ({@code blockNetworkIdsHashed=true}) — the proxy does not have the client's block registry and
     * could not compute one. Zero storages needs no registry at all, which is what makes this usable
     * as a neuter.
     *
     * <p>The reader stops after the declared storage count, so appending arbitrary bytes after these
     * two keeps the payload valid at <em>any</em> length &ge; 2. That is what lets
     * {@link NeuterMode#SAME_SIZE} hold the byte count fixed while removing all real block content.
     */
    private static final byte[] EMPTY_SUB_CHUNK_PAYLOAD = {8, 0};

    /**
     * The transform itself, in place. Static and package-private so a test can assert the property the
     * whole experiment rests on — that {@link NeuterMode#SAME_SIZE} really does encode to the same
     * number of bytes — without having to set a system property before this class loads. A
     * {@code SAME_SIZE} that quietly changed the packet's length would silently reintroduce the volume
     * confound it exists to remove, and the run would look like a clean answer.
     *
     * <p><b>The {@code SubChunk} neuter is the one this hop still needs, and it is why the two modes
     * matter.</b> {@code -Dproxy.dropClientbound=SubChunk} makes the session immortal, which reads as a
     * clean isolation — but it also removes about 90% of all clientbound <em>bytes</em>, so it is the
     * same volume confound that has defeated every earlier bisect here, one packet further along. The
     * envelope itself has since been verified byte-for-byte against gophertunnel PR #481 and the
     * {@code r26_u4} dump and is correct, so what a drop cannot tell apart is the payload's content
     * from the payload's size. These two modes do:
     *
     * <ul>
     *   <li>{@code samesize} keeps every entry, every heightmap, every result and the exact encoded
     *       length, replacing only the opaque block payload with an equally long empty one. Survives
     *       &rarr; the <b>content</b> of the terrain payload is the cause, i.e. 1.26.30 block data a
     *       1.26.40 client will not accept. Still dies &rarr; content is exonerated and the cause is
     *       byte volume or packet count.</li>
     *   <li>{@code minimal} cuts each payload to two bytes, so it cuts content and volume together. It
     *       is the control: if {@code samesize} dies and {@code minimal} survives, the variable is
     *       size alone and no amount of payload translation will help.</li>
     * </ul>
     *
     * <p>Expect no terrain to render under either mode. As always, the question is whether the player
     * is still connected, not whether the world looks right — but note this neuter leaves the client
     * free to move, because the chunk still completes.
     */
    static void neuter(BedrockPacket packet, NeuterMode mode) {
        if (packet instanceof MoveEntityDeltaPacket move) {
            if (mode == NeuterMode.MINIMAL) {
                // Every optional absent. The entity stops where it is; the packet keeps arriving.
                move.getFlags().clear();
                move.setX(0f);
                move.setY(0f);
                move.setZ(0f);
                move.setPitch(0f);
                move.setYaw(0f);
                move.setHeadYaw(0f);
            } else {
                // Keep the HAS_* flags and their values untouched so the encoded length does not
                // move, and clear only the four that carry meaning rather than presence.
                move.getFlags().removeAll(EnumSet.of(
                        MoveEntityDeltaPacket.Flag.TELEPORTING,
                        MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY,
                        MoveEntityDeltaPacket.Flag.FORCE_COMPLETION
                ));
            }
            // On-ground true in both modes, and deliberately not false — the HANDOFF's sketch said all
            // four booleans false, but a client told an entity is unsupported runs its own physics for
            // it, so all-false would swap one suspect content for another known-bad one (see the note
            // on MoveEntityDeltaSerializer_v2168). The v2168 writer ORs the boolean with the flag, so
            // the flag has to be cleared for the boolean to be the only thing deciding.
            move.setOnGround(true);
            move.getFlags().remove(MoveEntityDeltaPacket.Flag.ON_GROUND);
            move.setForceMove(false);
            move.setForceMoveLocalEntity(false);
            move.setForceCompletion(false);
        } else if (packet instanceof SetEntityMotionPacket motion) {
            // Fixed shape, so both modes are the same neuter and neither changes the encoded length.
            motion.setMotion(Vector3f.ZERO);
        } else if (packet instanceof SubChunkPacket subChunkPacket) {
            for (SubChunkData subChunk : subChunkPacket.getSubChunks()) {
                ByteBuf original = subChunk.getData();
                if (original == null) {
                    // SUCCESS_ALL_AIR with the blob cache on carries no payload at all.
                    continue;
                }
                int length = mode == NeuterMode.MINIMAL
                        ? EMPTY_SUB_CHUNK_PAYLOAD.length
                        : Math.max(original.readableBytes(), EMPTY_SUB_CHUNK_PAYLOAD.length);
                ByteBuf replacement = Unpooled.buffer(length, length);
                replacement.writeBytes(EMPTY_SUB_CHUNK_PAYLOAD);
                replacement.writeZero(length - EMPTY_SUB_CHUNK_PAYLOAD.length);
                subChunk.setData(replacement);
                original.release();
            }
        } else {
            // Unreachable: parseNeuteredClientbound rejects anything not handled above.
            throw new IllegalStateException("No neuter implemented for " + packet.getClass().getSimpleName());
        }
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        boolean pendingStartGame = packet instanceof StartGamePacket && backend == connection.pendingBackend();
        if (connection.pendingBackend() != null && backend == connection.backend()) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Dropping old-backend packet from %s during pending switch: %s.%n",
                        backendName,
                        packet.getClass().getSimpleName()
                );
            }
            return PacketSignal.HANDLED;
        }
        if (!isCurrentBackend()) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Dropping stale packet from backend %s after handoff: %s.%n",
                        backendName,
                        packet.getClass().getSimpleName()
                );
            }
            return PacketSignal.HANDLED;
        }
        if (backend == connection.pendingBackend() && acknowledgePendingSwitchLoginPacket(packet)) {
            return PacketSignal.HANDLED;
        }
        if (packet instanceof UpdateClientInputLocksPacket inputLocks
                && captureSwitchInputLocks(inputLocks)) {
            return PacketSignal.HANDLED;
        }
        if (packet instanceof TransferPacket transfer && interceptInternalTransfer(transfer)) {
            return PacketSignal.HANDLED;
        }
        if (isSuppressedForDiagnostics(packet)) {
            return PacketSignal.HANDLED;
        }
        neuterForDiagnostics(packet);
        // Must come before anything that could forward the packet: a backend's disconnect reaching
        // the client is an immediate kick with no way back, so failover has to claim it first.
        if (backend == connection.backend() && interceptBackendKick(packet)) {
            return PacketSignal.HANDLED;
        }
        // A pack the client is downloading passes through here in full, so learning it costs nothing
        // beyond the copy — no extra request, no extra traffic.
        captureBackendPackBytes(packet);
        // Proxy resource pack injection. Intercept the backend's resource pack packets so that
        // proxy packs are merged into the single info+stack the client sees.
        if (!connection.proxyResourcePackRegistry().isEmpty()) {
            if (packet instanceof ResourcePacksInfoPacket backendInfo) {
                handleMergedResourcePacksInfo(backendInfo);
                return PacketSignal.HANDLED;
            }
            if (packet instanceof ResourcePackStackPacket backendStack) {
                handleMergedResourcePackStack(backendStack);
                return PacketSignal.HANDLED;
            }
        }
        long traceSequence = -1;
        if (connection.isPacketTraceActive()) {
            traceSequence = connection.nextClientboundTraceSequence();
            System.out.printf(
                    "Trace clientbound #%d +%dms from backend %s: %s current=%s pending=%s switchReset=%s.%n",
                traceSequence,
                connection.elapsedMillis(),
                    backendName,
                    packet.getClass().getSimpleName(),
                    backend == connection.backend(),
                    backend == connection.pendingBackend(),
                    connection.backendSwitchReset() != null
            );
            logClientboundDetails(packet);
        }
        flushPendingInitialClientboundIfReady();
        int sourceDimension = connection.playerDimensionId();
        if (pendingStartGame) {
            clearPreviousClientWorldState();
        }
        if (connection.crossBackendPalette().isEnabled() && handleCrossBackendPalette(packet, traceSequence)) {
            return PacketSignal.HANDLED;
        }
        syncDefinitionState(packet);
        if (isCrossProtocol() && shouldDropCrossProtocolClientbound(packet)) {
            System.out.printf(
                    "Dropping clientbound cross-protocol packet from backend %s for client protocol %d: %s.%n",
                    backendName,
                    connection.sessionProfile().clientCodec().getProtocolVersion(),
                    packet.getClass().getSimpleName()
            );
            return PacketSignal.HANDLED;
        }
        if (packet instanceof DeathInfoPacket) {
            connection.tracePacketsForMillis(PACKET_TRACE_MILLIS);
            if (PACKET_TRACE_MILLIS > 0) {
                System.out.printf(
                        "Enabled detailed packet trace for %s for %dms after DeathInfoPacket at +%dms.%n",
                        connection.client().getSocketAddress(),
                        PACKET_TRACE_MILLIS,
                        connection.elapsedMillis()
                );
            }
        }
        if (packet instanceof AvailableCommandsPacket availableCommands) {
            int before = availableCommands.getCommands().size();
            if (!NO_COMMAND_INJECTION) {
                commandsInjector.inject(availableCommands);
            }
            int after = availableCommands.getCommands().size();
            Set<String> commandNames = availableCommands.getCommands().stream()
                    .map(command -> command.getName().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding command tree from backend %s: %d native commands, %d total after proxy injection. proxyCommandsPresent=%s/%s/%s.%n",
                        backendName,
                        before,
                        after,
                        commandNames.contains("hub"),
                        commandNames.contains("lobby"),
                        commandNames.contains("server")
                );
            }
            connection.tracePacketsForMillis(PACKET_TRACE_MILLIS);
            if (PACKET_TRACE_MILLIS > 0) {
                System.out.printf(
                        "Enabled detailed packet trace for %s for %dms after AvailableCommands at +%dms.%n",
                        connection.client().getSocketAddress(),
                        PACKET_TRACE_MILLIS,
                        connection.elapsedMillis()
                );
            }
        }
        if (packet instanceof CommandOutputPacket commandOutput) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding backend command output from %s: type=%s successCount=%d messages=%d.%n",
                        backendName,
                        commandOutput.getType(),
                        commandOutput.getSuccessCount(),
                        commandOutput.getMessages().size()
                );
            }
        }
        if (packet instanceof UnknownPacket unknownPacket) {
            logUnknownBackendPacket(unknownPacket);
            if (unknownPacket.getPacketId() == COMMAND_OUTPUT_PACKET_ID) {
                System.out.printf(
                        "Dropping undecodable backend command output packet %d from %s to avoid client disconnect.%n",
                        unknownPacket.getPacketId(),
                        backendName
                );
                return PacketSignal.HANDLED;
            }
        }
        if (packet instanceof UpdatePlayerGameTypePacket updateGameType) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding UpdatePlayerGameType from backend %s: gameType=%s entityId=%d tick=%d.%n",
                        backendName,
                        updateGameType.getGameType(),
                        updateGameType.getEntityId(),
                        updateGameType.getTick()
                );
            }
        } else if (packet instanceof SetPlayerGameTypePacket setGameType) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding SetPlayerGameType from backend %s: gamemode=%d.%n",
                        backendName,
                        setGameType.getGamemode()
                );
            }
        } else if (packet instanceof DisconnectPacket disconnect) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding backend disconnect packet from %s: reason=%s skipped=%s message=%s filtered=%s.%n",
                        backendName,
                        disconnect.getReason(),
                        disconnect.isMessageSkipped(),
                        disconnect.getKickMessage(),
                        disconnect.getFilteredMessage()
                );
            }
        }
        if (packet instanceof RespawnPacket respawn) {
            if (respawn.getState() == RespawnPacket.State.SERVER_READY) {
                // Respawn cycle finished; allow the next death to drive its own handshake.
                deathRespawnHandshakeDriven = false;
                flushPendingPostSwitchInit();
            }
            acknowledgeInitialCrossProtocolRespawn(respawn);
            rememberInitialCrossProtocolServerReady(respawn);
            if (suppressDuplicateInitialCrossProtocolServerReady(respawn, traceSequence)) {
                return PacketSignal.HANDLED;
            }
            if (suppressCrossProtocolDeathServerSearching(respawn, traceSequence)) {
                return PacketSignal.HANDLED;
            }
        }
        if (packet instanceof PlayStatusPacket playStatus) {
            prepareInitialCrossProtocolPlayerSpawn(playStatus, traceSequence);
        }
        if (MATERIALIZE_CACHED_CHUNKS_FOR_CROSS_PROTOCOL
                && packet instanceof ClientCacheMissResponsePacket missResponse
                && handleInternalChunkBlobResponse(missResponse, traceSequence)) {
            return PacketSignal.HANDLED;
        }
        BackendSwitchReset switchReset = connection.backendSwitchReset();
        if (!pendingStartGame
                && switchReset != null
                && switchReset.isActive()
                && backend == connection.backend()
                && suppressWorldStateDuringSwitchReset(packet)) {
            if (packet instanceof RespawnPacket respawn) {
                acknowledgeRespawn(respawn);
            }
            captureSwitchResetPlayerState(packet);
            boolean deferred = captureSwitchResetWorldState(packet);
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "%s clientbound packet from backend %s during switch reset: %s.%n",
                        deferred ? "Deferring" : "Suppressing",
                        backendName,
                        packet.getClass().getSimpleName()
                );
            }
            return PacketSignal.HANDLED;
        }
        if (suppressInitialCrossProtocolEntitySpawn(packet, traceSequence)) {
            return PacketSignal.HANDLED;
        }
        long unknownRuntimeEntityId = unknownRuntimeEntityUpdate(packet);
        if (unknownRuntimeEntityId > 0) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Dropping clientbound entity update from backend %s for unknown runtimeEntityId=%d: %s.%n",
                        backendName,
                        unknownRuntimeEntityId,
                        packet.getClass().getSimpleName()
                );
            }
            return PacketSignal.HANDLED;
        }
        BedrockPacket translated = connection.sessionProfile()
                .translator()
                .translateClientbound(rewriteClientboundRuntimeIds(packet), connection.sessionProfile().translationContext());
        if (translated == null) {
            System.out.printf(
                    "Dropping clientbound packet from backend %s after protocol translation for client protocol %d: %s.%n",
                    backendName,
                    connection.sessionProfile().clientCodec().getProtocolVersion(),
                    packet.getClass().getSimpleName()
            );
            return PacketSignal.HANDLED;
        }
        flushDeferredInitialChunkRadiusUpdatedIfReady();
        if (MATERIALIZE_CACHED_CHUNKS_FOR_CROSS_PROTOCOL
                && translated instanceof LevelChunkPacket chunk
                && holdCachedLevelChunk(chunk, traceSequence)) {
            connection.clientWorldState().track(packet);
            return PacketSignal.HANDLED;
        }
        if (suppressEarlyInitialChunkRadiusUpdated(translated, traceSequence)) {
            connection.clientWorldState().track(packet);
            return PacketSignal.HANDLED;
        }
        if (suppressInitialCrossProtocolEmptyChunkPublisher(translated, traceSequence)) {
            connection.clientWorldState().track(packet);
            return PacketSignal.HANDLED;
        }

        if (bufferInitialClientboundUntilLoadingStart(packet, translated, traceSequence)) {
            connection.clientWorldState().track(packet);
            return PacketSignal.HANDLED;
        }

        boolean sent = sendTranslatedClientbound(translated, packet.getClass().getSimpleName(), traceSequence, false);
        if (sent && translated instanceof StartGamePacket) {
            // From here on an unexpected backend loss can be turned into a switch rather than a kick.
            connection.markClientJoinedWorld();
        }
        if (isBiomeDefinitionPacket(translated)) {
            initialBiomeDefinitionForwarded = true;
        }
        if (sent && translated instanceof LevelChunkPacket chunk) {
            markInitialLevelChunkForwarded(chunk);
        } else if (sent && translated instanceof NetworkChunkPublisherUpdatePacket) {
            rememberInitialCrossProtocolPublisherChunks((NetworkChunkPublisherUpdatePacket) translated);
        }
        connection.clientWorldState().track(packet);
        if (pendingStartGame) {
            sendSwitchWorldReadyPackets((StartGamePacket) packet, sourceDimension);
        }
        return PacketSignal.HANDLED;
    }

    /**
     * Keeps a pending/transitioning backend's input-permission mask from leaking into the old world.
     * The reset applies the latest target mask once the client has finished changing dimensions.
     */
    private boolean captureSwitchInputLocks(UpdateClientInputLocksPacket inputLocks) {
        backendInputLockData = inputLocks.getLockComponentData();
        if (backend == connection.pendingBackend()) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Captured pre-StartGame input locks for pending backend %s: mask=%d.%n",
                        backendName,
                        backendInputLockData
                );
            }
            return true;
        }
        BackendSwitchReset switchReset = connection.backendSwitchReset();
        if (switchReset == null || !switchReset.isActive() || backend != connection.backend()) {
            return false;
        }
        switchReset.rememberTargetInputLocks(backendInputLockData);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Captured input locks for backend %s during switch reset: mask=%d.%n",
                    backendName,
                    backendInputLockData
            );
        }
        return true;
    }

    /**
     * Turns a backend's transfer to another configured backend into an in-proxy handoff. External
     * destinations return {@code false} and continue through the ordinary relay path unchanged.
     */
    private boolean interceptInternalTransfer(TransferPacket transfer) {
        // The switch path preserves an existing client world. Before the first StartGame there is
        // no world to reset, so retain vanilla transfer behaviour for early-login redirects.
        if (backend != connection.backend()
                || !connection.hasClientJoinedWorld()
                || backendDirectory == null
                || backendSwitcher == null) {
            return false;
        }
        BackendConfig target = backendDirectory
                .findByAddress(transfer.getAddress(), transfer.getPort())
                .orElse(null);
        if (target == null) {
            return false;
        }

        System.out.printf(
                "Intercepting backend transfer for %s from %s to configured backend %s (%s:%d).%n",
                connection.clientLogin().authData().displayName(),
                backendName,
                target.name(),
                transfer.getAddress(),
                transfer.getPort()
        );
        if (!backendSwitcher.switchBackend(connection, target)) {
            System.out.printf(
                    "Internal transfer for %s to backend %s was consumed without starting a new switch; "
                            + "current=%s pending=%s.%n",
                    connection.clientLogin().authData().displayName(),
                    target.name(),
                    connection.backendName(),
                    connection.backendSwitchTarget()
            );
        }
        // Once an endpoint is known to this proxy, never tell the client to reconnect to it
        // directly. Doing so would be slower and could bypass backend verification.
        return true;
    }

    private boolean bufferInitialClientboundUntilLoadingStart(
            BedrockPacket original,
            BedrockPacket translated,
            long traceSequence
    ) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !initialBiomeDefinitionForwarded
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null
                || translated instanceof DisconnectPacket
                || translated instanceof PlayStatusPacket) {
            return false;
        }
        if (connection.hasInitialLoadingScreenStarted()
                && hasPendingInitialLevelChunk()
                && !(translated instanceof LevelChunkPacket)) {
            return false;
        }
        pendingInitialClientbound.addLast(new PendingInitialClientbound(
                ReferenceCountUtil.retain(translated),
                original.getClass().getSimpleName(),
                translated.getClass().getSimpleName(),
                traceSequence
        ));
        if (traceSequence > 0) {
            System.out.printf(
                    "Buffered initial clientbound #%d +%dms backend=%s original=%s translated=%s until real loading-screen start and first LevelChunk; pending=%d.%n",
                    traceSequence,
                    connection.elapsedMillis(),
                    backendName,
                    original.getClass().getSimpleName(),
                    translated.getClass().getSimpleName(),
                    pendingInitialClientbound.size()
            );
        }
        if (translated instanceof LevelChunkPacket) {
            flushPendingInitialClientboundIfReady();
        }
        return true;
    }

    private void flushPendingInitialClientboundIfReady() {
        if (pendingInitialClientbound.isEmpty()
                || !connection.hasInitialLoadingScreenStarted()
                || !connection.client().isConnected()
                || !hasPendingInitialLevelChunk()) {
            return;
        }
        System.out.printf(
                "Flushing %d buffered initial clientbound packet(s) for %s after real loading-screen start and first LevelChunk.%n",
                pendingInitialClientbound.size(),
                connection.client().getSocketAddress()
        );
        while (!pendingInitialClientbound.isEmpty() && connection.client().isConnected()) {
            PendingInitialClientbound pending = pendingInitialClientbound.removeFirst();
            boolean sent = sendTranslatedClientbound(pending.packet(), pending.originalName(), pending.traceSequence(), true);
            if (isBiomeDefinitionPacket(pending.packet())) {
                initialBiomeDefinitionForwarded = true;
            }
            if (sent && pending.packet() instanceof LevelChunkPacket chunk) {
                markInitialLevelChunkForwarded(chunk);
            } else if (sent && pending.packet() instanceof NetworkChunkPublisherUpdatePacket) {
                rememberInitialCrossProtocolPublisherChunks((NetworkChunkPublisherUpdatePacket) pending.packet());
            }
        }
    }

    private boolean hasPendingInitialLevelChunk() {
        for (PendingInitialClientbound pending : pendingInitialClientbound) {
            if (pending.packet() instanceof LevelChunkPacket) {
                return true;
            }
        }
        return false;
    }

    private void markInitialLevelChunkForwarded(LevelChunkPacket chunk) {
        int forwardedLevelChunks = connection.markLevelChunkForwarded();
        if (isCrossProtocol()
                && connection.backendSwitchReset() == null
                && !initialBackendPlayerSpawnPrepared) {
            initialForwardedLevelChunks.add(Vector2i.from(chunk.getChunkX(), chunk.getChunkZ()));
            sendInitialCrossProtocolChunkBatchPublisher(forwardedLevelChunks);
            sendInitialCrossProtocolKeepAlivePlayerSpawn("initial chunk set", forwardedLevelChunks);
        }
    }

    private boolean suppressInitialCrossProtocolEmptyChunkPublisher(BedrockPacket packet, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                || !(packet instanceof NetworkChunkPublisherUpdatePacket publisher)
                || !publisher.getSavedChunks().isEmpty()
                || !connection.hasForwardedLevelChunk()
                || connection.hasInitialClientChunkCacheStatusSeen()
                || connection.hasSentInitialSyntheticPlayerSpawn()
                || connection.backendSwitchReset() != null) {
            return false;
        }

        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Suppressing empty initial cross-protocol chunk publisher #%d from backend %s while waiting for client cache status: position=%s radius=%d cachedChunks=%d.%n",
                    traceSequence,
                    backendName,
                    publisher.getPosition(),
                    publisher.getRadius(),
                    initialSyntheticCachedLevelChunks.size()
            );
        }
        return true;
    }

    private boolean suppressEarlyInitialChunkRadiusUpdated(BedrockPacket packet, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                || !(packet instanceof ChunkRadiusUpdatedPacket radiusUpdated)
                || connection.backendSwitchReset() != null
                || connection.hasInitialClientChunkCacheStatusSeen()
                || connection.hasSentInitialSyntheticPlayerSpawn()) {
            return false;
        }

        ChunkRadiusUpdatedPacket deferred = new ChunkRadiusUpdatedPacket();
        deferred.setRadius(radiusUpdated.getRadius());
        deferredInitialChunkRadiusUpdated = deferred;
        deferredInitialChunkRadiusTraceSequence = traceSequence;
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Deferring early initial cross-protocol ChunkRadiusUpdated #%d from backend %s until client cache status: radius=%d forwardedChunks=%d.%n",
                    traceSequence,
                    backendName,
                    radiusUpdated.getRadius(),
                    connection.forwardedLevelChunks()
            );
        }
        return true;
    }

    private void flushDeferredInitialChunkRadiusUpdatedIfReady() {
        if (deferredInitialChunkRadiusUpdated == null
                || !connection.hasInitialClientChunkCacheStatusSeen()
                || !connection.client().isConnected()) {
            return;
        }

        ChunkRadiusUpdatedPacket deferred = deferredInitialChunkRadiusUpdated;
        long traceSequence = deferredInitialChunkRadiusTraceSequence;
        deferredInitialChunkRadiusUpdated = null;
        deferredInitialChunkRadiusTraceSequence = -1;
        sendTranslatedClientbound(deferred, ChunkRadiusUpdatedPacket.class.getSimpleName(), traceSequence, false);
        System.out.printf(
                "Forwarded deferred initial cross-protocol ChunkRadiusUpdated #%d to client %s after cache status: radius=%d.%n",
                traceSequence,
                connection.client().getSocketAddress(),
                deferred.getRadius()
        );
    }

    private void sendInitialCrossProtocolChunkBatchPublisher(int forwardedLevelChunks) {
        if (!SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL) {
            return;
        }
        int batchReadyChunks = SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                ? initialSyntheticCachedLevelChunks.size()
                : forwardedLevelChunks;
        Set<Vector2i> savedChunks = SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                ? initialSyntheticCachedLevelChunks
                : initialForwardedLevelChunks;
        if (batchReadyChunks < INITIAL_CROSS_PROTOCOL_CHUNK_BATCH_MIN_CHUNKS
                || savedChunks.isEmpty()
                || savedChunks.size() <= initialChunkBatchPublisherSavedChunkCount
                || (initialChunkBatchPublisherSavedChunkCount > 0
                        && savedChunks.size() < initialChunkBatchPublisherSavedChunkCount
                        + INITIAL_CROSS_PROTOCOL_CHUNK_BATCH_INCREMENT_CHUNKS)
                || !connection.client().isConnected()) {
            return;
        }

        NetworkChunkPublisherUpdatePacket batch = new NetworkChunkPublisherUpdatePacket();
        batch.setPosition(initialPlayerSpawnPublisherPosition());
        batch.setRadius(initialCrossProtocolChunkCachePublisherRadius());
        batch.getSavedChunks().addAll(savedChunks);
        initialChunkBatchPublisherSavedChunkCount = batch.getSavedChunks().size();
        connection.client().sendPacket(batch);
        System.out.printf(
                "Sent initial cross-protocol chunk batch publisher for client %s from backend %s after %d LevelChunk packets (%d cached): radius=%d savedChunks=%d cacheMode=%s.%n",
                connection.client().getSocketAddress(),
                backendName,
                forwardedLevelChunks,
                initialSyntheticCachedLevelChunks.size(),
                batch.getRadius(),
                batch.getSavedChunks().size(),
                SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL ? "synthetic" : "full"
        );
    }

    private boolean sendTranslatedClientbound(
            BedrockPacket translated,
            String originalName,
            long traceSequence,
            boolean buffered
    ) {
        BedrockPacket outbound = translated;
        boolean generatedOutbound = false;
        if (translated instanceof LevelChunkPacket) {
            sendInitialCrossProtocolServerReady();
            normalizeInitialCrossProtocolLevelChunk((LevelChunkPacket) translated, traceSequence);
            if (SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL) {
                SyntheticClientCachedChunk cachedChunk = syntheticClientCachedChunk((LevelChunkPacket) translated, traceSequence);
                if (cachedChunk != null && cachedChunk.drop()) {
                    return false;
                }
                if (cachedChunk != null && cachedChunk.packet() != null) {
                    outbound = cachedChunk.packet();
                    generatedOutbound = true;
                }
            } else if (isCrossProtocol() && connection.isPacketTraceActive()) {
                LevelChunkPacket chunk = (LevelChunkPacket) translated;
                System.out.printf(
                        "Forwarding full cross-protocol LevelChunk #%d to client %s without synthetic cache: chunk=(%d,%d) dimension=%d dataBytes=%d.%n",
                        traceSequence,
                        connection.client().getSocketAddress(),
                        chunk.getChunkX(),
                        chunk.getChunkZ(),
                        chunk.getDimension(),
                        chunk.getData() == null ? 0 : chunk.getData().readableBytes()
                );
            }
        }
        injectVerifiedXuids(outbound);
        connection.client().sendPacket(buffered || generatedOutbound ? outbound : ReferenceCountUtil.retain(outbound));
        if (MATERIALIZE_CACHED_CHUNKS_FOR_CROSS_PROTOCOL && translated instanceof LevelChunkPacket chunk) {
            requestMissingChunkBlobs(chunk);
        }
        if (traceSequence > 0) {
            System.out.printf(
                    "%s clientbound #%d +%dms backend=%s original=%s translated=%s clientConnected=%s backendConnected=%s.%n",
                    buffered ? "Flushed buffered" : "Forwarded",
                    traceSequence,
                    connection.elapsedMillis(),
                    backendName,
                    originalName,
                    translated.getClass().getSimpleName(),
                    connection.client().isConnected(),
                    backend.isConnected()
            );
        }
        return true;
    }

    /**
     * Substitutes proxy-verified XUIDs into outgoing PlayerListPacket entries. BDS
     * 1.26.10+ in offline mode does not trust self-signed OIDC `xid` claims, so the
     * backend's outgoing PlayerListPacket has empty xuid fields. We have the real
     * XUID for every connected proxy client (from their Mojang-signed login chain)
     * and inject it here so the client-side friends tab and any xuid-keyed lookups
     * still work.
     */
    private void injectVerifiedXuids(BedrockPacket packet) {
        if (!(packet instanceof PlayerListPacket playerList)) {
            return;
        }
        if (playerList.getAction() != PlayerListPacket.Action.ADD) {
            return;
        }
        for (PlayerListPacket.Entry entry : playerList.getEntries()) {
            String existing = entry.getXuid();
            if (existing != null && !existing.isBlank() && !"0".equals(existing)) {
                continue;
            }
            CharSequence name = entry.getName();
            if (name == null) {
                continue;
            }
            String verified;
            try {
                verified = verifiedXuidLookup.apply(name.toString());
            } catch (RuntimeException exception) {
                verified = null;
            }
            if (verified != null && !verified.isBlank()) {
                entry.setXuid(verified);
            }
        }
    }

    private void normalizeInitialCrossProtocolLevelChunk(LevelChunkPacket chunk, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                || connection.backendSwitchReset() != null
                || connection.hasInitialLocalPlayerInitialized()
                || !chunk.isRequestSubChunks()
                || chunk.getSubChunkLimit() >= INITIAL_CROSS_PROTOCOL_MIN_SUBCHUNK_LIMIT) {
            return;
        }

        int original = chunk.getSubChunkLimit();
        chunk.setSubChunkLimit(INITIAL_CROSS_PROTOCOL_MIN_SUBCHUNK_LIMIT);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Normalized initial cross-protocol LevelChunk #%d subChunkLimit from %d to %d for client %s: chunk=(%d,%d) dimension=%d.%n",
                    traceSequence,
                    original,
                    chunk.getSubChunkLimit(),
                    connection.client().getSocketAddress(),
                    chunk.getChunkX(),
                    chunk.getChunkZ(),
                    chunk.getDimension()
            );
        }
    }

    private SyntheticClientCachedChunk syntheticClientCachedChunk(LevelChunkPacket source, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || source.isCachingEnabled()
                || source.getData() == null
                || source.getData().readableBytes() == 0
                || connection.backendSwitchReset() != null) {
            return null;
        }

        byte[] blob = copyReadableBytes(source.getData());
        if (blob.length < MIN_SYNTHETIC_CLIENT_CACHE_BLOB_BYTES) {
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Dropping tiny synthetic-cache LevelChunk #%d during initial client cache phase for client %s: chunk=(%d,%d) dimension=%d blobBytes=%d minBlobBytes=%d.%n",
                        traceSequence,
                        connection.client().getSocketAddress(),
                        source.getChunkX(),
                        source.getChunkZ(),
                        source.getDimension(),
                        blob.length,
                        MIN_SYNTHETIC_CLIENT_CACHE_BLOB_BYTES
                );
            }
            return new SyntheticClientCachedChunk(null, true);
        }
        long blobId = xxHash64(blob, 0);
        boolean newBlob = advertisedSyntheticClientChunkBlobs.add(blobId);
        if (newBlob) {
            connection.rememberSyntheticClientChunkBlob(blobId, blob);
        } else if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Reusing duplicate synthetic-cache blob for LevelChunk #%d during initial client cache phase for client %s: chunk=(%d,%d) dimension=%d blobId=%d blobBytes=%d.%n",
                    traceSequence,
                    connection.client().getSocketAddress(),
                    source.getChunkX(),
                    source.getChunkZ(),
                    source.getDimension(),
                    blobId,
                    blob.length
            );
        }
        initialSyntheticCachedLevelChunks.add(Vector2i.from(source.getChunkX(), source.getChunkZ()));

        LevelChunkPacket shell = new LevelChunkPacket();
        shell.setChunkX(source.getChunkX());
        shell.setChunkZ(source.getChunkZ());
        shell.setDimension(source.getDimension());
        shell.setSubChunksLength(source.getSubChunksLength());
        shell.setRequestSubChunks(source.isRequestSubChunks());
        shell.setSubChunkLimit(source.getSubChunkLimit());
        shell.setCachingEnabled(true);
        shell.getBlobIds().add(blobId);
        shell.setData(Unpooled.EMPTY_BUFFER);

        if (connection.isPacketTraceActive()) {
            Vector3i publisherPosition = initialPlayerSpawnPublisherPosition();
            System.out.printf(
                    "Materialized cross-protocol client cache LevelChunk #%d for client %s: chunk=(%d,%d) dimension=%d blobId=%d blobBytes=%d publisherChunk=(%d,%d) publisherRadius=%d.%n",
                    traceSequence,
                    connection.client().getSocketAddress(),
                    source.getChunkX(),
                    source.getChunkZ(),
                    source.getDimension(),
                    blobId,
                    blob.length,
                    Math.floorDiv(publisherPosition.getX(), 16),
                    Math.floorDiv(publisherPosition.getZ(), 16),
                    initialCrossProtocolChunkCachePublisherRadius()
            );
        }
        return new SyntheticClientCachedChunk(shell, false);
    }

    private static boolean suppressWorldStateDuringSwitchReset(BedrockPacket packet) {
        if (packet instanceof DisconnectPacket || packet instanceof PlayStatusPacket) {
            return false;
        }
        if (packet instanceof RespawnPacket
                || packet instanceof LevelChunkPacket
                || packet instanceof NetworkChunkPublisherUpdatePacket) {
            return true;
        }
        return switch (packet.getClass().getSimpleName()) {
            case "AddEntityPacket",
                 "AddHangingEntityPacket",
                 "AddItemEntityPacket",
                 "AddPaintingPacket",
                 "AddPlayerPacket",
                 "AnimatePacket",
                 "BlockEventPacket",
                 "BlockPickRequestPacket",
                 "ChunkRadiusUpdatedPacket",
                 "ClientboundMapItemDataPacket",
                 "CorrectPlayerMovePredictionPacket",
                 "CurrentStructureFeaturePacket",
                 "EntityEventPacket",
                 "LevelEventPacket",
                 "LevelEventGenericPacket",
                 "LevelSoundEventPacket",
                 "MoveEntityAbsolutePacket",
                 "MoveEntityDeltaPacket",
                 "MovePlayerPacket",
                 "RemoveEntityPacket",
                 "SetEntityDataPacket",
                 "SetEntityLinkPacket",
                 "SetEntityMotionPacket",
                 "SetHealthPacket",
                 "SetTitlePacket",
                 "SubChunkPacket",
                 "TakeItemEntityPacket",
                 "UpdateAttributesPacket",
                 "UpdateBlockPacket",
                 "UpdateBlockSyncedPacket",
                 "UpdateSubChunkBlocksPacket" -> true;
            default -> false;
        };
    }

    /**
     * The backend emits the local player's authoritative state (entity metadata, attributes such as
     * health/hunger/movement speed, and current health) exactly once, in the join burst right after
     * StartGame. During a backend switch that burst arrives while {@link BackendSwitchReset} is
     * suppressing world-state packets, so without this capture those packets are dropped and never
     * replayed, leaving the player with stale state after the switch (wrong/zero health, frozen
     * movement, unable to interact). We translate and stash a client-ready copy here and replay it
     * once the switch reset completes.
     */
    private void captureSwitchResetPlayerState(BedrockPacket packet) {
        if (!isLocalPlayerStatePacket(packet)) {
            return;
        }
        BedrockPacket translated = connection.sessionProfile()
                .translator()
                .translateClientbound(rewriteClientboundRuntimeIds(packet), connection.sessionProfile().translationContext());
        if (translated == null) {
            return;
        }
        connection.addDeferredSwitchPlayerState(ReferenceCountUtil.retain(translated));
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Captured local-player state during switch reset for %s to replay after spawn: %s.%n",
                    backendName,
                    packet.getClass().getSimpleName()
            );
        }
    }

    /**
     * The backend streams each chunk to a player exactly once — once it is in that player's chunk view
     * it is never re-sent unless it leaves and re-enters the view radius. A backend whose spawn area is
     * already loaded and cheap to serialize (a skyblock or otherwise mostly-empty world) can therefore
     * deliver everything around the player within a few hundred ms of StartGame, well inside the switch
     * reset's dimension-bounce window. Dropping that burst strands the player in a void the backend will
     * never refill, so buffer it here and let {@link BackendSwitchReset} replay it once the client is
     * back in the target dimension.
     *
     * @return whether the packet was captured for replay
     */
    private boolean captureSwitchResetWorldState(BedrockPacket packet) {
        if (!isDeferrableWorldStatePacket(packet)) {
            return false;
        }
        BedrockPacket translated = connection.sessionProfile()
                .translator()
                .translateClientbound(rewriteClientboundRuntimeIds(packet), connection.sessionProfile().translationContext());
        if (translated == null) {
            return false;
        }
        BedrockPacket retained = ReferenceCountUtil.retain(translated);
        if (connection.addDeferredSwitchWorldState(retained)) {
            return true;
        }
        ReferenceCountUtil.release(retained);
        return false;
    }

    /**
     * World geometry the backend will not resend on its own. Deliberately excludes entity spawns and
     * movement — the backend re-announces entities as they tick back into view, so replaying stale
     * copies of those would fight the live stream rather than fill a gap.
     */
    private static boolean isDeferrableWorldStatePacket(BedrockPacket packet) {
        return packet instanceof LevelChunkPacket
                || packet instanceof SubChunkPacket
                || packet instanceof NetworkChunkPublisherUpdatePacket
                || packet instanceof UpdateBlockPacket
                || packet instanceof UpdateBlockSyncedPacket
                || packet instanceof UpdateSubChunkBlocksPacket;
    }

    private boolean isLocalPlayerStatePacket(BedrockPacket packet) {
        long playerRuntimeEntityId = connection.backendPlayerRuntimeEntityId();
        if (packet instanceof UpdateAttributesPacket attributes) {
            return playerRuntimeEntityId > 0 && attributes.getRuntimeEntityId() == playerRuntimeEntityId;
        }
        if (packet instanceof SetEntityDataPacket entityData) {
            return playerRuntimeEntityId > 0 && entityData.getRuntimeEntityId() == playerRuntimeEntityId;
        }
        return packet instanceof SetHealthPacket;
    }

    private boolean suppressInitialCrossProtocolEntitySpawn(BedrockPacket packet, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.backendSwitchReset() != null
                || connection.hasInitialLocalPlayerInitialized()
                || !connection.hasForwardedLevelChunk()
                || !isNonPlayerEntitySpawn(packet)) {
            return false;
        }

        // Capture (translate + register the runtime id) the suppressed spawn so it can be replayed to
        // the client once the player is initialized. Registering now also stops this entity's later
        // movement/metadata from being dropped as "unknown" while we wait for initialization.
        BedrockPacket translated = connection.sessionProfile()
                .translator()
                .translateClientbound(rewriteClientboundRuntimeIds(packet), connection.sessionProfile().translationContext());
        if (translated != null) {
            connection.addDeferredInitialEntitySpawn(ReferenceCountUtil.retain(translated));
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Suppressing (and deferring for replay) initial cross-protocol entity spawn #%d from backend %s until local player is initialized: %s runtimeEntityId=%d.%n",
                    traceSequence,
                    backendName,
                    packet.getClass().getSimpleName(),
                    runtimeEntityIdForEntitySpawn(packet)
            );
        }
        return true;
    }

    private static boolean isNonPlayerEntitySpawn(BedrockPacket packet) {
        return packet instanceof AddEntityPacket
                || packet instanceof AddItemEntityPacket
                || packet instanceof AddHangingEntityPacket
                || packet instanceof AddPlayerPacket;
    }

    private static long runtimeEntityIdForEntitySpawn(BedrockPacket packet) {
        if (packet instanceof AddEntityPacket addEntity) {
            return addEntity.getRuntimeEntityId();
        }
        if (packet instanceof AddItemEntityPacket addItem) {
            return addItem.getRuntimeEntityId();
        }
        if (packet instanceof AddHangingEntityPacket addHanging) {
            return addHanging.getRuntimeEntityId();
        }
        if (packet instanceof AddPlayerPacket addPlayer) {
            return addPlayer.getRuntimeEntityId();
        }
        return 0;
    }

    private static boolean isBiomeDefinitionPacket(BedrockPacket packet) {
        return packet instanceof BiomeDefinitionListPacket
                || packet instanceof CompressedBiomeDefinitionListPacket;
    }

    private long unknownRuntimeEntityUpdate(BedrockPacket packet) {
        long runtimeEntityId = runtimeEntityIdForExistingEntity(packet);
        return runtimeEntityId > 0 && !connection.hasBackendRuntimeEntityId(runtimeEntityId)
                ? runtimeEntityId
                : 0;
    }

    private static long runtimeEntityIdForExistingEntity(BedrockPacket packet) {
        if (packet instanceof MoveEntityDeltaPacket moveEntity) {
            return moveEntity.getRuntimeEntityId();
        }
        if (packet instanceof MoveEntityAbsolutePacket moveEntity) {
            return moveEntity.getRuntimeEntityId();
        }
        if (packet instanceof MovePlayerPacket movePlayer) {
            return movePlayer.getRuntimeEntityId();
        }
        if (packet instanceof SetEntityDataPacket entityData) {
            return entityData.getRuntimeEntityId();
        }
        if (packet instanceof SetEntityMotionPacket entityMotion) {
            return entityMotion.getRuntimeEntityId();
        }
        if (packet instanceof UpdateAttributesPacket attributes) {
            return attributes.getRuntimeEntityId();
        }
        if (packet instanceof EntityEventPacket entityEvent) {
            return entityEvent.getRuntimeEntityId();
        }
        if (packet instanceof EntityFallPacket entityFall) {
            return entityFall.getRuntimeEntityId();
        }
        if (packet instanceof AnimatePacket animate) {
            return animate.getRuntimeEntityId();
        }
        if (packet instanceof MovementEffectPacket movementEffect) {
            return movementEffect.getEntityRuntimeId();
        }
        if (packet instanceof MovementPredictionSyncPacket movementPrediction) {
            return movementPrediction.getRuntimeEntityId();
        }
        if (packet instanceof UpdateBlockSyncedPacket blockSynced) {
            return blockSynced.getRuntimeEntityId();
        }
        if (packet instanceof TakeItemEntityPacket takeItem) {
            return takeItem.getItemRuntimeEntityId();
        }
        return 0;
    }

    private void clearPreviousClientWorldState() {
        var cleanupPackets = connection.clientWorldState().clearPackets();
        if (!cleanupPackets.isEmpty() && connection.isPacketTraceActive()) {
            System.out.printf(
                    "Clearing previous backend client-world state before switching to %s with %d packets.%n",
                    backendName,
                    cleanupPackets.size()
            );
        }
        for (BedrockPacket cleanupPacket : cleanupPackets) {
            BedrockPacket translated = connection.sessionProfile()
                    .translator()
                    .translateClientbound(cleanupPacket, connection.sessionProfile().translationContext());
            if (translated == null) {
                System.out.printf(
                        "WARNING: Skipping previous-world cleanup packet after protocol translation for client protocol %d: %s.%n",
                        connection.sessionProfile().clientCodec().getProtocolVersion(),
                        cleanupPacket.getClass().getSimpleName()
                );
                continue;
            }
            connection.client().sendPacket(ReferenceCountUtil.retain(translated));
        }
    }

    private boolean acknowledgePendingSwitchLoginPacket(BedrockPacket packet) {
        if (packFetch != null && !packFetch.isFinished() && packFetch.handle(packet)) {
            return true;
        }
        if (packet instanceof ResourcePacksInfoPacket packsInfo) {
            if (!packsInfo.getResourcePackInfos().isEmpty() || !packsInfo.getBehaviorPackInfos().isEmpty()) {
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Acknowledging %d resource packs and %d behavior packs for pending backend %s during switch.%n",
                            packsInfo.getResourcePackInfos().size(),
                            packsInfo.getBehaviorPackInfos().size(),
                            backendName
                    );
                }
                warnAboutUnservedSwitchPacks(packsInfo);
            } else if (connection.isPacketTraceActive()) {
                System.out.printf("Acknowledging empty resource-pack info for pending backend %s during switch.%n", backendName);
            }
            // The one moment this backend's packs can be obtained: nobody else will ever ask it for
            // them, because a client only downloads packs during its own login.
            packFetch = BackendPackFetch.start(
                    connection.backendPackCache(),
                    backendName,
                    packsInfo,
                    backend::sendPacket,
                    () -> sendPackResponse(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS)
            );
            if (packFetch != null) {
                schedulePackFetchDeadline(packFetch);
                return true;
            }
            sendPackResponse(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
            return true;
        }
        if (packet instanceof ResourcePackStackPacket) {
            if (connection.isPacketTraceActive()) {
                System.out.printf("Completing resource-pack stack for pending backend %s during switch.%n", backendName);
            }
            sendPackResponse(ResourcePackClientResponsePacket.Status.COMPLETED);
            return true;
        }
        return false;
    }

    /**
     * Warns when a backend joined mid-session advertises packs the proxy is not serving itself.
     *
     * <p>Bedrock runs the resource-pack handshake exactly once, before StartGame. A client already in
     * a world cannot be made to fetch and apply a new pack stack, so on a switch the proxy has to
     * answer that handshake on the client's behalf — the packs are acknowledged to the backend and the
     * client never sees them. If those packs are also in {@code resourcePacks.dir} the client already
     * downloaded and applied them at login and everything renders; if they are not, the player gets
     * the backend's custom content with no client-side definitions: custom entities render as nothing
     * (still solid and clickable) and custom items fall back to arbitrary vanilla textures.
     *
     * <p>That failure is otherwise completely silent — no kick, no error, just wrong visuals — so name
     * the specific packs that need copying into {@code resourcePacks.dir}.</p>
     */
    private void warnAboutUnservedSwitchPacks(ResourcePacksInfoPacket packsInfo) {
        ProxyResourcePackRegistry registry = connection.proxyResourcePackRegistry();
        List<String> unserved = new ArrayList<>();
        for (ResourcePacksInfoPacket.Entry entry : packsInfo.getResourcePackInfos()) {
            if (entry.getPackId() == null || !registry.isProxyPack(entry.getPackId())) {
                unserved.add(entry.getPackId() + " v" + entry.getPackVersion());
            }
        }
        if (unserved.isEmpty()) {
            return;
        }
        System.out.printf(
                "WARNING: backend %s uses %d resource pack(s) the proxy does not serve, and a switched "
                        + "client cannot be asked to download them: %s. Custom entities will be invisible and "
                        + "custom items will show wrong textures on this backend. Copy these packs into "
                        + "resourcePacks.dir so every client gets them at login.%n",
                backendName,
                unserved.size(),
                String.join(", ", unserved)
        );
    }

    /**
     * Keeps a copy of a pack the client is downloading from the backend, so the next player gets it
     * from the proxy — including on backends they reach by switching, where no client can download
     * anything.
     *
     * <p>Pure observation: the packets carry on to the client untouched. A client that already has
     * the pack in its own cache never requests it, so nothing is learned from that join; the switch
     * path ({@link BackendPackFetch}) is what guarantees a backend is eventually learned.</p>
     */
    private void captureBackendPackBytes(BedrockPacket packet) {
        BackendPackCache cache = connection.backendPackCache();
        if (!cache.isEnabled()) {
            return;
        }
        if (packet instanceof ResourcePackDataInfoPacket dataInfo) {
            observedPacks.remove(dataInfo.getPackId());
            long size = dataInfo.getCompressedPackSize();
            if (size <= 0 || size > BackendPackCache.MAX_PACK_BYTES
                    || cache.has(dataInfo.getPackId(), ProxyResourcePackRegistry.parseVersion(dataInfo.getPackVersion()))) {
                return;
            }
            observedPacks.put(dataInfo.getPackId(), new ObservedPack(
                    new byte[(int) size], dataInfo.getHash(), Math.max(1, dataInfo.getMaxChunkSize())));
            return;
        }
        if (!(packet instanceof ResourcePackChunkDataPacket chunkData)) {
            return;
        }
        ObservedPack observed = observedPacks.get(chunkData.getPackId());
        if (observed == null) {
            return;
        }
        ByteBuf data = chunkData.getData();
        int offset = (int) Math.min(observed.buffer.length, (long) chunkData.getChunkIndex() * observed.chunkSize);
        int length = data == null ? 0 : Math.min(data.readableBytes(), observed.buffer.length - offset);
        if (length > 0) {
            // getBytes, not readBytes: this buffer is on its way to the client and must not be moved.
            data.getBytes(data.readerIndex(), observed.buffer, offset, length);
            observed.filled += length;
        }
        if (observed.filled >= observed.buffer.length) {
            observedPacks.remove(chunkData.getPackId());
            cache.store(chunkData.getPackId(), observed.buffer, observed.hash);
        }
    }

    private static final class ObservedPack {
        private final byte[] buffer;
        private final byte[] hash;
        private final int chunkSize;
        private int filled;

        private ObservedPack(byte[] buffer, byte[] hash, long chunkSize) {
            this.buffer = buffer;
            this.hash = hash;
            this.chunkSize = (int) Math.min(Integer.MAX_VALUE, chunkSize);
        }
    }

    /**
     * Bounds the pack download in time. The player switching is waiting on it, so a backend that
     * stops answering must not hold them there: the fetch is dropped and the handshake completes with
     * the packs still unlearned, which is exactly the state the proxy was in before it tried.
     */
    private void schedulePackFetchDeadline(BackendPackFetch fetch) {
        connection.client().getPeer().getChannel().eventLoop().schedule(() -> {
            if (!fetch.isFinished()) {
                fetch.abandon("the backend stopped sending after " + PACK_FETCH_TIMEOUT_MILLIS + "ms");
            }
        }, PACK_FETCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sendPackResponse(ResourcePackClientResponsePacket.Status status) {
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(status);
        backend.sendPacket(response);
    }

    private void handleMergedResourcePacksInfo(ResourcePacksInfoPacket backendInfo) {
        ProxyResourcePackRegistry registry = connection.proxyResourcePackRegistry();
        ResourcePacksInfoPacket merged = registry.buildMergedInfo(backendInfo);
        // Deliberately silent. The merge runs on every join and always did the same thing, so the
        // line said nothing an operator could act on. The pack problem that *is* worth reporting —
        // a backend serving packs the proxy does not have — still warns, from checkBackendPacks.
        // Forward merged info to client; client responses flow back through ClientRelayPacketHandler.
        // Proxy pack chunks are served locally there; backend pack chunks are forwarded to backend.
        connection.client().sendPacket(merged);
    }

    private void handleMergedResourcePackStack(ResourcePackStackPacket backendStack) {
        ProxyResourcePackRegistry registry = connection.proxyResourcePackRegistry();
        ResourcePackStackPacket merged = registry.buildMergedStack(backendStack);
        // Silent for the same reason as handleMergedResourcePacksInfo above.
        // Send merged stack to client; the client's COMPLETED response will flow normally
        // through ClientRelayPacketHandler to the backend.
        connection.client().sendPacket(merged);
    }

    private void sendSwitchWorldReadyPackets(StartGamePacket startGame, int sourceDimension) {
        RequestChunkRadiusPacket chunkRadius = new RequestChunkRadiusPacket();
        chunkRadius.setRadius(connection.lastRequestedChunkRadius());
        chunkRadius.setMaxRadius(connection.lastRequestedMaxChunkRadius());
        backend.sendPacket(chunkRadius);

        BackendSwitchReset.start(
                connection,
                backend,
                backendName,
                sourceDimension,
                startGame,
                backendInputLockData
        );

        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Sent switch chunk-radius to backend %s and deferred player initialization until dimension reset ack: chunkRadius=%d maxRadius=%d runtimeEntityId=%d.%n",
                    backendName,
                    chunkRadius.getRadius(),
                    chunkRadius.getMaxRadius(),
                    startGame.getRuntimeEntityId()
            );
        }
    }

    private void flushPendingPostSwitchInit() {
        long runtimeEntityId = connection.consumePendingPostSwitchInit(backend);
        if (runtimeEntityId <= 0) {
            return;
        }
        // The backend has finished the post-switch respawn (SERVER_READY); now it is safe to mark the
        // player initialized so the server treats it as a fully spawned, interactive player.
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(runtimeEntityId);
        backend.sendPacket(initialized);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Sent deferred post-switch SetLocalPlayerAsInitialized to backend %s after SERVER_READY: runtimeEntityId=%d.%n",
                    backendName,
                    runtimeEntityId
            );
        }
    }

    private void acknowledgeRespawn(RespawnPacket respawn) {
        acknowledgeRespawn(respawn.getState(), respawn.getPosition());
    }

    private void acknowledgeRespawn(RespawnPacket.State state, Vector3f position) {
        if (state == RespawnPacket.State.CLIENT_READY) {
            return;
        }
        RespawnPacket ready = new RespawnPacket();
        ready.setState(RespawnPacket.State.CLIENT_READY);
        ready.setPosition(position);
        ready.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
        backend.sendPacket(ready);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Acknowledged respawn for backend %s: state=%s runtimeEntityId=%d position=%s.%n",
                    backendName,
                    state,
                    ready.getRuntimeEntityId(),
                    ready.getPosition()
            );
        }
    }

    private void acknowledgeInitialCrossProtocolRespawn(RespawnPacket respawn) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null
                || respawn.getState() != RespawnPacket.State.SERVER_SEARCHING) {
            return;
        }

        initialSyntheticServerReadyPosition = joinReadyPosition(respawn.getPosition());
        connection.markInitialServerSearchingSeen(initialSyntheticServerReadyPosition);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Observed initial cross-protocol respawn from backend %s: serverState=%s runtimeEntityId=%d position=%s loadingStarted=%s.%n",
                    backendName,
                    respawn.getState(),
                    connection.backendPlayerRuntimeEntityId(),
                    initialSyntheticServerReadyPosition,
                    connection.hasInitialLoadingScreenStarted()
            );
        }
        sendInitialCrossProtocolBackendRespawnReady();
    }

    private synchronized void rememberInitialCrossProtocolServerReady(RespawnPacket respawn) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null) {
            return;
        }
        if (respawn.getState() == RespawnPacket.State.SERVER_READY) {
            connection.markInitialSyntheticServerReadySent();
            return;
        }
        if (respawn.getState() != RespawnPacket.State.SERVER_SEARCHING) {
            return;
        }

        initialSyntheticServerReadyPosition = joinReadyPosition(respawn.getPosition());
    }

    private boolean suppressDuplicateInitialCrossProtocolServerReady(RespawnPacket respawn, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || respawn.getState() != RespawnPacket.State.SERVER_READY
                || connection.backendSwitchReset() != null
                || !connection.hasForwardedLevelChunk()
                || !connection.hasSentInitialSyntheticServerReady()
                || connection.hasInitialLocalPlayerInitialized()) {
            // Only suppress the one real SERVER_READY the backend sends right after the proxy
            // already synthesized one during the initial cross-protocol join. Once the player is
            // fully initialized, every subsequent SERVER_READY is a real death-respawn event and
            // must reach the client.
            return false;
        }

        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Suppressing duplicate backend SERVER_READY respawn #%d from backend %s after synthetic join ready was sent: position=%s.%n",
                    traceSequence,
                    backendName,
                    respawn.getPosition()
            );
        }
        return true;
    }

    private boolean suppressCrossProtocolDeathServerSearching(RespawnPacket respawn, long traceSequence) {
        // a bridge addon (Bedrock→bridge) always cancels SERVER_SEARCHING: the 1.26.20
        // client does not use this state and disconnects upon receiving it during gameplay.
        // The death screen is triggered by DeathInfoPacket; the respawn handshake starts
        // when the client sends START_LOADING_SCREEN (→ CLIENT_READY to backend), which
        // makes the backend emit SERVER_READY.  Only suppress after initial join is done;
        // during initial join the proxy buffers SERVER_SEARCHING for its own purposes.
        if (!backendUsesLegacyDeathRespawn()
                || !connection.hasInitialLocalPlayerInitialized()
                || connection.backendSwitchReset() != null
                || respawn.getState() != RespawnPacket.State.SERVER_SEARCHING) {
            return false;
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Suppressing cross-protocol death SERVER_SEARCHING respawn #%d from backend %s: position=%s.%n",
                    traceSequence,
                    backendName,
                    respawn.getPosition()
            );
        }
        // The 1.26.20 client never drives the old-style respawn handshake after death: it sends no
        // CLIENT_READY and no respawn loading-screen, it simply closes the connection a few hundred
        // ms later. Drive the handshake ourselves so the tickDeathSystems=false backend locates the
        // spawn point and replies with SERVER_READY (plus the teleport + health restore), which
        // reaches the client and respawns it instead of the connection dropping.
        if (!deathRespawnHandshakeDriven) {
            deathRespawnHandshakeDriven = true;
            RespawnPacket clientReady = new RespawnPacket();
            clientReady.setState(RespawnPacket.State.CLIENT_READY);
            clientReady.setPosition(respawn.getPosition());
            clientReady.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
            backend.sendPacket(clientReady);
            System.out.printf(
                    "Drove cross-protocol death respawn for backend %s by sending CLIENT_READY: runtimeEntityId=%d position=%s.%n",
                    backendName,
                    clientReady.getRuntimeEntityId(),
                    respawn.getPosition()
            );
        }
        return true;
    }

    private void rememberInitialCrossProtocolPublisherChunks(NetworkChunkPublisherUpdatePacket publisher) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.backendSwitchReset() != null
                || initialBackendPlayerSpawnPrepared) {
            return;
        }
        initialForwardedLevelChunks.addAll(publisher.getSavedChunks());
    }

    private void prepareInitialCrossProtocolPlayerSpawn(PlayStatusPacket playStatus, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || playStatus.getStatus() != PlayStatusPacket.Status.PLAYER_SPAWN
                || connection.backendSwitchReset() != null
                || !connection.hasForwardedLevelChunk()
                || !connection.client().isConnected()
                || initialBackendPlayerSpawnPrepared) {
            return;
        }

        initialBackendPlayerSpawnPrepared = true;
        InitialCrossProtocolSpawnPrelude prelude = sendInitialCrossProtocolSpawnPrelude(false);

        System.out.printf(
                "Prepared initial cross-protocol PLAYER_SPAWN for client %s from backend %s before backend play status #%d after %d LevelChunk packets: runtimeEntityId=%d publisherRadius=%d savedChunks=%d.%n",
                connection.client().getSocketAddress(),
                backendName,
                traceSequence,
                connection.forwardedLevelChunks(),
                prelude.runtimeEntityId(),
                prelude.publisherRadius(),
                prelude.savedChunks()
        );
    }

    private void sendInitialCrossProtocolKeepAlivePlayerSpawn(String reason, int forwardedLevelChunks) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                || forwardedLevelChunks < INITIAL_CROSS_PROTOCOL_KEEPALIVE_PLAYER_SPAWN_MIN_CHUNKS
                || !connection.hasForwardedLevelChunk()
                || !connection.hasInitialClientChunkCacheStatusSeen()
                || connection.backendSwitchReset() != null
                || !connection.client().isConnected()
                || !connection.markInitialSyntheticPlayerSpawnSent()) {
            return;
        }

        InitialCrossProtocolSpawnPrelude prelude = sendInitialCrossProtocolSpawnPrelude(true);
        System.out.printf(
                "Synthesized initial cross-protocol keepalive PLAYER_SPAWN for client %s from backend %s after %d LevelChunk packets (%s): runtimeEntityId=%d publisherRadius=%d savedChunks=%d.%n",
                connection.client().getSocketAddress(),
                backendName,
                forwardedLevelChunks,
                reason,
                prelude.runtimeEntityId(),
                prelude.publisherRadius(),
                prelude.savedChunks()
        );
    }

    private InitialCrossProtocolSpawnPrelude sendInitialCrossProtocolSpawnPrelude(boolean includePlayerSpawn) {
        NetworkChunkPublisherUpdatePacket readyPublisher = new NetworkChunkPublisherUpdatePacket();
        readyPublisher.setPosition(initialPlayerSpawnPublisherPosition());
        readyPublisher.setRadius(initialPlayerSpawnPublisherRadius());
        readyPublisher.getSavedChunks().addAll(initialForwardedLevelChunks);
        connection.client().sendPacket(readyPublisher);

        long runtimeEntityId = connection.clientPlayerRuntimeEntityId();
        if (runtimeEntityId > 0) {
            EntityEventPacket respawnEvent = new EntityEventPacket();
            respawnEvent.setRuntimeEntityId(runtimeEntityId);
            respawnEvent.setType(EntityEventType.RESPAWN);
            respawnEvent.setData(0);
            connection.client().sendPacket(respawnEvent);
        }

        SetHealthPacket health = new SetHealthPacket();
        health.setHealth(20);
        connection.client().sendPacket(health);

        if (includePlayerSpawn) {
            PlayStatusPacket playerSpawn = new PlayStatusPacket();
            playerSpawn.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
            connection.client().sendPacket(playerSpawn);
            sendInitialCrossProtocolReappearedSound();
        }

        return new InitialCrossProtocolSpawnPrelude(
                runtimeEntityId,
                readyPublisher.getRadius(),
                readyPublisher.getSavedChunks().size()
        );
    }

    private void sendInitialCrossProtocolReappearedSound() {
        LevelSoundEventPacket sound = new LevelSoundEventPacket();
        sound.setSound(SoundEvent.REAPPEARED);
        sound.setPosition(initialPlayerSpawnSoundPosition());
        sound.setExtraData(-1);
        sound.setIdentifier("minecraft:player");
        sound.setBabySound(false);
        sound.setRelativeVolumeDisabled(false);
        sound.setEntityUniqueId(-1L);
        connection.client().sendPacket(sound);
    }

    private record InitialCrossProtocolSpawnPrelude(long runtimeEntityId, int publisherRadius, int savedChunks) {
    }

    private synchronized void sendInitialCrossProtocolServerReady() {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null
                || !connection.client().isConnected()
                || !connection.markInitialSyntheticServerReadySent()) {
            return;
        }

        Vector3f readyPosition = connection.saneJoinPosition();
        if (readyPosition == null || !isSaneJoinY(readyPosition.getY())) {
            readyPosition = initialSyntheticServerReadyPosition;
        }
        if (readyPosition == null || !isSaneJoinY(readyPosition.getY())) {
            readyPosition = Vector3f.from(0.5f, 72.0f, 0.5f);
        }

        RespawnPacket ready = new RespawnPacket();
        ready.setState(RespawnPacket.State.SERVER_READY);
        ready.setPosition(readyPosition);
        ready.setRuntimeEntityId(0);
        connection.client().sendPacket(ready);
        NetworkChunkPublisherUpdatePacket publisher = new NetworkChunkPublisherUpdatePacket();
        publisher.setPosition(initialPlayerSpawnPublisherPosition());
        publisher.setRadius(initialCrossProtocolChunkCachePublisherRadius());
        connection.client().sendPacket(publisher);
        System.out.printf(
                "Synthesized initial cross-protocol SERVER_READY respawn for client %s from backend %s: position=%s publisherPosition=%s publisherRadius=%d.%n",
                connection.client().getSocketAddress(),
                backendName,
                ready.getPosition(),
                publisher.getPosition(),
                publisher.getRadius()
        );
    }

    private Vector3i initialPlayerSpawnPublisherPosition() {
        Vector3f position = connection.saneJoinPosition();
        if (position == null || !isSaneJoinY(position.getY())) {
            position = initialSyntheticServerReadyPosition;
        }
        if (position == null || !isSaneJoinY(position.getY())) {
            position = Vector3f.from(0.5f, 72.0f, 0.5f);
        }
        return Vector3i.from(
                Math.round(position.getX()),
                Math.round(position.getY()),
                Math.round(position.getZ())
        );
    }

    private Vector3f initialPlayerSpawnSoundPosition() {
        Vector3f position = connection.saneJoinPosition();
        if (position == null || !isSaneJoinY(position.getY())) {
            position = initialSyntheticServerReadyPosition;
        }
        if (position == null || !isSaneJoinY(position.getY())) {
            position = Vector3f.from(0.5f, 72.0f, 0.5f);
        }
        return Vector3f.from(position.getX(), position.getY() - 0.72f, position.getZ());
    }

    private int initialPlayerSpawnPublisherRadius() {
        return INITIAL_CROSS_PROTOCOL_PUBLISHER_RADIUS_BLOCKS;
    }

    private int initialCrossProtocolChunkCachePublisherRadius() {
        int requestedRadius = !SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL
                && connection.lastRequestedMaxChunkRadius() > 0
                ? connection.lastRequestedMaxChunkRadius()
                : connection.lastRequestedChunkRadius();
        int requestedRadiusBlocks = requestedRadius > 0 ? requestedRadius * 16 : 0;
        return Math.max(INITIAL_CROSS_PROTOCOL_PUBLISHER_RADIUS_BLOCKS, requestedRadiusBlocks);
    }

    private void sendInitialCrossProtocolBackendRespawnReady() {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !connection.hasInitialLoadingScreenStarted()
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null
                || !connection.hasInitialServerSearchingSeen()
                || !connection.markInitialBackendRespawnReadySent()) {
            return;
        }

        Vector3f position = connection.initialServerSearchingPosition();
        if (position == null || !isSaneJoinY(position.getY())) {
            position = connection.saneJoinPosition();
        }
        if (position == null || !isSaneJoinY(position.getY())) {
            position = Vector3f.from(0.5f, 72.0f, 0.5f);
        }

        RespawnPacket ready = new RespawnPacket();
        ready.setState(RespawnPacket.State.CLIENT_READY);
        ready.setPosition(position);
        ready.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
        backend.sendPacket(ready);
        System.out.printf(
                "Acknowledged initial cross-protocol backend respawn after SERVER_SEARCHING from %s: runtimeEntityId=%d position=%s.%n",
                backendName,
                ready.getRuntimeEntityId(),
                ready.getPosition()
        );
    }

    private Vector3f joinReadyPosition(Vector3f position) {
        Vector3f readyPosition = position;
        if (readyPosition == null || !isSaneJoinY(readyPosition.getY())) {
            readyPosition = saneJoinPosition;
        }
        if (readyPosition == null || !isSaneJoinY(readyPosition.getY())) {
            readyPosition = Vector3f.from(0.5f, 72.0f, 0.5f);
        }
        return readyPosition;
    }

    private void requestMissingChunkBlobs(LevelChunkPacket chunk) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !chunk.isCachingEnabled()
                || chunk.getBlobIds().isEmpty()
                || connection.backendSwitchReset() != null) {
            return;
        }

        ClientCacheBlobStatusPacket missing = new ClientCacheBlobStatusPacket();
        for (int i = 0; i < chunk.getBlobIds().size(); i++) {
            long blobId = chunk.getBlobIds().getLong(i);
            if (requestedMissingChunkBlobs.add(blobId)) {
                missing.getNaks().add(blobId);
            }
        }
        if (missing.getNaks().isEmpty()) {
            return;
        }

        backend.sendPacketImmediately(missing);
        System.out.printf(
                "Requested %d missing cached chunk blob(s) from backend %s after LevelChunk (%d,%d) dimension=%d firstNaks=%s totalRequested=%d.%n",
                missing.getNaks().size(),
                backendName,
                chunk.getChunkX(),
                chunk.getChunkZ(),
                chunk.getDimension(),
                missing.getNaks().longStream().limit(8).boxed().toList(),
                requestedMissingChunkBlobs.size()
        );
    }

    private boolean holdCachedLevelChunk(LevelChunkPacket chunk, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || !chunk.isCachingEnabled()
                || chunk.getBlobIds().size() != 1
                || connection.backendSwitchReset() != null) {
            return false;
        }

        long blobId = chunk.getBlobIds().getLong(0);
        byte[] cachedBlob = cachedChunkBlobs.get(blobId);
        if (cachedBlob != null) {
            sendBlobBackedLevelChunk(chunk, blobId, cachedBlob, traceSequence, 0);
            return true;
        }

        pendingCachedChunks.computeIfAbsent(blobId, ignored -> new ArrayList<>())
                .add(PendingCachedChunk.from(chunk, traceSequence, connection.elapsedMillis()));
        requestMissingChunkBlob(chunk, blobId);
        System.out.printf(
                "Holding cached LevelChunk from backend %s until blob arrives: chunk=(%d,%d) dimension=%d blobId=%d pendingForBlob=%d.%n",
                backendName,
                chunk.getChunkX(),
                chunk.getChunkZ(),
                chunk.getDimension(),
                blobId,
                pendingCachedChunks.get(blobId).size()
        );
        return true;
    }

    private void requestMissingChunkBlob(LevelChunkPacket chunk, long blobId) {
        if (!requestedMissingChunkBlobs.add(blobId)) {
            return;
        }

        ClientCacheBlobStatusPacket missing = new ClientCacheBlobStatusPacket();
        missing.getNaks().add(blobId);
        backend.sendPacket(missing);
        System.out.printf(
                "Requested missing cached chunk blob from backend %s before forwarding LevelChunk (%d,%d) dimension=%d blobId=%d totalRequested=%d.%n",
                backendName,
                chunk.getChunkX(),
                chunk.getChunkZ(),
                chunk.getDimension(),
                blobId,
                requestedMissingChunkBlobs.size()
        );
    }

    private boolean handleInternalChunkBlobResponse(ClientCacheMissResponsePacket missResponse, long traceSequence) {
        if (!backendNeedsLegacyJoinWorkarounds()) {
            return false;
        }
        if (missResponse.getBlobs().isEmpty()) {
            if (!requestedMissingChunkBlobs.isEmpty() || !acknowledgedChunkBlobs.isEmpty()) {
                System.out.printf(
                        "Consumed empty internal chunk blob response from backend %s after proxy cache status: trace=%d requested=%d acked=%d.%n",
                        backendName,
                        traceSequence,
                        requestedMissingChunkBlobs.size(),
                        acknowledgedChunkBlobs.size()
                );
                return true;
            }
            return false;
        }
        if (pendingCachedChunks.isEmpty() && requestedMissingChunkBlobs.isEmpty()) {
            return false;
        }

        boolean handledAny = false;
        boolean allInternal = true;
        for (var entry : missResponse.getBlobs().long2ObjectEntrySet()) {
            long blobId = entry.getLongKey();
            if (!requestedMissingChunkBlobs.contains(blobId)) {
                allInternal = false;
                continue;
            }

            byte[] blob = copyReadableBytes(entry.getValue());
            cachedChunkBlobs.put(blobId, blob);
            List<PendingCachedChunk> pending = pendingCachedChunks.remove(blobId);
            int flushed = 0;
            if (pending != null) {
                for (PendingCachedChunk chunk : pending) {
                    sendBlobBackedLevelChunk(chunk, blobId, blob);
                    flushed++;
                }
            }
            handledAny = true;
            System.out.printf(
                    "Resolved cached chunk blob from backend %s: blobId=%d bytes=%d flushedChunks=%d trace=%d remainingPendingBlobs=%d.%n",
                    backendName,
                    blobId,
                    blob.length,
                    flushed,
                    traceSequence,
                    pendingCachedChunks.size()
            );
        }
        return handledAny && allInternal;
    }

    private void sendBlobBackedLevelChunk(LevelChunkPacket source, long blobId, byte[] blob, long traceSequence, long heldMillis) {
        PendingCachedChunk pending = PendingCachedChunk.from(source, traceSequence, connection.elapsedMillis() - heldMillis);
        sendBlobBackedLevelChunk(pending, blobId, blob);
    }

    private void sendBlobBackedLevelChunk(PendingCachedChunk pending, long blobId, byte[] blob) {
        LevelChunkPacket fullChunk = new LevelChunkPacket();
        fullChunk.setChunkX(pending.chunkX());
        fullChunk.setChunkZ(pending.chunkZ());
        fullChunk.setDimension(pending.dimension());
        fullChunk.setSubChunksLength(pending.subChunksLength());
        fullChunk.setRequestSubChunks(pending.requestSubChunks());
        fullChunk.setSubChunkLimit(pending.subChunkLimit());
        fullChunk.setCachingEnabled(false);
        byte[] tailData = pending.data();
        byte[] fullData = blob;
        if (tailData.length > 0) {
            fullData = new byte[blob.length + tailData.length];
            System.arraycopy(blob, 0, fullData, 0, blob.length);
            System.arraycopy(tailData, 0, fullData, blob.length, tailData.length);
        }
        fullChunk.setData(Unpooled.wrappedBuffer(fullData));
        boolean buffered = bufferInitialClientboundUntilLoadingStart(fullChunk, fullChunk, pending.traceSequence());
        if (!buffered) {
            sendTranslatedClientbound(fullChunk, LevelChunkPacket.class.getSimpleName(), pending.traceSequence(), false);
            markInitialLevelChunkForwarded(fullChunk);
        }
        acknowledgeResolvedChunkBlob(blobId);
        System.out.printf(
                "%s blob-backed LevelChunk to client from backend %s: originalTrace=%d chunk=(%d,%d) dimension=%d blobId=%d blobBytes=%d tailBytes=%d fullBytes=%d held=%dms clientConnected=%s.%n",
                buffered ? "Buffered" : "Forwarded",
                backendName,
                pending.traceSequence(),
                pending.chunkX(),
                pending.chunkZ(),
                pending.dimension(),
                blobId,
                blob.length,
                tailData.length,
                fullData.length,
                Math.max(0, connection.elapsedMillis() - pending.enqueuedAtMillis()),
                connection.client().isConnected()
        );
    }

    private void acknowledgeResolvedChunkBlob(long blobId) {
        if (!acknowledgedChunkBlobs.add(blobId)) {
            return;
        }

        ClientCacheBlobStatusPacket ack = new ClientCacheBlobStatusPacket();
        ack.getAcks().add(blobId);
        backend.sendPacketImmediately(ack);
        System.out.printf(
                "Acknowledged resolved cached chunk blob to backend %s: blobId=%d totalAcked=%d.%n",
                backendName,
                blobId,
                acknowledgedChunkBlobs.size()
        );
    }

    private static byte[] copyReadableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static long xxHash64(byte[] data, long seed) {
        final long prime1 = -7046029288634856825L;
        final long prime2 = -4417276706812531889L;
        final long prime3 = 1609587929392839161L;
        final long prime4 = -8796714831421723037L;
        final long prime5 = 2870177450012600261L;

        int index = 0;
        int length = data.length;
        long hash;
        if (length >= 32) {
            long v1 = seed + prime1 + prime2;
            long v2 = seed + prime2;
            long v3 = seed;
            long v4 = seed - prime1;
            int limit = length - 32;
            do {
                v1 = xxHash64Round(v1, readLongLE(data, index));
                index += 8;
                v2 = xxHash64Round(v2, readLongLE(data, index));
                index += 8;
                v3 = xxHash64Round(v3, readLongLE(data, index));
                index += 8;
                v4 = xxHash64Round(v4, readLongLE(data, index));
                index += 8;
            } while (index <= limit);

            hash = Long.rotateLeft(v1, 1)
                    + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);
            hash = xxHash64MergeRound(hash, v1);
            hash = xxHash64MergeRound(hash, v2);
            hash = xxHash64MergeRound(hash, v3);
            hash = xxHash64MergeRound(hash, v4);
        } else {
            hash = seed + prime5;
        }

        hash += length;
        while (index <= length - 8) {
            long k1 = xxHash64Round(0, readLongLE(data, index));
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * prime1 + prime4;
            index += 8;
        }
        if (index <= length - 4) {
            hash ^= (readIntLE(data, index) & 0xffffffffL) * prime1;
            hash = Long.rotateLeft(hash, 23) * prime2 + prime3;
            index += 4;
        }
        while (index < length) {
            hash ^= (data[index] & 0xffL) * prime5;
            hash = Long.rotateLeft(hash, 11) * prime1;
            index++;
        }

        hash ^= hash >>> 33;
        hash *= prime2;
        hash ^= hash >>> 29;
        hash *= prime3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long xxHash64Round(long accumulator, long input) {
        final long prime1 = -7046029288634856825L;
        final long prime2 = -4417276706812531889L;
        accumulator += input * prime2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * prime1;
    }

    private static long xxHash64MergeRound(long accumulator, long value) {
        final long prime1 = -7046029288634856825L;
        final long prime4 = -8796714831421723037L;
        accumulator ^= xxHash64Round(0, value);
        return accumulator * prime1 + prime4;
    }

    private static long readLongLE(byte[] data, int index) {
        return (data[index] & 0xffL)
                | ((data[index + 1] & 0xffL) << 8)
                | ((data[index + 2] & 0xffL) << 16)
                | ((data[index + 3] & 0xffL) << 24)
                | ((data[index + 4] & 0xffL) << 32)
                | ((data[index + 5] & 0xffL) << 40)
                | ((data[index + 6] & 0xffL) << 48)
                | ((data[index + 7] & 0xffL) << 56);
    }

    private static int readIntLE(byte[] data, int index) {
        return (data[index] & 0xff)
                | ((data[index + 1] & 0xff) << 8)
                | ((data[index + 2] & 0xff) << 16)
                | ((data[index + 3] & 0xff) << 24);
    }

    private void requestBackendSubChunks(LevelChunkPacket chunk) {
        if (!backendNeedsLegacyJoinWorkarounds() || !chunk.isRequestSubChunks()) {
            return;
        }

        int minY = minimumSubChunkY(chunk.getDimension());
        int maxY = chunk.getSubChunkLimit() < 0 ? minY + 23 : minY + chunk.getSubChunkLimit();
        if (maxY < minY) {
            return;
        }

        String key = chunk.getDimension() + ":" + chunk.getChunkX() + ":" + chunk.getChunkZ() + ":" + minY + ":" + maxY;
        if (!requestedSkeletonChunks.add(key)) {
            return;
        }

        SubChunkRequestPacket request = new SubChunkRequestPacket();
        request.setDimension(chunk.getDimension());
        request.setSubChunkPosition(Vector3i.from(chunk.getChunkX(), minY, chunk.getChunkZ()));
        for (int y = minY; y <= maxY; y++) {
            request.getPositionOffsets().add(Vector3i.from(0, y - minY, 0));
        }
        backend.sendPacket(request);
        System.out.printf(
                "Requested backend subchunks for skeleton LevelChunk from %s at +%dms: chunk=(%d,%d) dimension=%d center=%s y=%d..%d requests=%d backendConnected=%s.%n",
                backendName,
                connection.elapsedMillis(),
                chunk.getChunkX(),
                chunk.getChunkZ(),
                chunk.getDimension(),
                request.getSubChunkPosition(),
                minY,
                maxY,
                request.getPositionOffsets().size(),
                backend.isConnected()
        );
    }

    private static int minimumSubChunkY(int dimension) {
        return dimension == 0 ? -4 : 0;
    }

    private void syncDefinitionState(BedrockPacket packet) {
        if (packet instanceof StartGamePacket startGame) {
            if (backend == connection.pendingBackend()) {
                activation.onStartGame(backend);
            }
            warnIfBackendVerificationMissing();
            long backendRuntimeEntityId = startGame.getRuntimeEntityId();
            connection.setBackendPlayerRuntimeEntityId(backendRuntimeEntityId);
            long clientRuntimeEntityId = connection.clientPlayerRuntimeEntityId();
            if (clientRuntimeEntityId > 0 && clientRuntimeEntityId != backendRuntimeEntityId) {
                startGame.setRuntimeEntityId(clientRuntimeEntityId);
            }
            long backendUniqueEntityId = startGame.getUniqueEntityId();
            connection.setBackendPlayerUniqueEntityId(backendUniqueEntityId);
            long clientUniqueEntityId = connection.clientPlayerUniqueEntityId();
            if (clientUniqueEntityId != backendUniqueEntityId) {
                startGame.setUniqueEntityId(clientUniqueEntityId);
            }
            connection.setPlayerDimensionId(startGame.getDimensionId());
            if (!connection.crossBackendPalette().isEnabled()) {
                // With the cross-backend palette on, item definitions are owned by
                // handleCrossBackendPalette: one shared registry would undo the per-backend mapping.
                CodecDefinitionState.syncFromStartGame(backend, connection.client(), startGame);
            }
            connection.tracePacketsForMillis(PACKET_TRACE_MILLIS);
            if (PACKET_TRACE_MILLIS > 0) {
                System.out.printf(
                        "Enabled detailed packet trace for %s for %dms after StartGame at +%dms.%n",
                        connection.client().getSocketAddress(),
                        PACKET_TRACE_MILLIS,
                        connection.elapsedMillis()
                );
            }
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                    "StartGame from backend %s: dimension=%d backendRuntimeEntityId=%d clientRuntimeEntityId=%d backendUniqueEntityId=%d clientUniqueEntityId=%d playerGameType=%s levelGameType=%s playerPosition=%s defaultSpawn=%s commandsEnabled=%s defaultPermission=%s blockRegistryChecksum=%d blockNetworkIdsHashed=%s.%n",
                    backendName,
                    startGame.getDimensionId(),
                    backendRuntimeEntityId,
                    startGame.getRuntimeEntityId(),
                    backendUniqueEntityId,
                    startGame.getUniqueEntityId(),
                    startGame.getPlayerGameType(),
                    startGame.getLevelGameType(),
                    startGame.getPlayerPosition(),
                    startGame.getDefaultSpawn(),
                    startGame.isCommandsEnabled(),
                    startGame.getDefaultPlayerPermission(),
                    startGame.getBlockRegistryChecksum(),
                    startGame.isBlockNetworkIdsHashed()
                );
            }
            saneJoinPosition = saneJoinPosition(startGame);
            connection.setSaneJoinPosition(saneJoinPosition);
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                    "StartGame details from backend %s: vanillaVersion=%s serverEngine=%s levelName=%s blockProperties=%d itemDefinitions=%d clientSideGeneration=%s inventoriesServerAuth=%s rewindHistory=%d serverAuthBlockBreaking=%s serverJoinInfo=%s networkPermissions=%s tickDeathSystems=%s.%n",
                    backendName,
                    startGame.getVanillaVersion(),
                    startGame.getServerEngine(),
                    startGame.getLevelName(),
                    startGame.getBlockProperties().size(),
                    startGame.getItemDefinitions().size(),
                    startGame.isClientSideGenerationEnabled(),
                    startGame.isInventoriesServerAuthoritative(),
                    startGame.getRewindHistorySize(),
                    startGame.isServerAuthoritativeBlockBreaking(),
                    startGame.getServerConfigurationJoinInfo() != null,
                    startGame.getNetworkPermissions(),
                    startGame.isTickDeathSystemsEnabled()
                );
            }
            if (isCrossProtocol()) {
                normalizeInitialCrossProtocolStartGame(startGame);
            }
            StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);
            if (fixups.forcedTickDeathSystems()) {
                System.out.printf(
                        "Forced tickDeathSystems=true for backend %s; the backend reported false, "
                                + "which makes the client disconnect on death.%n",
                        backendName
                );
            }
            if (fixups.enabledCommands()) {
                System.out.printf("Enabled client-side commands for backend %s.%n", backendName);
            }
        } else if (packet instanceof ItemComponentPacket itemComponent) {
            if (!connection.crossBackendPalette().isEnabled()) {
                CodecDefinitionState.syncFromItemComponents(backend, connection.client(), itemComponent);
            }
        } else if (packet instanceof CameraPresetsPacket cameraPresets) {
            CodecDefinitionState.syncFromCameraPresets(backend, connection.client(), cameraPresets);
        } else if (packet instanceof ChangeDimensionPacket changeDimension) {
            connection.setPlayerDimensionId(changeDimension.getDimension());
        } else if (packet instanceof SetCommandsEnabledPacket commandsEnabled) {
            if (!commandsEnabled.isCommandsEnabled()) {
                commandsEnabled.setCommandsEnabled(true);
                System.out.printf("Overrode SetCommandsEnabled=false from backend %s.%n", backendName);
            }
        } else if (packet instanceof UpdateAbilitiesPacket abilities) {
            BackendPermissionSync.apply(abilities);
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                    "UpdateAbilities from backend %s: playerPermission=%s commandPermission=%s layers=%s.%n",
                    backendName,
                    abilities.getPlayerPermission(),
                    abilities.getCommandPermission(),
                    abilities.getAbilityLayers().stream()
                            .map(layer -> layer.getLayerType() + ":" + layer.getAbilityValues())
                            .collect(Collectors.joining(","))
                );
            }
            // The backend's individual ADMIN/HOST/OWNER command level corrects the MEMBER world
            // default above. Ordinary members and explicit visitor/custom permissions are untouched.
        }
    }

    /**
     * Keeps custom items and entities rendering correctly across a seamless backend switch.
     *
     * <p>Bedrock reads its item registry ({@code ItemComponentPacket}) and its entity identifier list
     * exactly once, at level init, and a switch deliberately does not re-run level init. So whatever
     * the client is told on its <em>first</em> backend is what it still believes on every later one:
     * a backend's custom item ids land in a registry that has no entry for them and draw arbitrary
     * vanilla textures, and its custom entities are missing from the identifier list and render as
     * nothing at all. That is the bug this method exists for, and no amount of serving the right
     * resource packs fixes it — the packs supply textures for identifiers the client never learned.</p>
     *
     * <p>At login the client is therefore given the union of every backend's registries, and from then
     * on each backend's own ids are translated to and from that union by
     * {@link org.endstone.proxy.palette.ItemPaletteMapping}. On a later backend these packets are
     * learned, used to rebuild the mapping, and dropped: resending them would tell the client
     * something it cannot act on.</p>
     *
     * @return true when the packet has been fully handled and must not be forwarded
     */
    private boolean handleCrossBackendPalette(BedrockPacket packet, long traceSequence) {
        CrossBackendPalette palette = connection.crossBackendPalette();
        if (packet instanceof StartGamePacket startGame) {
            // Applied on every StartGame, switches included: the union is a superset of what this
            // backend sent, so a client that acts on a later StartGame is no worse off for it.
            palette.applyToStartGame(backendName, startGame);
            return false;
        }
        if (packet instanceof ItemComponentPacket itemComponent) {
            List<ItemDefinition> backendItems = List.copyOf(itemComponent.getItems());
            if (backendItems.isEmpty()) {
                return false;
            }
            palette.store().learnItems(backendName, backendItems);
            boolean firstBackend = !palette.hasClientItems();
            if (firstBackend) {
                List<ItemDefinition> union = palette.buildClientItems(backendName, backendItems);
                itemComponent.getItems().clear();
                itemComponent.getItems().addAll(union);
            }
            installItemPaletteMapping(backendItems);
            if (firstBackend) {
                return false;
            }
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Suppressed clientbound #%d item registry from backend %s: the client's registry was "
                                + "fixed at login and now maps through the cross-backend palette.%n",
                        traceSequence, backendName
                );
            }
            return true;
        }
        if (packet instanceof AvailableEntityIdentifiersPacket entityIdentifiers) {
            palette.store().learnEntityIdentifiers(backendName, entityIdentifiers.getIdentifiers());
            if (palette.clientEntityIdentifiers() != null) {
                return true;
            }
            entityIdentifiers.setIdentifiers(
                    palette.buildClientEntityIdentifiers(backendName, entityIdentifiers.getIdentifiers()));
            sendForeignEntityProperties();
            return false;
        }
        if (packet instanceof SyncEntityPropertyPacket entityProperty) {
            palette.store().learnEntityProperty(backendName, entityProperty.getData());
            // One list per entity type is all the client keeps; a second backend's copy of the same
            // type is noise, and a type it has already been told about must not be re-sent.
            return !palette.markEntityPropertySent(entityProperty.getData());
        }
        return false;
    }

    /**
     * Sends the entity property lists belonging to backends this player has not visited, so their
     * entities behave correctly the moment they switch. Sent with the identifier list, which is the
     * last of the definition burst and still ahead of any entity spawn.
     */
    private void sendForeignEntityProperties() {
        List<NbtMap> pending = connection.crossBackendPalette().pendingEntityProperties(backendName);
        for (NbtMap property : pending) {
            SyncEntityPropertyPacket packet = new SyncEntityPropertyPacket();
            packet.setData(property);
            connection.client().sendPacket(packet);
        }
        if (!pending.isEmpty()
                && connection.crossBackendPalette().store()
                .firstReportOf("properties:" + backendName + ":" + pending.size())) {
            System.out.printf(
                    "Sent %d entity property list(s) from other backends to a client joining %s.%n",
                    pending.size(), backendName
            );
        }
    }

    private void installItemPaletteMapping(List<ItemDefinition> backendItems) {
        ItemPaletteMapping mapping = connection.crossBackendPalette().mappingFor(backendName, backendItems);
        if (mapping == null) {
            return;
        }
        CodecDefinitionState.installItemMapping(backend, connection.client(), mapping);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Installed item palette mapping for backend %s: %s.%n",
                    backendName,
                    mapping.isIdentity() ? "ids already agree" : "ids remapped to the client's registry"
            );
        }
    }

    private void warnIfBackendVerificationMissing() {
        if (pendingJoin == null) {
            return;
        }
        if (!pendingJoinRegistry.removeIfPending(pendingJoin)) {
            return;
        }
        System.out.printf(
                "WARNING: Backend %s sent StartGame for %s without consuming backend verification. The EndlinkGuard plugin is missing, disabled, or pointed at the wrong proxy; direct joins are not secured.%n",
                backendName,
                pendingJoin.name()
        );
    }

    private void normalizeInitialCrossProtocolStartGame(StartGamePacket startGame) {
        boolean changed = false;
        long originalBlockRegistryChecksum = startGame.getBlockRegistryChecksum();
        String clientMinecraftVersion = connection.sessionProfile().clientCodec().getMinecraftVersion();
        if (clientMinecraftVersion != null && !clientMinecraftVersion.equals(startGame.getServerEngine())) {
            startGame.setServerEngine(clientMinecraftVersion);
            changed = true;
        }
        if (!startGame.isClientSideGenerationEnabled()) {
            startGame.setClientSideGenerationEnabled(true);
            changed = true;
        }
        if (startGame.getServerConfigurationJoinInfo() == null) {
            startGame.setServerConfigurationJoinInfo(new ServerConfigurationJoinInfo(null, null, null));
            changed = true;
        }
        // tickDeathSystems is corrected unconditionally by the caller — it is a client-behaviour
        // workaround (the client disconnects on death when it is false), not a translation concern.
        if (connection.sessionProfile().clientCodec().getProtocolVersion()
                == CanonicalProtocol.V1_26_20.codec().getProtocolVersion()
                && startGame.isBlockNetworkIdsHashed()
                && startGame.getBlockProperties().isEmpty()
                && startGame.getBlockRegistryChecksum() != VANILLA_1_26_20_BLOCK_REGISTRY_CHECKSUM) {
            startGame.setBlockRegistryChecksum(VANILLA_1_26_20_BLOCK_REGISTRY_CHECKSUM);
            changed = true;
        }
        if (changed) {
            System.out.printf(
                    "Normalized StartGame for client protocol %d: serverEngine=%s clientSideGeneration=%s serverJoinInfo=%s blockRegistryChecksum=%d->%d.%n",
                    connection.sessionProfile().clientCodec().getProtocolVersion(),
                    startGame.getServerEngine(),
                    startGame.isClientSideGenerationEnabled(),
                    startGame.getServerConfigurationJoinInfo() != null,
                    originalBlockRegistryChecksum,
                    startGame.getBlockRegistryChecksum()
            );
        }
    }

    /**
     * Whether a backend-side runtime entity id is the player's own. Detail lines are printed before
     * {@code rewriteClientboundRuntimeIds}, so the id here is still the backend's; both sides are
     * checked because they are usually equal but need not be.
     */
    private String isClientOwnRuntimeEntityId(long backendRuntimeEntityId) {
        if (backendRuntimeEntityId <= 0) {
            return "false";
        }
        boolean own = backendRuntimeEntityId == connection.backendPlayerRuntimeEntityId()
                || backendRuntimeEntityId == connection.clientPlayerRuntimeEntityId();
        return own ? "SELF" : "false";
    }

    private void logClientboundDetails(BedrockPacket packet) {
        if (packet instanceof NetworkChunkPublisherUpdatePacket publisherUpdate) {
            System.out.printf(
                    "  NetworkChunkPublisherUpdate position=%s radius=%d savedChunks=%d playerDimension=%d requestedRadius=%d/%d.%n",
                    publisherUpdate.getPosition(),
                    publisherUpdate.getRadius(),
                    publisherUpdate.getSavedChunks().size(),
                    connection.playerDimensionId(),
                    connection.lastRequestedChunkRadius(),
                    connection.lastRequestedMaxChunkRadius()
            );
        } else if (packet instanceof ChunkRadiusUpdatedPacket radiusUpdated) {
            System.out.printf(
                    "  ChunkRadiusUpdated radius=%d rememberedRadius=%d/%d.%n",
                    radiusUpdated.getRadius(),
                    connection.lastRequestedChunkRadius(),
                    connection.lastRequestedMaxChunkRadius()
            );
        } else if (packet instanceof LevelChunkPacket chunk) {
            ByteBuf data = chunk.getData();
            System.out.printf(
                    "  LevelChunk x=%d z=%d dimension=%d subChunks=%d requestSubChunks=%s subChunkLimit=%d cache=%s blobs=%d dataBytes=%d firstBytes=%s.%n",
                    chunk.getChunkX(),
                    chunk.getChunkZ(),
                    chunk.getDimension(),
                    chunk.getSubChunksLength(),
                    chunk.isRequestSubChunks(),
                    chunk.getSubChunkLimit(),
                    chunk.isCachingEnabled(),
                    chunk.getBlobIds().size(),
                    data == null ? 0 : data.readableBytes(),
                    preview(data, 32)
            );
        } else if (packet instanceof SubChunkPacket subChunk) {
            System.out.printf(
                    "  SubChunk dimension=%d center=%s cache=%s entries=%d details=%s.%n",
                    subChunk.getDimension(),
                    subChunk.getCenterPosition(),
                    subChunk.isCacheEnabled(),
                    subChunk.getSubChunks().size(),
                    subChunk.getSubChunks().stream()
                            .limit(12)
                            .map(data -> data.getPosition()
                                    + ":" + data.getResult()
                                    + ":bytes=" + (data.getData() == null ? 0 : data.getData().readableBytes())
                                    + ":height=" + data.getHeightMapType()
                                    // renderHeightMapType is the only field of this packet that six
                                    // captures never printed, and therefore the only value on it that
                                    // has never been checked against 1.26.40's accepted range (the
                                    // r26_u4 dump allows 0-4 here but only 0-3 for the terrain
                                    // heightmap above). first= is the sub-chunk format version byte
                                    // and storage count, which is what says the payload is the
                                    // standard unchanged encoding.
                                    + ":render=" + data.getRenderHeightMapType()
                                    + ":first=" + preview(data.getData(), 4))
                            .collect(Collectors.joining(","))
            );
        } else if (packet instanceof ClientCacheMissResponsePacket missResponse) {
            System.out.printf(
                    "  ClientCacheMissResponse blobs=%d firstBlobIds=%s.%n",
                    missResponse.getBlobs().size(),
                    missResponse.getBlobs().keySet().longStream().limit(8).boxed().toList()
            );
        } else if (packet instanceof RespawnPacket respawn) {
            System.out.printf(
                    "  Respawn state=%s runtimeEntityId=%d position=%s.%n",
                    respawn.getState(),
                    respawn.getRuntimeEntityId(),
                    respawn.getPosition()
            );
        } else if (packet instanceof ItemStackResponsePacket stackResponse) {
            // The other half of the ItemStackRequest trace in ClientRelayPacketHandler. A rejection
            // names only the request id and a reason, so it is the request logged alongside that
            // says what was refused; a success is worth having too, because the stack network ids it
            // hands back are what the client has to quote in its next request.
            for (ItemStackResponse response : stackResponse.getEntries()) {
                StringBuilder line = new StringBuilder("  ItemStackResponse id=")
                        .append(response.getRequestId()).append(" result=").append(response.getResult());
                for (ItemStackResponseContainer container : response.getContainers()) {
                    for (ItemStackResponseSlot slot : container.getItems()) {
                        line.append("\n    ").append(container.getContainer())
                                .append('[').append(slot.getSlot()).append("] count=").append(slot.getCount())
                                .append(" netId=").append(slot.getStackNetworkId());
                    }
                }
                System.out.println(line + ".");
            }
        } else if (packet instanceof SetPlayerInventoryOptionsPacket inventoryOptions) {
            System.out.printf(
                    "  SetPlayerInventoryOptions left=%s right=%s filtering=%s layout=%s craftingLayout=%s.%n",
                    inventoryOptions.getLeftTab(),
                    inventoryOptions.getRightTab(),
                    inventoryOptions.isFiltering(),
                    inventoryOptions.getLayout(),
                    inventoryOptions.getCraftingLayout()
            );
        } else if (packet instanceof SetEntityDataPacket entityData) {
            System.out.printf(
                    "  SetEntityData runtimeEntityId=%d metadata=%d properties(int=%d float=%d) tick=%d.%n",
                    entityData.getRuntimeEntityId(),
                    entityData.getMetadata().size(),
                    entityData.getProperties().getIntProperties().size(),
                    entityData.getProperties().getFloatProperties().size(),
                    entityData.getTick()
            );
        } else if (packet instanceof MoveEntityDeltaPacket moveEntity) {
            // The two highest-volume packets on this hop had no detail line at all, which is why six
            // captures never showed which entity was moving. -Dproxy.neuterClientbound stripped their
            // content without changing the outcome, so the remaining suspects are the fields a neuter
            // must preserve to stay a neuter: the runtime entity id, and whether it is the player's
            // own (a server that keeps moving the local entity is fighting the client's prediction).
            System.out.printf(
                    "  MoveEntityDelta runtimeEntityId=%d self=%s flags=%s position=(%s,%s,%s) rotation=(%s,%s,%s) onGround=%s forceMove=%s forceMoveLocalEntity=%s forceCompletion=%s.%n",
                    moveEntity.getRuntimeEntityId(),
                    isClientOwnRuntimeEntityId(moveEntity.getRuntimeEntityId()),
                    moveEntity.getFlags(),
                    moveEntity.getX(),
                    moveEntity.getY(),
                    moveEntity.getZ(),
                    moveEntity.getPitch(),
                    moveEntity.getYaw(),
                    moveEntity.getHeadYaw(),
                    moveEntity.isOnGround(),
                    moveEntity.isForceMove(),
                    moveEntity.isForceMoveLocalEntity(),
                    moveEntity.isForceCompletion()
            );
        } else if (packet instanceof SetEntityMotionPacket entityMotion) {
            System.out.printf(
                    "  SetEntityMotion runtimeEntityId=%d self=%s motion=%s tick=%d.%n",
                    entityMotion.getRuntimeEntityId(),
                    isClientOwnRuntimeEntityId(entityMotion.getRuntimeEntityId()),
                    entityMotion.getMotion(),
                    entityMotion.getTick()
            );
        } else if (packet instanceof UpdateAttributesPacket attributes) {
            System.out.printf(
                    "  UpdateAttributes runtimeEntityId=%d attributes=%d tick=%d.%n",
                    attributes.getRuntimeEntityId(),
                    attributes.getAttributes().size(),
                    attributes.getTick()
            );
        }
    }

    private static String preview(ByteBuf data, int maxBytes) {
        if (data == null || data.readableBytes() <= 0) {
            return "";
        }
        int length = Math.min(maxBytes, data.readableBytes());
        return ByteBufUtil.hexDump(data, data.readerIndex(), length);
    }

    private boolean shouldDropCrossProtocolClientbound(BedrockPacket packet) {
        return switch (packet.getClass().getSimpleName()) {
            case "EditorNetworkPacket",
                 "DebugDrawerPacket" -> true;
            default -> false;
        };
    }

    private boolean isCrossProtocol() {
        return connection.sessionProfile().clientCodec().getProtocolVersion()
                != connection.sessionProfile().backendCodec().getProtocolVersion();
    }

    /**
     * Whether the backend predates the loading-screen death flow and needs the old
     * {@code RespawnPacket} handshake driven for it.
     *
     * <p><b>This is not the same question as "are the versions different", and conflating the two
     * broke death and backend switching.</b> The translation below was written for a 1.26.20 client
     * on a 1.21.130/944 backend, where the backend genuinely had no loading-screen death flow and the
     * proxy had to drive {@code CLIENT_READY} itself. It was gated on {@link #isCrossProtocol()},
     * which was an accurate proxy for "legacy backend" only while every cross-protocol pairing
     * happened to involve one.</p>
     *
     * <p>1.26.40 &rarr; 1.26.30 broke that assumption: the pairing is cross-protocol, but
     * {@code ServerboundLoadingScreenPacket} has existed since v712, so a 1001 backend speaks exactly
     * the same modern death flow as the 2168 client and needs no help at all. Driving the old
     * handshake there suppressed the {@code RespawnPacket} the client was waiting for and sent the
     * backend a {@code CLIENT_READY} it never asked for, so the client sat with no respawn and closed
     * the connection — and the same mistaken assumption made a backend switch wait for a
     * {@code SERVER_READY} that a modern backend never sends.</p>
     */
    private boolean backendUsesLegacyDeathRespawn() {
        return connection.sessionProfile().backendCodec().getProtocolVersion() < LOADING_SCREEN_PROTOCOL;
    }

    /**
     * First protocol with {@code ServerboundLoadingScreenPacket} ({@code Bedrock_v712}). At or above
     * this, client and backend drive the death respawn themselves.
     */
    private static final int LOADING_SCREEN_PROTOCOL = 712;

    /**
     * Backends at or above this need none of the join workarounds below.
     *
     * <p>1.26.30. Every one of those workarounds was written for a 1.26.20 client on an 898/944
     * backend, where the two sides genuinely disagreed about join geometry. A 1.26.30 backend is one
     * release below a 1.26.40 client and shares essentially all of it.</p>
     */
    private static final int MODERN_JOIN_PROTOCOL = 1001;

    /** {@code auto} (default), {@code always} or {@code never} — see {@link #backendNeedsLegacyJoinWorkarounds()}. */
    private static final String LEGACY_JOIN_WORKAROUNDS = System.getProperty("proxy.legacyJoinWorkarounds", "auto");

    /**
     * Whether this backend needs the join workarounds that follow — position, chunk-publisher and
     * spawn-ordering rewrites originally written for 898/944 backends.
     *
     * <p><b>Gating these on {@link #isCrossProtocol()} made them fire on 1.26.40 &rarr; 1.26.30, where
     * they do harm rather than good.</b> A single session against a 1.26.30 backend logged 289
     * chunk-publisher radius rewrites (the backend published 128 blocks, the proxy told the client
     * 448) and 192 publisher-Y rewrites, plus StartGame and respawn Y rewrites. The client was
     * repeatedly told chunks existed where the server had published none, which is the reported
     * broken chunk loading, and the sessions ended in the client closing the connection while
     * moving.</p>
     *
     * <p>Same mistake as the death-respawn gate above: "the versions differ" was standing in for
     * "the backend is from the era these hacks were written for", and that stopped being true the
     * moment two modern versions were paired. Override with
     * {@code -Dproxy.legacyJoinWorkarounds=always} to restore the old behaviour for a bisect, or
     * {@code never} to disable it for every backend.</p>
     */
    private boolean backendNeedsLegacyJoinWorkarounds() {
        if ("always".equalsIgnoreCase(LEGACY_JOIN_WORKAROUNDS)) {
            return isCrossProtocol();
        }
        if ("never".equalsIgnoreCase(LEGACY_JOIN_WORKAROUNDS)) {
            return false;
        }
        return isCrossProtocol()
                && connection.sessionProfile().backendCodec().getProtocolVersion() < MODERN_JOIN_PROTOCOL;
    }

    private BedrockPacket rewriteClientboundRuntimeIds(BedrockPacket packet) {
        if (packet instanceof StartGamePacket startGame) {
            normalizeJoinStartGamePosition(startGame);
            neutralizeCrossProtocolBlockPalette(startGame);
            return packet;
        }
        if (packet instanceof RespawnPacket respawn) {
            respawn.setRuntimeEntityId(toClientRuntime(respawn.getRuntimeEntityId(), false));
            normalizeJoinRespawnPosition(respawn);
        } else if (packet instanceof MovePlayerPacket movePlayer) {
            movePlayer.setRuntimeEntityId(toClientRuntime(movePlayer.getRuntimeEntityId(), false));
            movePlayer.setRidingRuntimeEntityId(toClientRuntime(movePlayer.getRidingRuntimeEntityId(), false));
        } else if (packet instanceof MoveEntityAbsolutePacket moveEntity) {
            moveEntity.setRuntimeEntityId(toClientRuntime(moveEntity.getRuntimeEntityId(), false));
        } else if (packet instanceof MoveEntityDeltaPacket moveEntity) {
            moveEntity.setRuntimeEntityId(toClientRuntime(moveEntity.getRuntimeEntityId(), false));
        } else if (packet instanceof SetEntityDataPacket entityData) {
            entityData.setRuntimeEntityId(toClientRuntime(entityData.getRuntimeEntityId(), false));
        } else if (packet instanceof SetEntityMotionPacket entityMotion) {
            entityMotion.setRuntimeEntityId(toClientRuntime(entityMotion.getRuntimeEntityId(), false));
        } else if (packet instanceof UpdateBlockSyncedPacket blockSynced) {
            blockSynced.setRuntimeEntityId(toClientRuntime(blockSynced.getRuntimeEntityId(), false));
        } else if (packet instanceof UpdateAttributesPacket attributes) {
            attributes.setRuntimeEntityId(toClientRuntime(attributes.getRuntimeEntityId(), false));
        } else if (packet instanceof EntityEventPacket entityEvent) {
            entityEvent.setRuntimeEntityId(toClientRuntime(entityEvent.getRuntimeEntityId(), false));
        } else if (packet instanceof EntityFallPacket entityFall) {
            entityFall.setRuntimeEntityId(toClientRuntime(entityFall.getRuntimeEntityId(), false));
        } else if (packet instanceof AnimatePacket animate) {
            animate.setRuntimeEntityId(toClientRuntime(animate.getRuntimeEntityId(), false));
        } else if (packet instanceof MovementEffectPacket movementEffect) {
            movementEffect.setEntityRuntimeId(toClientRuntime(movementEffect.getEntityRuntimeId(), false));
        } else if (packet instanceof MovementPredictionSyncPacket movementPrediction) {
            movementPrediction.setRuntimeEntityId(toClientRuntime(movementPrediction.getRuntimeEntityId(), false));
        } else if (packet instanceof TakeItemEntityPacket takeItem) {
            takeItem.setRuntimeEntityId(toClientRuntime(takeItem.getRuntimeEntityId(), false));
            takeItem.setItemRuntimeEntityId(toClientRuntime(takeItem.getItemRuntimeEntityId(), false));
        } else if (packet instanceof SetEntityLinkPacket linkPacket && linkPacket.getEntityLink() != null) {
            linkPacket.setEntityLink(rewriteLink(linkPacket.getEntityLink()));
        } else if (packet instanceof AddEntityPacket addEntity) {
            addEntity.setRuntimeEntityId(toClientRuntime(addEntity.getRuntimeEntityId(), true));
            addEntity.setEntityLinks(rewriteLinks(addEntity.getEntityLinks()));
        } else if (packet instanceof AddItemEntityPacket addItem) {
            addItem.setRuntimeEntityId(toClientRuntime(addItem.getRuntimeEntityId(), true));
        } else if (packet instanceof AddPlayerPacket addPlayer) {
            addPlayer.setRuntimeEntityId(toClientRuntime(addPlayer.getRuntimeEntityId(), true));
            addPlayer.setUniqueEntityId(toClientUnique(addPlayer.getUniqueEntityId()));
            addPlayer.setEntityLinks(rewriteLinks(addPlayer.getEntityLinks()));
        } else if (packet instanceof AddHangingEntityPacket addHanging) {
            addHanging.setRuntimeEntityId(toClientRuntime(addHanging.getRuntimeEntityId(), true));
        } else if (packet instanceof UpdatePlayerGameTypePacket updateGameType) {
            updateGameType.setEntityId(traceUniqueRewrite("UpdatePlayerGameType", updateGameType.getEntityId()));
        } else if (packet instanceof UpdateAbilitiesPacket abilities) {
            abilities.setUniqueEntityId(traceUniqueRewrite("UpdateAbilities", abilities.getUniqueEntityId()));
        } else if (packet instanceof PlayerListPacket playerList) {
            // Entry.entityId is the player's *unique* id. The local player's own entry has to be
            // remapped like any other local-player id packet, or the client binds its skin and
            // nametag to an id it does not recognise after a backend switch.
            for (PlayerListPacket.Entry entry : playerList.getEntries()) {
                entry.setEntityId(toClientUnique(entry.getEntityId()));
            }
        } else if (packet instanceof NetworkChunkPublisherUpdatePacket publisherUpdate) {
            normalizeJoinPublisherPosition(publisherUpdate);
            normalizeJoinPublisherRadius(publisherUpdate);
        } else if (packet instanceof ChunkRadiusUpdatedPacket radiusUpdated) {
            normalizeJoinChunkRadiusUpdated(radiusUpdated);
        }
        return packet;
    }

    private Vector3f saneJoinPosition(StartGamePacket startGame) {
        Vector3f playerPosition = startGame.getPlayerPosition();
        if (isSaneJoinY(playerPosition == null ? Float.NaN : playerPosition.getY())) {
            return playerPosition;
        }
        Vector3i defaultSpawn = startGame.getDefaultSpawn();
        if (defaultSpawn != null && isSaneJoinY(defaultSpawn.getY())) {
            return Vector3f.from(defaultSpawn.getX() + 0.5f, defaultSpawn.getY(), defaultSpawn.getZ() + 0.5f);
        }
        if (playerPosition != null) {
            return Vector3f.from(playerPosition.getX(), 72.0f, playerPosition.getZ());
        }
        if (defaultSpawn != null) {
            return Vector3f.from(defaultSpawn.getX() + 0.5f, 72.0f, defaultSpawn.getZ() + 0.5f);
        }
        return Vector3f.from(0.5f, 72.0f, 0.5f);
    }

    private void normalizeJoinStartGamePosition(StartGamePacket startGame) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || startGame.getPlayerPosition() == null
                || isSaneJoinY(startGame.getPlayerPosition().getY())) {
            return;
        }
        Vector3f original = startGame.getPlayerPosition();
        float y = saneJoinPosition == null ? 72.0f : saneJoinPosition.getY();
        startGame.setPlayerPosition(Vector3f.from(original.getX(), y, original.getZ()));
        System.out.printf(
                "Normalized cross-protocol StartGame player Y from %.2f to %.2f for backend %s at position=%s.%n",
                original.getY(),
                y,
                backendName,
                startGame.getPlayerPosition()
        );
    }

    /** See {@link CrossProtocolStartGameFixups}; this only reports what it did. */
    private void neutralizeCrossProtocolBlockPalette(StartGamePacket startGame) {
        CrossProtocolStartGameFixups fixups = CrossProtocolStartGameFixups.apply(startGame, isCrossProtocol());

        if (fixups.clearedBlockRegistryChecksum() != 0L) {
            System.out.printf(
                    "Cleared cross-protocol StartGame block registry checksum %d for backend %s "
                            + "(client %d, backend %d); the client cannot match a palette from another version.%n",
                    fixups.clearedBlockRegistryChecksum(),
                    backendName,
                    connection.sessionProfile().clientCodec().getProtocolVersion(),
                    connection.sessionProfile().backendCodec().getProtocolVersion()
            );
        }
        if (fixups.indexBasedBlockIds()) {
            System.out.printf(
                    "WARNING: backend %s sends index-based block network ids (blockNetworkIdsHashed=false) "
                            + "on a cross-protocol join. Block ids are palette indices and will not match the "
                            + "client's palette; expect wrong or missing blocks.%n",
                    backendName
            );
        }
    }

    private void normalizeJoinRespawnPosition(RespawnPacket respawn) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || respawn.getState() != RespawnPacket.State.SERVER_SEARCHING
                || respawn.getPosition() == null
                || isSaneJoinY(respawn.getPosition().getY())) {
            return;
        }
        Vector3f original = respawn.getPosition();
        float y = saneJoinPosition == null ? 72.0f : saneJoinPosition.getY();
        respawn.setPosition(Vector3f.from(original.getX(), y, original.getZ()));
        System.out.printf(
                "Normalized cross-protocol SERVER_SEARCHING respawn Y from %.2f to %.2f for backend %s at position=%s.%n",
                original.getY(),
                y,
                backendName,
                respawn.getPosition()
        );
    }

    private void normalizeJoinPublisherPosition(NetworkChunkPublisherUpdatePacket publisherUpdate) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || publisherUpdate.getPosition() == null
                || isSaneJoinY(publisherUpdate.getPosition().getY())) {
            return;
        }
        Vector3i original = publisherUpdate.getPosition();
        int y = saneJoinPosition == null ? 72 : Math.round(saneJoinPosition.getY());
        publisherUpdate.setPosition(Vector3i.from(original.getX(), y, original.getZ()));
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Normalized cross-protocol chunk publisher Y from %d to %d for backend %s at position=%s.%n",
                    original.getY(),
                    y,
                    backendName,
                    publisherUpdate.getPosition()
            );
        }
    }

    private void normalizeJoinPublisherRadius(NetworkChunkPublisherUpdatePacket publisherUpdate) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.hasInitialClientChunkCacheStatusSeen()
                || connection.hasSentInitialSyntheticPlayerSpawn()) {
            return;
        }
        int targetRadius = initialCrossProtocolChunkCachePublisherRadius();
        if (SYNTHETIC_CLIENT_CHUNK_CACHE_FOR_CROSS_PROTOCOL) {
            if (publisherUpdate.getRadius() <= targetRadius) {
                return;
            }
        } else if (connection.hasInitialLocalPlayerInitialized()
                || targetRadius <= 0
                || publisherUpdate.getRadius() == targetRadius) {
            return;
        }
        int original = publisherUpdate.getRadius();
        publisherUpdate.setRadius(targetRadius);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Normalized cross-protocol chunk publisher radius from %d to %d blocks for backend %s during join.%n",
                    original,
                    publisherUpdate.getRadius(),
                    backendName
            );
        }
    }

    private void normalizeJoinChunkRadiusUpdated(ChunkRadiusUpdatedPacket radiusUpdated) {
        if (!backendNeedsLegacyJoinWorkarounds()
                || connection.backendSwitchReset() != null
                || connection.hasSentInitialSyntheticPlayerSpawn()) {
            return;
        }
        int clientRadius = connection.lastRequestedMaxChunkRadius() > 0
                ? connection.lastRequestedMaxChunkRadius()
                : connection.lastRequestedChunkRadius();
        if (clientRadius <= 0 || radiusUpdated.getRadius() >= clientRadius) {
            return;
        }
        int original = radiusUpdated.getRadius();
        radiusUpdated.setRadius(clientRadius);
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Normalized initial cross-protocol chunk radius update from %d to %d chunks for backend %s.%n",
                    original,
                    radiusUpdated.getRadius(),
                    backendName
            );
        }
    }

    private static boolean isSaneJoinY(float y) {
        return !Float.isNaN(y) && y > -128.0f && y < 384.0f;
    }

    private long toClientRuntime(long backendRuntimeEntityId, boolean registerEntity) {
        return connection.toClientRuntimeEntityId(backendRuntimeEntityId, registerEntity);
    }

    private long toClientUnique(long backendUniqueEntityId) {
        return connection.toClientUniqueEntityId(backendUniqueEntityId);
    }

    /**
     * Local-player unique-id rewrite with a loud "did it match?" trace.
     *
     * <p>The whole local-player id mapping keys off {@code StartGame.uniqueEntityId}. If a backend
     * ever reports a different id there than the one it uses in these packets, the mapping silently
     * never fires and the client quietly ignores its own gamemode/ability updates — which looks like
     * a half-applied gamemode rather than an error. Logging the miss makes that failure visible.
     */
    private long traceUniqueRewrite(String label, long backendUniqueEntityId) {
        long clientUniqueEntityId = toClientUnique(backendUniqueEntityId);
        if (connection.isPacketTraceActive()) {
            long expected = connection.backendPlayerUniqueEntityId();
            System.out.printf(
                    "%s unique-id rewrite from backend %s: backendId=%d -> clientId=%d "
                            + "(localPlayerBackendId=%d localPlayerClientId=%d matchedLocalPlayer=%s).%n",
                    label,
                    backendName,
                    backendUniqueEntityId,
                    clientUniqueEntityId,
                    expected,
                    connection.clientPlayerUniqueEntityId(),
                    backendUniqueEntityId == expected && expected != 0
            );
        }
        return clientUniqueEntityId;
    }

    private java.util.List<EntityLinkData> rewriteLinks(java.util.List<EntityLinkData> links) {
        if (links == null || links.isEmpty()) {
            return links;
        }
        for (int i = 0; i < links.size(); i++) {
            links.set(i, rewriteLink(links.get(i)));
        }
        return links;
    }

    private EntityLinkData rewriteLink(EntityLinkData link) {
        return new EntityLinkData(
                toClientRuntime(link.getFrom(), false),
                toClientRuntime(link.getTo(), false),
                link.getType(),
                link.isImmediate(),
                link.isRiderInitiated(),
                link.getVehicleAngularVelocity()
        );
    }

    /**
     * Turns a kick from the backend the player is on into a failover, the way Velocity turns one
     * into a redirect.
     *
     * <p>A graceful backend shutdown sends this well before the socket closes, so without the
     * interception the client is gone long before {@code onDisconnect} — and the RakNet timeout that
     * would eventually fire is ten seconds too late to matter. Forwarding resumes unchanged when
     * failover declines the kick, so a player with no fallback still sees the backend's own
     * message.</p>
     */
    private boolean interceptBackendKick(BedrockPacket packet) {
        if (kickIntercepted) {
            return true;
        }
        if (failover == null) {
            return false;
        }
        CharSequence reason;
        // Whether the backend wrote something for this player, which is what separates a ban from a
        // host going away. messageSkipped is the wire flag itself, so this is the backend's own
        // statement rather than a guess at its text. A disconnect that did not decode has no message
        // to read, and a host-level one is the likelier cause, so it counts as no message.
        boolean backendSuppliedMessage;
        if (packet instanceof DisconnectPacket disconnect) {
            reason = kickReason(disconnect);
            backendSuppliedMessage = !disconnect.isMessageSkipped()
                    && disconnect.getKickMessage() != null
                    && !disconnect.getKickMessage().toString().isBlank();
        } else if (packet instanceof UnknownPacket unknown && unknown.getPacketId() == DISCONNECT_PACKET_ID) {
            reason = "the server closed the connection";
            backendSuppliedMessage = false;
        } else {
            return false;
        }
        if (!failover.failsOverOnBackendKick(backendSuppliedMessage)) {
            // The backend decided something about this player — banned, whitelisted out, kicked by a
            // moderator. Rescuing them to a fallback overrides that decision, and because the
            // fallback transfers them straight back it also loops: kick, failover, transfer, kick.
            // Forwarding the packet unchanged lets the player read the backend's own message, which
            // is the one worth showing; the flag stops onDisconnect starting a failover behind it.
            kickPassedThrough = true;
            System.out.printf(
                    "Backend %s kicked %s (%s); passing the kick through to the client.%n",
                    backendName,
                    connection.client().getSocketAddress(),
                    reason
            );
            return false;
        }
        System.out.printf(
                "Backend %s kicked %s (%s); %s.%n",
                backendName,
                connection.client().getSocketAddress(),
                reason,
                packet instanceof UnknownPacket ? "its disconnect did not decode" : "intercepted"
        );
        // The fault matters here too: a backend that answers a violation with a real disconnect
        // packet rather than by timing out arrives down this path instead of onDisconnect.
        if (!failover.begin(connection, backendName, reason, pendingProtocolFault)) {
            return false;
        }
        kickIntercepted = true;
        // The socket closes moments after this packet; onDisconnect must not undo the failover.
        backend.setDisconnectClientOnClose(false);
        // Close it now rather than waiting: until it does, isConnected() is still true and the
        // client's input keeps being forwarded into a world it is already being moved out of.
        if (backend.isConnected()) {
            backend.disconnect("Failing the player over after a kick");
        }
        return true;
    }

    /**
     * Decodes a violation and, when it is fatal, remembers it as the cause of the disconnect that
     * BDS is about to perform. A non-terminating violation is logged and nothing more: BDS is
     * complaining but carrying on, and dropping the player over it would be worse than the bug.
     */
    private void recordPacketViolation(ByteBuf payload) {
        PacketViolation violation = PacketViolation.decode(payload);
        if (violation == null) {
            return;
        }
        System.err.printf("Backend %s rejected a packet from the proxy: %s.%n", backendName, violation);
        if (violation.isTerminating()) {
            this.pendingProtocolFault = ProtocolFault.fromViolation(
                    backendName, connection.clientLogin().authData().displayName(), violation);
        }
    }

    private static CharSequence kickReason(DisconnectPacket disconnect) {
        String kickMessage = disconnect.getKickMessage();
        if (kickMessage != null && !kickMessage.isBlank()) {
            return kickMessage;
        }
        return String.valueOf(disconnect.getReason());
    }

    /** Overridable so a stubborn packet can be dumped in full without a rebuild. */
    private static final int UNKNOWN_PACKET_DUMP_BYTES =
            Integer.getInteger("proxy.unknownPacketDumpBytes", 1024);

    /**
     * The last {@code PacketViolationWarningPacket} this backend sent, if it was fatal.
     *
     * <p>BDS answers a packet it cannot read with one of these and then tears the connection down
     * without a disconnect packet, so the proxy only ever sees the timeout that follows. Holding the
     * violation lets the disconnect be attributed to the real cause instead of looking like the
     * backend went down — which is the difference between kicking the player with an explanation and
     * silently failing them over into the same bug.</p>
     */
    private ProtocolFault pendingProtocolFault;

    /**
     * Set when a backend kick was relayed to the client instead of being turned into a failover.
     *
     * <p>The socket closes a moment later and {@code onDisconnect} would otherwise read that as the
     * backend dying and start the failover the kick was just spared — which is what put a banned
     * player on the fallback and then straight back onto the backend that banned them.</p>
     */
    private boolean kickPassedThrough;

    private void logUnknownBackendPacket(UnknownPacket unknownPacket) {
        ByteBuf payload = unknownPacket.getPayload();
        if (unknownPacket.getPacketId() == PacketViolation.PACKET_ID) {
            recordPacketViolation(payload);
        }
        int readable = payload == null ? 0 : payload.readableBytes();
        // 128 bytes is enough to identify a packet but not to find where a decode desynced, which is
        // what this dump is actually used for when a new Minecraft version lands. StartGame alone
        // spends its first ~130 bytes on level settings before reaching the gamerules.
        int dumpLength = Math.min(readable, UNKNOWN_PACKET_DUMP_BYTES);
        String dump = payload == null || dumpLength == 0
                ? ""
                : ByteBufUtil.hexDump(payload, payload.readerIndex(), dumpLength);
        System.err.printf(
                "Unknown backend packet from %s: id=%d payloadBytes=%d firstBytes=%s.%n",
                backendName,
                unknownPacket.getPacketId(),
                readable,
                dump
        );
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        if (connection.client().isConnected()) {
            System.err.printf(
                    "Backend %s disconnected player %s unexpectedly: %s.%n",
                    backendName,
                    connection.clientLogin().authData().displayName(),
                    reason
            );
        }
        if (backend == connection.pendingBackend()) {
            activation.onFailure(backend, new IllegalStateException(String.valueOf(reason)));
            return;
        }
        if (backend == connection.backend() && connection.client().isConnected()) {
            if (connection.isFailingOver()) {
                // The kick interception already started this; the socket closing behind it is the
                // expected next step, not a second failure to react to.
                backend.setDisconnectClientOnClose(false);
                return;
            }
            if (joinFailover != null && joinFailover.handleJoinFailure(connection, backendName, reason)) {
                // Dropped before StartGame: the player has no world to be moved out of, so the join
                // try-list owns this, not mid-session failover.
                backend.setDisconnectClientOnClose(false);
                return;
            }
            if (kickPassedThrough) {
                // The client already has the backend's kick and is on its way out. Anything here
                // would be undoing a decision the backend deliberately made.
                connection.client().disconnect(reason);
                return;
            }
            if (failover != null && failover.begin(connection, backendName, reason, pendingProtocolFault)) {
                // BackendSession.onClose() kicks the client right after this returns unless the flag
                // is cleared, which would defeat the failover before it has connected anywhere.
                backend.setDisconnectClientOnClose(false);
                return;
            }
            connection.client().disconnect(reason);
        }
    }

    private boolean isCurrentBackend() {
        return backend == connection.backend() || backend == connection.pendingBackend();
    }

    private record PendingInitialClientbound(
            BedrockPacket packet,
            String originalName,
            String translatedName,
            long traceSequence
    ) {
    }

    private record SyntheticClientCachedChunk(LevelChunkPacket packet, boolean drop) {
    }

    private record PendingCachedChunk(
            int chunkX,
            int chunkZ,
            int subChunksLength,
            boolean requestSubChunks,
            int subChunkLimit,
            int dimension,
            byte[] data,
            long traceSequence,
            long enqueuedAtMillis
    ) {
        private static PendingCachedChunk from(LevelChunkPacket chunk, long traceSequence, long enqueuedAtMillis) {
            return new PendingCachedChunk(
                    chunk.getChunkX(),
                    chunk.getChunkZ(),
                    chunk.getSubChunksLength(),
                    chunk.isRequestSubChunks(),
                    chunk.getSubChunkLimit(),
                    chunk.getDimension(),
                    chunk.getData() == null ? new byte[0] : copyReadableBytes(chunk.getData()),
                    traceSequence,
                    enqueuedAtMillis
            );
        }
    }
}
