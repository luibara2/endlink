package org.endstone.proxy.backend;

import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.camera.AimAssistAction;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftResultsDeprecatedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.RecipeItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistInstructionPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheBlobStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheMissResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.endstone.proxy.command.CommandInterception;
import org.endstone.proxy.command.ProxyCommandInterceptor;

public final class ClientRelayPacketHandler implements BedrockPacketHandler {
    private static final int INITIAL_CROSS_PROTOCOL_BACKEND_CHUNK_RADIUS = 8;

    private final ProxyConnection connection;
    private final ProxyCommandInterceptor commandInterceptor;
    private final BackendCommandRouter commandRouter;

    public ClientRelayPacketHandler(
            ProxyConnection connection,
            ProxyCommandInterceptor commandInterceptor,
            BackendCommandRouter commandRouter
    ) {
        this.connection = connection;
        this.commandInterceptor = commandInterceptor;
        this.commandRouter = commandRouter;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        long traceSequence = -1;
        if (connection.isPacketTraceActive()) {
            traceSequence = connection.nextServerboundTraceSequence();
            System.out.printf(
                    "Trace serverbound #%d +%dms from %s: %s backend=%s pending=%s switchReset=%s.%n",
                    traceSequence,
                    connection.elapsedMillis(),
                    connection.client().getSocketAddress(),
                    packet.getClass().getSimpleName(),
                    connection.backendName(),
                    connection.pendingBackend() != null,
                    connection.backendSwitchReset() != null
            );
            logServerboundDetails(packet);
        }
        if (connection.isPacketTraceActive()) {
            logMovementStateChange(packet);
        }

        if (connection.backend() == null || !connection.backend().isConnected()) {
            if (connection.isFailingOver() || connection.isJoinSequenceActive()) {
                // The backend died and the proxy is moving the player to a fallback. Their client
                // keeps sending input at ~20/s in the meantime; those packets have nowhere to go,
                // but dropping them is the whole point — kicking here is what failover exists to
                // avoid. Forwarding resumes once the fallback's StartGame swaps in a live backend.
                return PacketSignal.HANDLED;
            }
            connection.client().disconnect("Backend is not connected");
            return PacketSignal.HANDLED;
        }

        if (packet instanceof UnknownPacket unknownPacket && !unknownPacket.isRelayable()) {
            // The same rule as the clientbound path, in the direction that protects the backend. A
            // client is free to send anything, so a malformed serverbound packet is the ordinary
            // shape of both a broken client and a hostile one; relaying it lets that client end its
            // own session on the backend, and on a shared backend that is a nuisance others notice.
            // See PacketValidationException.
            System.out.printf(
                    "Dropped a malformed packet id=%d from %s rather than relaying it to backend %s."
                            + " The reason is on the UNDECODABLE line above.%n",
                    unknownPacket.getPacketId(),
                    connection.client().getSocketAddress(),
                    connection.backendName()
            );
            return PacketSignal.HANDLED;
        }

        if (isCrossProtocol() && packet instanceof CameraAimAssistInstructionPacket aimAssist) {
            handleCrossProtocolCameraAimAssistInstruction(aimAssist);
            return PacketSignal.HANDLED;
        }

        if (isCrossProtocol() && shouldDropCrossProtocolServerbound(packet)) {
            System.out.printf(
                    "Dropping serverbound cross-protocol packet from %s for backend protocol %d: %s.%n",
                    connection.client().getSocketAddress(),
                    connection.sessionProfile().backendCodec().getProtocolVersion(),
                    packet.getClass().getSimpleName()
            );
            return PacketSignal.HANDLED;
        }

        if (isEarlyCrossProtocolJoinPacket(packet)) {
            System.out.printf(
                    "Dropping early cross-protocol join packet before first LevelChunk from %s for 1.21.130 backend: %s.%n",
                    connection.client().getSocketAddress(),
                    packet.getClass().getSimpleName()
            );
            return PacketSignal.HANDLED;
        }

        if (packet instanceof PacketViolationWarningPacket violation) {
            System.err.printf(
                    "Client packet violation from %s: type=%s severity=%s packetId=%d message=%s.%n",
                    connection.client().getSocketAddress(),
                    violation.getType(),
                    violation.getSeverity(),
                    violation.getPacketCauseId(),
                    violation.getContext()
            );
        }

        if (packet instanceof RequestChunkRadiusPacket requestChunkRadius) {
            normalizeChunkRadiusRequest(requestChunkRadius);
            connection.rememberChunkRadius(requestChunkRadius.getRadius(), requestChunkRadius.getMaxRadius());
        }
        boolean initialModernJoinReadyTrigger = isInitialModernJoinReadyTrigger(packet);
        if (initialModernJoinReadyTrigger) {
            connection.markInitialLoadingScreenStarted();
            sendInitialCrossProtocolBackendRespawnReady();
            if (isCrossProtocol()
                    && !connection.hasForwardedLevelChunk()
                    && connection.backendSwitchReset() == null
                    && !connection.markInitialBackendLoadingScreenStarted()) {
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Forwarding real initial cross-protocol loading-screen start from %s even though backend was already primed.%n",
                            connection.client().getSocketAddress()
                    );
                }
            }
        }
        if (packet instanceof SetLocalPlayerAsInitializedPacket && connection.backendSwitchReset() == null) {
            connection.markInitialLocalPlayerInitialized();
            replayDeferredInitialEntitySpawns();
        }

        BackendSwitchReset switchReset = connection.backendSwitchReset();
        if (switchReset != null && switchReset.isActive()) {
            if (packet instanceof PlayerActionPacket action
                    && action.getAction() == PlayerActionType.DIMENSION_CHANGE_SUCCESS) {
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Received client dimension-change ack during backend switch to %s.%n",
                            connection.backendName()
                    );
                }
                switchReset.handleDimensionChangeSuccess(connection);
                return PacketSignal.HANDLED;
            }
            if (packet instanceof SetLocalPlayerAsInitializedPacket initialized) {
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Suppressing early client initialization during backend switch to %s: runtimeEntityId=%d.%n",
                            connection.backendName(),
                            initialized.getRuntimeEntityId()
                    );
                }
                return PacketSignal.HANDLED;
            }
            if (packet instanceof ServerboundLoadingScreenPacket loadingScreen) {
                if (connection.isPacketTraceActive()) {
                    System.out.printf(
                            "Received client loading-screen ack during backend switch to %s: type=%s id=%s.%n",
                            connection.backendName(),
                            loadingScreen.getType(),
                            loadingScreen.getLoadingScreenId()
                    );
                }
                switchReset.handleLoadingScreen(connection, loadingScreen);
                return PacketSignal.HANDLED;
            }
            if (packet instanceof SubChunkRequestPacket request) {
                switchReset.handleTargetWorldRequest(connection, request.getDimension());
            }
        }

        // In cross-protocol mode, intercept ServerboundLoadingScreenPacket after the initial
        // join completes. Protocol 944 does not use loading-screen packets for death respawn;
        // instead it uses a RespawnPacket(CLIENT_READY)/SERVER_READY handshake.
        //
        // Translation:
        //   START_LOADING_SCREEN (1.26.20 client clicked Respawn) →
        //       RespawnPacket(CLIENT_READY) to 944 backend, so it finds the respawn point and
        //       replies with SERVER_READY.
        //   END_LOADING_SCREEN (1.26.20 client finished loading) →
        //       PlayerActionPacket(RESPAWN) to 944 backend to complete the respawn cycle.
        //
        // Gate on hasInitialLocalPlayerInitialized(): END_LOADING_SCREEN arrives just before
        // SetLocalPlayerAsInitializedPacket during initial join, so the flag is false then and
        // the initial join loading-screen packets are forwarded normally.
        if (backendUsesLegacyDeathRespawn()
                && connection.hasInitialLocalPlayerInitialized()
                && connection.backendSwitchReset() == null
                && packet instanceof ServerboundLoadingScreenPacket loadingScreen) {
            org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType type = loadingScreen.getType();
            if (type == org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.START_LOADING_SCREEN) {
                connection.markDeathRespawnLoadingScreenStarted();
                RespawnPacket clientReady = new RespawnPacket();
                clientReady.setState(RespawnPacket.State.CLIENT_READY);
                clientReady.setPosition(Vector3f.ZERO);
                clientReady.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
                connection.backend().sendPacket(clientReady);
                System.out.printf(
                        "Translated START_LOADING_SCREEN to CLIENT_READY for cross-protocol backend: runtimeEntityId=%d.%n",
                        clientReady.getRuntimeEntityId()
                );
            } else if (type == org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.END_LOADING_SCREEN) {
                if (!connection.consumeDeathRespawnLoadingScreenEnd()) {
                    // Trailing dimension-change loading screen from a backend switch, not a death
                    // respawn. Consume it without sending a spurious RESPAWN to the backend, which
                    // would otherwise reset the freshly switched-in player's state.
                    System.out.printf(
                            "Consumed trailing END_LOADING_SCREEN (no death respawn in progress) for cross-protocol backend %s.%n",
                            connection.backendName()
                    );
                    return PacketSignal.HANDLED;
                }
                PlayerActionPacket respawn = new PlayerActionPacket();
                respawn.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
                respawn.setAction(PlayerActionType.RESPAWN);
                respawn.setBlockPosition(Vector3i.ZERO);
                respawn.setResultPosition(Vector3i.ZERO);
                respawn.setFace(0);
                connection.backend().sendPacket(respawn);
                System.out.printf(
                        "Translated END_LOADING_SCREEN to PlayerAction(RESPAWN) for cross-protocol backend: runtimeEntityId=%d.%n",
                        respawn.getRuntimeEntityId()
                );
            }
            return PacketSignal.HANDLED;
        }

        BackendSession pendingBackend = pendingSwitchBackend();
        if (packet instanceof ClientCacheStatusPacket cacheStatus) {
            BackendSession targetBackend = pendingBackend == null ? connection.backend() : pendingBackend;
            if (isCrossProtocol()) {
                ClientCacheStatusPacket disabledCache = new ClientCacheStatusPacket();
                disabledCache.setSupported(false);
                System.out.printf(
                        "Disabling backend blob cache for cross-protocol join from %s to backend %s: clientSupported=%s backendSupported=false.%n",
                        connection.client().getSocketAddress(),
                        connection.backendName(),
                        cacheStatus.isSupported()
                );
                sendToBackend(targetBackend, disabledCache, traceSequence);
                return PacketSignal.HANDLED;
            }
            ClientCacheStatusPacket disabledCache = new ClientCacheStatusPacket();
            disabledCache.setSupported(false);
            targetBackend.sendPacket(disabledCache);
            return PacketSignal.HANDLED;
        }

        if (packet instanceof ClientCacheBlobStatusPacket blobStatus && handleSyntheticClientChunkBlobStatus(blobStatus)) {
            return PacketSignal.HANDLED;
        }

        if (pendingBackend != null && isBackendLoginResponse(packet)) {
            sendToBackend(pendingBackend, packet);
            return PacketSignal.HANDLED;
        }

        // Proxy resource pack serving during the resource pack negotiation phase.
        ProxyResourcePackRegistry registry = connection.proxyResourcePackRegistry();
        if (!registry.isEmpty()) {
            // Serve proxy pack chunks directly from the proxy - but only the ones this client was
            // actually told the proxy would serve. Holding a pack is not the same as being the right
            // source for it: the merge can find the cached copy out of date and leave the backend to
            // send it, and answering here anyway would deliver the copy it just rejected.
            if (packet instanceof ResourcePackChunkRequestPacket chunkRequest
                    && connection.isProxyServedPack(chunkRequest.getPackId())) {
                registry.sendChunk(connection.client(), chunkRequest.getPackId(), chunkRequest.getChunkIndex());
                return PacketSignal.HANDLED;
            }
            // Filter proxy pack IDs out of send_packs before forwarding to backend.
            if (packet instanceof ResourcePackClientResponsePacket response
                    && response.getStatus() == ResourcePackClientResponsePacket.Status.SEND_PACKS) {
                return handleSendPacksWithProxyFilter(response, registry, traceSequence);
            }
        }

        if (packet instanceof CommandRequestPacket commandRequest) {
            if (connection.isPacketTraceActive()) {
                logCommandRequest(commandRequest);
            }
            if (isClientSideCommandPreview(commandRequest)) {
                System.out.printf(
                        "Suppressing client-side command preview from %s: %s%n",
                        connection.client().getSocketAddress(),
                        commandRequest.getCommand()
                );
                return PacketSignal.HANDLED;
            }
            CommandInterception interception = commandInterceptor.intercept(commandRequest);
            if (interception instanceof CommandInterception.Consumed consumed) {
                commandRouter.execute(connection, consumed);
                return PacketSignal.HANDLED;
            }
            if (connection.isPacketTraceActive()) {
                System.out.printf(
                        "Forwarding native command from %s to backend %s: %s%n",
                        connection.client().getSocketAddress(),
                        connection.backendName(),
                        commandRequest.getCommand()
                );
            }
            connection.tracePacketsForMillis(5_000);
            sendToBackend(connection.backend(), commandRequest.clone(), traceSequence);
            return PacketSignal.HANDLED;
        }

        sendToBackend(connection.backend(), packet, traceSequence);
        return PacketSignal.HANDLED;
    }

    private static boolean isInitialModernJoinReadyTrigger(BedrockPacket packet) {
        if (packet instanceof ServerboundLoadingScreenPacket loadingScreen) {
            return "START_LOADING_SCREEN".equals(String.valueOf(loadingScreen.getType()));
        }
        return false;
    }

    private void replayDeferredInitialEntitySpawns() {
        java.util.List<BedrockPacket> deferred = connection.drainDeferredInitialEntitySpawns();
        if (deferred.isEmpty()) {
            return;
        }
        for (BedrockPacket spawn : deferred) {
            connection.client().sendPacket(spawn);
        }
        if (connection.isPacketTraceActive()) {
            System.out.printf(
                    "Replayed %d deferred initial entity spawn(s) for %s after local player initialized.%n",
                    deferred.size(),
                    connection.client().getSocketAddress()
            );
        }
    }

    private void sendInitialCrossProtocolBackendRespawnReady() {
        if (!isCrossProtocol()
                || connection.hasForwardedLevelChunk()
                || connection.backendSwitchReset() != null
                || !connection.hasInitialServerSearchingSeen()
                || !connection.markInitialBackendRespawnReadySent()) {
            return;
        }

        Vector3f position = connection.initialServerSearchingPosition();
        if (position == null) {
            position = connection.saneJoinPosition();
        }
        if (position == null) {
            position = Vector3f.from(0.5f, 72.0f, 0.5f);
        }

        RespawnPacket ready = new RespawnPacket();
        ready.setState(RespawnPacket.State.CLIENT_READY);
        ready.setPosition(position);
        ready.setRuntimeEntityId(connection.backendPlayerRuntimeEntityId());
        connection.backend().sendPacket(ready);
        System.out.printf(
                "Acknowledged initial cross-protocol backend respawn after client loading-screen start: backend=%s runtimeEntityId=%d position=%s.%n",
                connection.backendName(),
                ready.getRuntimeEntityId(),
                ready.getPosition()
        );
    }

    /**
     * Caps the chunk view distance the backend is asked for, from {@code -Dproxy.forceChunkRadius=2}.
     * Zero (the default) leaves the client's request alone.
     *
     * <p><b>Why this exists.</b> Every drop-based experiment on the 1.26.40 disconnect is confounded,
     * and the terrain one worst of all: dropping {@code LevelChunk} kept the session alive for
     * minutes, but it also left the client with no chunks, and <b>a Bedrock client will not move the
     * player until the chunk under them is loaded</b>. The tester confirmed it — zombies visible,
     * movement input doing nothing, teleport working. So that run silently reproduced "standing
     * still", which the symptom table has always listed as fine. It proved nothing.
     *
     * <p>A radius cap changes the same variable — how much terrain is streamed — while leaving the
     * player able to fly, which is the only activity that reproduces the bug in seconds. It is the
     * first terrain experiment that holds activity constant.
     *
     * <p>It is also a candidate mitigation rather than only a diagnostic: if radius 2-4 is stable and
     * radius 8 is not, that is shippable while the root cause is still open.
     */
    private static final int FORCED_CHUNK_RADIUS = Integer.getInteger("proxy.forceChunkRadius", 0);

    private void normalizeChunkRadiusRequest(RequestChunkRadiusPacket request) {
        if (FORCED_CHUNK_RADIUS > 0 && request.getRadius() > FORCED_CHUNK_RADIUS) {
            System.out.printf(
                    "Diagnostics: forcing chunk radius for %s: radius=%d maxRadius=%d -> %d.%n",
                    connection.client().getSocketAddress(),
                    request.getRadius(),
                    request.getMaxRadius(),
                    FORCED_CHUNK_RADIUS
            );
            request.setRadius(FORCED_CHUNK_RADIUS);
            if (request.getMaxRadius() > FORCED_CHUNK_RADIUS) {
                request.setMaxRadius(FORCED_CHUNK_RADIUS);
            }
        }
        if (!isCrossProtocol()) {
            return;
        }
        if (!connection.hasForwardedLevelChunk()
                && request.getRadius() > INITIAL_CROSS_PROTOCOL_BACKEND_CHUNK_RADIUS) {
            System.out.printf(
                    "Clamping initial cross-protocol chunk radius request from %s for backend join: radius=%d maxRadius=%d -> radius=%d.%n",
                    connection.client().getSocketAddress(),
                    request.getRadius(),
                    request.getMaxRadius(),
                    INITIAL_CROSS_PROTOCOL_BACKEND_CHUNK_RADIUS
            );
            request.setRadius(INITIAL_CROSS_PROTOCOL_BACKEND_CHUNK_RADIUS);
            return;
        }
        if (request.getMaxRadius() > 0 && request.getMaxRadius() < request.getRadius()) {
            System.out.printf(
                    "Normalizing cross-protocol chunk radius request from %s: radius=%d maxRadius=%d -> radius=%d.%n",
                    connection.client().getSocketAddress(),
                    request.getRadius(),
                    request.getMaxRadius(),
                    request.getMaxRadius()
            );
            request.setRadius(request.getMaxRadius());
        }
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        connection.closeBackend(reason);
    }

    private void logCommandRequest(CommandRequestPacket commandRequest) {
        CommandOriginData origin = commandRequest.getCommandOriginData();
        System.out.printf(
                "Command request from %s: command=%s internal=%s version=%d commandVersion=%s origin=%s requestId=%s playerId=%d.%n",
                connection.client().getSocketAddress(),
                commandRequest.getCommand(),
                commandRequest.isInternal(),
                commandRequest.getVersion(),
                commandRequest.getCommandVersion(),
                origin == null ? null : origin.getOrigin(),
                origin == null ? null : origin.getRequestId(),
                origin == null ? -1 : origin.getPlayerId()
        );
    }

    private static boolean isClientSideCommandPreview(CommandRequestPacket commandRequest) {
        String command = commandRequest.getCommand();
        if (command == null || command.trim().isEmpty() || "/".equals(command.trim())) {
            return true;
        }
        return commandRequest.isInternal();
    }

    private BackendSession pendingSwitchBackend() {
        BackendSession pendingBackend = connection.pendingBackend();
        if (!connection.isSwitchingBackend() || pendingBackend == null || !pendingBackend.isConnected()) {
            return null;
        }
        return pendingBackend;
    }

    private PacketSignal handleSendPacksWithProxyFilter(
            ResourcePackClientResponsePacket response,
            ProxyResourcePackRegistry registry,
            long traceSequence
    ) {
        ArrayList<String> backendPackIds = new ArrayList<>();
        for (String packId : response.getPackIds()) {
            UUID uuid = extractPackUuid(packId);
            if (uuid != null && connection.isProxyServedPack(uuid)) {
                // Send DataInfo for this proxy pack directly to the client.
                registry.sendDataInfo(connection.client(), uuid);
            } else {
                backendPackIds.add(packId);
            }
        }
        if (!backendPackIds.isEmpty()) {
            // Forward only the backend pack IDs to the backend.
            ResourcePackClientResponsePacket filtered = new ResourcePackClientResponsePacket();
            filtered.setStatus(ResourcePackClientResponsePacket.Status.SEND_PACKS);
            filtered.getPackIds().addAll(backendPackIds);
            sendToBackend(connection.backend(), filtered, traceSequence);
        }
        // If the list contained only proxy pack IDs, nothing is forwarded; the backend waits
        // until the client sends have_all_packs (after downloading the proxy packs).
        return PacketSignal.HANDLED;
    }

    private static UUID extractPackUuid(String packId) {
        if (packId == null || packId.isEmpty()) return null;
        // Format is "uuid_version" or just "uuid"; UUIDs use hyphens, not underscores.
        int underscore = packId.indexOf('_');
        String uuidPart = underscore >= 0 ? packId.substring(0, underscore) : packId;
        try {
            return UUID.fromString(uuidPart);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isBackendLoginResponse(BedrockPacket packet) {
        return packet instanceof ResourcePackClientResponsePacket
                || packet instanceof ResourcePackChunkRequestPacket;
    }

    private void sendToBackend(BackendSession backend, BedrockPacket packet) {
        sendToBackend(backend, packet, connection.isPacketTraceActive() ? 0 : -1);
    }

    private void sendToBackend(BackendSession backend, BedrockPacket packet, long traceSequence) {
        // Sub-chunk mode belongs to the client's session, not to one backend, so it survives a
        // switch: a client taught to request terrain a sub-chunk at a time by a BDS backend goes on
        // doing it after the handoff. A backend that never advertised the system then receives
        // requests it cannot answer. Withheld here rather than translated away because there is
        // nothing to translate to — the request simply has no meaning there.
        if (backend != null && backend.dropSubChunkRequests() && packet instanceof SubChunkRequestPacket) {
            if (traceSequence >= 0 || connection.isPacketTraceActive()) {
                System.out.printf(
                        "Withholding SubChunkRequest from backend %s: it does not implement the sub-chunk system.%n",
                        connection.backendName()
                );
            }
            return;
        }
        normalizePlayerRuntimeId(packet);
        normalizeChatIdentity(packet);
        BedrockPacket translated = connection.sessionProfile()
                .translator()
                .translateServerbound(packet, connection.sessionProfile().translationContext());
        if (translated == null) {
            if (traceSequence >= 0 || connection.isPacketTraceActive()) {
                System.out.printf(
                        "Dropping serverbound packet after protocol translation for backend protocol %d: %s.%n",
                        connection.sessionProfile().backendCodec().getProtocolVersion(),
                        packet.getClass().getSimpleName()
                );
            }
            return;
        }
        backend.sendPacket(ReferenceCountUtil.retain(translated));
        if (traceSequence >= 0 && connection.isPacketTraceActive()) {
            String id = traceSequence > 0 ? "#" + traceSequence + " " : "";
            System.out.printf(
                    "Forwarded serverbound %s+%dms to backend %s original=%s translated=%s clientConnected=%s backendConnected=%s.%n",
                    id,
                    connection.elapsedMillis(),
                    connection.backendName(),
                    packet.getClass().getSimpleName(),
                    translated.getClass().getSimpleName(),
                    connection.client().isConnected(),
                    backend.isConnected()
            );
        }
    }

    /**
     * Stamps the authenticated identity onto anything the client says.
     *
     * <p>{@code TextPacket} carries the author's name and XUID as plain fields the client fills in,
     * and nothing downstream re-derives them from the session. A modified client can therefore send
     * chat as any name it likes — an owner's, a staff member's — and every backend, plugin and chat
     * log that trusts those fields repeats it. The values here come from the Mojang-signed login
     * chain, so overwriting them costs an honest client nothing and closes the impersonation.</p>
     *
     * <p>The packet type is deliberately left alone: dropping a non-CHAT type would be a behaviour
     * change for backends that use them, and with the name and XUID corrected the remaining
     * capability is sending oneself an odd-looking message.</p>
     */
    private void normalizeChatIdentity(BedrockPacket packet) {
        if (!(packet instanceof org.cloudburstmc.protocol.bedrock.packet.TextPacket text)) {
            return;
        }
        String displayName = connection.clientLogin().authData().displayName();
        String xuid = connection.clientLogin().authData().xuid();
        // A vanilla client sends its XUID blank and lets the server fill it in, so a blank one is
        // normal and only a populated-but-wrong value is worth reporting.
        boolean forgedXuid = text.getXuid() != null && !text.getXuid().isBlank() && !xuid.equals(text.getXuid());
        if (!displayName.equals(text.getSourceName()) || forgedXuid) {
            System.out.printf(
                    "Rewrote serverbound chat identity from %s: sourceName=%s xuid=%s -> %s/%s.%n",
                    connection.client().getSocketAddress(),
                    text.getSourceName(),
                    text.getXuid(),
                    displayName,
                    xuid
            );
            text.setSourceName(displayName);
            text.setXuid(forgedXuid ? xuid : text.getXuid());
        }
    }

    /**
     * Rewrites the local player's runtime id on packets the client addresses to itself.
     *
     * <p>The client keeps the id from its first StartGame for the whole proxy session, while every
     * backend assigns its own — after a switch they differ, and a packet still carrying the client's
     * id names an entity the backend does not associate with this player. It is dropped silently:
     * nothing errors, the action simply never happens.</p>
     *
     * <p>Anything here must also be remapped in the other direction by
     * {@code BackendRelayPacketHandler.rewriteClientboundRuntimeIds}; a packet handled on one side
     * only is the shape of bug this list exists to prevent.</p>
     */
    void normalizePlayerRuntimeId(BedrockPacket packet) {
        long backendPlayerRuntimeEntityId = connection.backendPlayerRuntimeEntityId();
        if (backendPlayerRuntimeEntityId <= 0) {
            return;
        }
        if (packet instanceof PlayerActionPacket playerAction) {
            playerAction.setRuntimeEntityId(backendPlayerRuntimeEntityId);
        } else if (packet instanceof RespawnPacket respawn) {
            // The client's answer to the death screen. Addressed to the wrong entity the backend
            // never replies SERVER_READY, and the player sits on "Respawning..." forever while the
            // client retries. Only ever sent about the local player.
            respawn.setRuntimeEntityId(backendPlayerRuntimeEntityId);
        } else if (packet instanceof MobEquipmentPacket mobEquipment) {
            mobEquipment.setRuntimeEntityId(backendPlayerRuntimeEntityId);
        } else if (packet instanceof AnimatePacket animate) {
            animate.setRuntimeEntityId(backendPlayerRuntimeEntityId);
        } else if (packet instanceof SetLocalPlayerAsInitializedPacket initialized) {
            initialized.setRuntimeEntityId(backendPlayerRuntimeEntityId);
        } else if (packet instanceof InteractPacket interact) {
            interact.setRuntimeEntityId(connection.toBackendRuntimeEntityId(interact.getRuntimeEntityId()));
            if (interact.getAction() == InteractPacket.Action.OPEN_INVENTORY) {
                interact.setRuntimeEntityId(backendPlayerRuntimeEntityId);
            }
        } else if (packet instanceof InventoryTransactionPacket transaction) {
            transaction.setRuntimeEntityId(connection.toBackendRuntimeEntityId(transaction.getRuntimeEntityId()));
        }
    }

    private java.util.Set<PlayerAuthInputData> lastLoggedInputData;
    private long lastLoggedInputTick = Long.MIN_VALUE;
    private long lastInputStateLogMillis = Long.MIN_VALUE;

    /**
     * Movement sample interval while packet tracing is enabled, from
     * {@code -Dproxy.movementSampleMillis=N}. Zero records every {@code PlayerAuthInput}.
     */
    private static final long MOVEMENT_SAMPLE_MILLIS =
            Long.getLong("proxy.movementSampleMillis", 1000L);

    /** Rendered into the startup {@code Diagnostics:} line so a run's posture is always visible. */
    public static String movementSampleSummary() {
        return "movementSampleMillis=" + MOVEMENT_SAMPLE_MILLIS + " (packet trace only)"
                + (MOVEMENT_SAMPLE_MILLIS <= 0 ? " (every PlayerAuthInput)" : "");
    }

    private void logMovementStateChange(BedrockPacket packet) {
        if (!(packet instanceof PlayerAuthInputPacket authInput)) {
            return;
        }
        long now = connection.elapsedMillis();
        java.util.Set<PlayerAuthInputData> inputData = authInput.getInputData();
        boolean changed = lastLoggedInputData == null || !lastLoggedInputData.equals(inputData);
        boolean tickWentBackwards = authInput.getTick() < lastLoggedInputTick;
        if (!changed && !tickWentBackwards && now - lastInputStateLogMillis < MOVEMENT_SAMPLE_MILLIS) {
            return;
        }
        System.out.printf(
                "Movement +%dms tick=%d%s pos=%s delta=%s rotation=(%s,%s) input=%s inputMode=%s playMode=%s.%n",
                now,
                authInput.getTick(),
                tickWentBackwards ? " TICK-WENT-BACKWARDS(prev=" + lastLoggedInputTick + ")" : "",
                authInput.getPosition(),
                authInput.getDelta(),
                authInput.getRotation() == null ? "?" : authInput.getRotation().getX(),
                authInput.getRotation() == null ? "?" : authInput.getRotation().getY(),
                inputData,
                authInput.getInputMode(),
                authInput.getPlayMode()
        );
        lastLoggedInputData = java.util.EnumSet.copyOf(inputData.isEmpty()
                ? java.util.EnumSet.noneOf(PlayerAuthInputData.class)
                : inputData);
        lastLoggedInputTick = authInput.getTick();
        lastInputStateLogMillis = now;
    }

    private void logServerboundDetails(BedrockPacket packet) {
        if (packet instanceof PlayerAuthInputPacket authInput) {
            if (authInput.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION)
                    || authInput.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS)
                    || authInput.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST)
                    || authInput.getInputData().contains(PlayerAuthInputData.MISSED_SWING)) {
                System.out.printf(
                        "  PlayerAuthInput tick=%d pos=%s input=%s itemUse=%s blockActions=%s stackRequest=%s predictedVehicle=%d.%n",
                        authInput.getTick(),
                        authInput.getPosition(),
                        authInput.getInputData(),
                        describeItemUse(authInput.getItemUseTransaction()),
                        describeBlockActions(authInput),
                        authInput.getItemStackRequest() == null ? null : authInput.getItemStackRequest().getActions().length,
                        authInput.getPredictedVehicle()
                );
                // A vanilla Bedrock client puts its request here rather than sending the standalone
                // packet, so without this the reference case -- what a real client does -- is the
                // one case the trace cannot show.
                logItemStackRequest(authInput.getItemStackRequest());
            }
        } else if (packet instanceof ItemStackRequestPacket stackRequest) {
            for (ItemStackRequest request : stackRequest.getRequests()) {
                logItemStackRequest(request);
            }
        } else if (packet instanceof InventoryTransactionPacket transaction) {
            System.out.printf(
                    "  InventoryTransaction type=%s actionType=%d runtimeEntityId=%d block=%s face=%d hotbar=%d item=%s blockDef=%s actions=%d legacyRequest=%d.%n",
                    transaction.getTransactionType(),
                    transaction.getActionType(),
                    transaction.getRuntimeEntityId(),
                    transaction.getBlockPosition(),
                    transaction.getBlockFace(),
                    transaction.getHotbarSlot(),
                    transaction.getItemInHand(),
                    transaction.getBlockDefinition(),
                    transaction.getActions().size(),
                    transaction.getLegacyRequestId()
            );
        } else if (packet instanceof PlayerActionPacket action) {
            System.out.printf(
                    "  PlayerAction action=%s runtimeEntityId=%d block=%s result=%s face=%d normalizedRuntimeEntityId=%d.%n",
                    action.getAction(),
                    action.getRuntimeEntityId(),
                    action.getBlockPosition(),
                    action.getResultPosition(),
                    action.getFace(),
                    connection.backendPlayerRuntimeEntityId()
            );
        } else if (packet instanceof RespawnPacket respawn) {
            System.out.printf(
                    "  Respawn state=%s runtimeEntityId=%d position=%s normalizedRuntimeEntityId=%d.%n",
                    respawn.getState(),
                    respawn.getRuntimeEntityId(),
                    respawn.getPosition(),
                    connection.backendPlayerRuntimeEntityId()
            );
        } else if (packet instanceof InteractPacket interact) {
            System.out.printf(
                    "  Interact action=%s runtimeEntityId=%d mouse=%s playerRuntimeEntityId=%d.%n",
                    interact.getAction(),
                    interact.getRuntimeEntityId(),
                    interact.getMousePosition(),
                    connection.backendPlayerRuntimeEntityId()
            );
        } else if (packet instanceof SubChunkRequestPacket request) {
            System.out.printf(
                    "  SubChunkRequest dimension=%d center=%s offsets=%d firstOffsets=%s.%n",
                    request.getDimension(),
                    request.getSubChunkPosition(),
                    request.getPositionOffsets().size(),
                    request.getPositionOffsets().stream().limit(12).toList()
            );
        } else if (packet instanceof RequestChunkRadiusPacket request) {
            System.out.printf(
                    "  RequestChunkRadius radius=%d maxRadius=%d rememberedBefore=%d/%d.%n",
                    request.getRadius(),
                    request.getMaxRadius(),
                    connection.lastRequestedChunkRadius(),
                    connection.lastRequestedMaxChunkRadius()
            );
        } else if (packet instanceof ClientCacheStatusPacket cacheStatus) {
            System.out.printf(
                    "  ClientCacheStatus supported=%s.%n",
                    cacheStatus.isSupported()
            );
        } else if (packet instanceof ClientCacheBlobStatusPacket blobStatus) {
            System.out.printf(
                    "  ClientCacheBlobStatus acks=%d naks=%d firstAcks=%s firstNaks=%s.%n",
                    blobStatus.getAcks().size(),
                    blobStatus.getNaks().size(),
                    blobStatus.getAcks().longStream().limit(8).boxed().toList(),
                    blobStatus.getNaks().longStream().limit(8).boxed().toList()
            );
        } else if (packet instanceof ServerboundLoadingScreenPacket loadingScreen) {
            System.out.printf(
                    "  ServerboundLoadingScreen type=%s id=%s.%n",
                    loadingScreen.getType(),
                    loadingScreen.getLoadingScreenId()
            );
        } else if (packet instanceof ResourcePackClientResponsePacket response) {
            // The status is the whole content of this packet, and the backend's pack handshake is a
            // strict order of them: without it a trace shows two identical lines and no way to tell a
            // correct handshake from one that ends it early. A client that answers COMPLETED before
            // HAVE_ALL_PACKS gets kicked for the trailing packet several seconds later, by which point
            // nothing in the log still points here.
            System.out.printf(
                    "  ResourcePackClientResponse status=%s packs=%d.%n",
                    response.getStatus(),
                    response.getPackIds().size()
            );
        }
    }

    private void handleCrossProtocolCameraAimAssistInstruction(CameraAimAssistInstructionPacket instruction) {
        if (!connection.hasForwardedLevelChunk()) {
            System.out.printf(
                    "Consumed early cross-protocol camera aim-assist instruction from %s before chunks: action=%s presetId=%s allow=%s.%n",
                    connection.client().getSocketAddress(),
                    instruction.getAction(),
                    instruction.getPresetId(),
                    instruction.isAllowAimAssist()
            );
            return;
        }

        CameraAimAssistPacket response = new CameraAimAssistPacket();
        response.setPresetId(instruction.getPresetId() == null ? "" : instruction.getPresetId());
        response.setAction(instruction.getAction() == null ? AimAssistAction.CLEAR : instruction.getAction());
        response.setViewAngle(Vector2f.from(30.0f, 45.0f));
        response.setDistance(5.7f);
        response.setTargetMode(CameraAimAssistPacket.TargetMode.ANGLE);
        response.setShowDebugRender(false);
        connection.client().sendPacket(response);
        System.out.printf(
                "Handled cross-protocol camera aim-assist instruction locally for %s: action=%s presetId=%s allow=%s.%n",
                connection.client().getSocketAddress(),
                instruction.getAction(),
                instruction.getPresetId(),
                instruction.isAllowAimAssist()
        );
    }

    private boolean handleSyntheticClientChunkBlobStatus(ClientCacheBlobStatusPacket blobStatus) {
        if (!isCrossProtocol()) {
            return false;
        }
        connection.markInitialClientChunkCacheStatusSeen();

        ClientCacheMissResponsePacket response = new ClientCacheMissResponsePacket();
        int missingKnownBlobs = 0;
        for (int i = 0; i < blobStatus.getNaks().size(); i++) {
            long blobId = blobStatus.getNaks().getLong(i);
            byte[] blob = connection.syntheticClientChunkBlob(blobId);
            if (blob == null) {
                continue;
            }
            response.getBlobs().put(blobId, Unpooled.wrappedBuffer(blob));
            missingKnownBlobs++;
        }

        if (missingKnownBlobs > 0) {
            connection.client().sendPacket(response);
            System.out.printf(
                    "Answered %d synthetic client chunk blob miss(es) for %s: acks=%d naks=%d firstNaks=%s.%n",
                    missingKnownBlobs,
                    connection.client().getSocketAddress(),
                    blobStatus.getAcks().size(),
                    blobStatus.getNaks().size(),
                    blobStatus.getNaks().longStream().limit(8).boxed().toList()
            );
        } else {
            response.release();
            if (!blobStatus.getAcks().isEmpty() || !blobStatus.getNaks().isEmpty()) {
                System.out.printf(
                        "Consumed synthetic client chunk blob status for %s without misses: acks=%d naks=%d firstAcks=%s firstNaks=%s.%n",
                        connection.client().getSocketAddress(),
                        blobStatus.getAcks().size(),
                        blobStatus.getNaks().size(),
                        blobStatus.getAcks().longStream().limit(8).boxed().toList(),
                        blobStatus.getNaks().longStream().limit(8).boxed().toList()
                );
            }
        }
        return true;
    }

    private boolean shouldDropCrossProtocolServerbound(BedrockPacket packet) {
        return switch (packet.getClass().getSimpleName()) {
            case "EditorNetworkPacket",
                 "ResourcePacksReadyForValidationPacket",
                 "PartyChangedPacket",
                 "ServerboundDataDrivenScreenClosedPacket",
                 "ServerboundDiagnosticsPacket" -> true;
            default -> false;
        };
    }

    private boolean isEarlyCrossProtocolJoinPacket(BedrockPacket packet) {
        if (!isCrossProtocol() || connection.hasForwardedLevelChunk()) {
            return false;
        }
        return switch (packet.getClass().getSimpleName()) {
            case "SetPlayerGameTypePacket" -> true;
            default -> false;
        };
    }

    private boolean isCrossProtocol() {
        return connection.sessionProfile().clientCodec().getProtocolVersion()
                != connection.sessionProfile().backendCodec().getProtocolVersion();
    }

    /**
     * Whether the backend predates {@code ServerboundLoadingScreenPacket} (v712) and therefore needs
     * the client's loading-screen death flow translated into the old {@code RespawnPacket}
     * handshake. See {@code BackendRelayPacketHandler#backendUsesLegacyDeathRespawn} for why this is
     * a different question from {@link #isCrossProtocol()}, and what conflating them broke.
     */
    private boolean backendUsesLegacyDeathRespawn() {
        return connection.sessionProfile().backendCodec().getProtocolVersion() < 712;
    }

    /**
     * Prints an {@code ItemStackRequest} in full.
     *
     * <p>In full because the server's answer is a single word. A request is accepted or rejected as
     * a whole — {@code FailedToValidateSrcSlot}, with nothing about which of its actions or which
     * slot — so the only way to tell a stale stack network id from a wrongly named container is to
     * have the request itself sitting beside the response.</p>
     */
    private static void logItemStackRequest(ItemStackRequest request) {
        if (request == null) {
            return;
        }
        StringBuilder line = new StringBuilder("  ItemStackRequest id=").append(request.getRequestId());
        for (ItemStackRequestAction action : request.getActions()) {
            line.append("\n    ").append(action.getType()).append(' ').append(describeStackRequestAction(action));
        }
        System.out.println(line + ".");
    }

    /**
     * One {@code ItemStackRequest} action, with the three fields the server validates it on.
     *
     * <p>A slot is named by its <em>kind</em> and its index within that kind, and carries the stack
     * network id the client believes is there — and the server refuses the whole request if any of
     * the three disagrees with its own view. All three therefore have to be visible; a rejection
     * reason on its own cannot distinguish them.</p>
     */
    private static String describeStackRequestAction(ItemStackRequestAction action) {
        if (action instanceof TransferItemStackRequestAction transfer) {
            return "count=" + transfer.getCount()
                    + " from=" + describeStackRequestSlot(transfer.getSource())
                    + " to=" + describeStackRequestSlot(transfer.getDestination());
        }
        if (action instanceof SwapAction swap) {
            return "from=" + describeStackRequestSlot(swap.getSource())
                    + " to=" + describeStackRequestSlot(swap.getDestination());
        }
        if (action instanceof DropAction drop) {
            return "count=" + drop.getCount() + " from=" + describeStackRequestSlot(drop.getSource())
                    + " randomly=" + drop.isRandomly();
        }
        if (action instanceof DestroyAction destroy) {
            return "count=" + destroy.getCount() + " from=" + describeStackRequestSlot(destroy.getSource());
        }
        if (action instanceof ConsumeAction consume) {
            return "count=" + consume.getCount() + " from=" + describeStackRequestSlot(consume.getSource());
        }
        if (action instanceof CraftResultsDeprecatedAction results) {
            return "timesCrafted=" + results.getTimesCrafted()
                    + " results=" + Arrays.toString(results.getResultItems());
        }
        if (action instanceof RecipeItemStackRequestAction recipe) {
            return "recipeNetworkId=" + recipe.getRecipeNetworkId()
                    + " crafts=" + recipe.getNumberOfRequestedCrafts();
        }
        if (action instanceof CraftCreativeAction creative) {
            return "creativeItemNetworkId=" + creative.getCreativeItemNetworkId()
                    + " crafts=" + creative.getNumberOfRequestedCrafts();
        }
        return action.toString();
    }

    private static String describeStackRequestSlot(ItemStackRequestSlotData slot) {
        if (slot == null) {
            return "null";
        }
        return slot.getContainer() + "[" + slot.getSlot() + "] netId=" + slot.getStackNetworkId();
    }

    private static String describeItemUse(ItemUseTransaction transaction) {
        if (transaction == null) {
            return "null";
        }
        return "actionType=" + transaction.getActionType()
                + " block=" + transaction.getBlockPosition()
                + " face=" + transaction.getBlockFace()
                + " hotbar=" + transaction.getHotbarSlot()
                + " item=" + transaction.getItemInHand()
                + " blockDef=" + transaction.getBlockDefinition()
                + " trigger=" + transaction.getTriggerType()
                + " prediction=" + transaction.getClientInteractPrediction()
                + " actions=" + transaction.getActions().size();
    }

    private static String describeBlockActions(PlayerAuthInputPacket authInput) {
        if (authInput.getPlayerActions().isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < authInput.getPlayerActions().size(); i++) {
            PlayerBlockActionData action = authInput.getPlayerActions().get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(action.getAction())
                    .append("@")
                    .append(action.getBlockPosition())
                    .append("/")
                    .append(action.getFace());
        }
        return builder.append("]").toString();
    }
}
