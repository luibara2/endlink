package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.LocatorBarWaypoint;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddHangingEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LocatorBarPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScoreboardIdentityPacket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClientWorldState {
    private final Set<Long> entityUniqueIds = new LinkedHashSet<>();
    private final Set<EntityLinkKey> entityLinks = new LinkedHashSet<>();
    private final Set<UUID> playerListEntries = new LinkedHashSet<>();
    private final Set<Long> bossBars = new LinkedHashSet<>();
    private final Set<String> scoreboardObjectives = new LinkedHashSet<>();
    private final Set<Long> scoreboardIdentities = new LinkedHashSet<>();
    private final Set<UUID> locatorWaypointGroups = new LinkedHashSet<>();

    public synchronized void track(BedrockPacket packet) {
        if (packet instanceof AddEntityPacket addEntity) {
            addEntity(addEntity.getUniqueEntityId());
            addEntity.getEntityLinks().forEach(this::addLink);
        } else if (packet instanceof AddPlayerPacket addPlayer) {
            addEntity(addPlayer.getUniqueEntityId());
            addPlayer.getEntityLinks().forEach(this::addLink);
            if (addPlayer.getUuid() != null) {
                playerListEntries.add(addPlayer.getUuid());
            }
        } else if (packet instanceof AddItemEntityPacket addItemEntity) {
            addEntity(addItemEntity.getUniqueEntityId());
        } else if (packet instanceof AddHangingEntityPacket addHangingEntity) {
            addEntity(addHangingEntity.getUniqueEntityId());
        } else if (packet instanceof RemoveEntityPacket removeEntity) {
            entityUniqueIds.remove(removeEntity.getUniqueEntityId());
            entityLinks.removeIf(link -> link.from() == removeEntity.getUniqueEntityId()
                    || link.to() == removeEntity.getUniqueEntityId());
        } else if (packet instanceof SetEntityLinkPacket linkPacket && linkPacket.getEntityLink() != null) {
            EntityLinkData link = linkPacket.getEntityLink();
            if (link.getType() == EntityLinkData.Type.REMOVE) {
                entityLinks.remove(new EntityLinkKey(link.getFrom(), link.getTo()));
            } else {
                addLink(link);
            }
        } else if (packet instanceof PlayerListPacket playerList) {
            trackPlayerList(playerList);
        } else if (packet instanceof BossEventPacket bossEvent) {
            trackBossEvent(bossEvent);
        } else if (packet instanceof SetDisplayObjectivePacket displayObjective) {
            addObjective(displayObjective.getObjectiveId());
        } else if (packet instanceof SetScorePacket setScore) {
            setScore.getInfos().forEach(score -> addObjective(score.getObjectiveId()));
        } else if (packet instanceof RemoveObjectivePacket removeObjective) {
            scoreboardObjectives.remove(removeObjective.getObjectiveId());
        } else if (packet instanceof SetScoreboardIdentityPacket scoreboardIdentity) {
            trackScoreboardIdentity(scoreboardIdentity);
        } else if (packet instanceof LocatorBarPacket locatorBar) {
            trackLocatorBar(locatorBar);
        }
    }

    public synchronized List<BedrockPacket> clearPackets() {
        List<BedrockPacket> packets = new ArrayList<>();
        for (EntityLinkKey link : entityLinks) {
            SetEntityLinkPacket removeLink = new SetEntityLinkPacket();
            removeLink.setEntityLink(new EntityLinkData(
                    link.from(),
                    link.to(),
                    EntityLinkData.Type.REMOVE,
                    false,
                    false
            ));
            packets.add(removeLink);
        }
        for (Long bossBar : bossBars) {
            BossEventPacket removeBossBar = new BossEventPacket();
            removeBossBar.setAction(BossEventPacket.Action.REMOVE);
            removeBossBar.setBossUniqueEntityId(bossBar);
            // Protocol 1001+ writes the full BossEvent shape for every action, including REMOVE.
            // Keep its always-present text fields encodable even though the client ignores them.
            removeBossBar.setTitle("");
            removeBossBar.setFilteredTitle("");
            packets.add(removeBossBar);
        }
        for (String objectiveId : scoreboardObjectives) {
            RemoveObjectivePacket removeObjective = new RemoveObjectivePacket();
            removeObjective.setObjectiveId(objectiveId);
            packets.add(removeObjective);
        }
        if (!scoreboardIdentities.isEmpty()) {
            SetScoreboardIdentityPacket removeIdentities = new SetScoreboardIdentityPacket();
            removeIdentities.setAction(SetScoreboardIdentityPacket.Action.REMOVE);
            for (Long scoreboardId : scoreboardIdentities) {
                // Both the legacy UUID and modern player-id serializers encode only scoreboardId
                // for REMOVE, so the second constructor value is deliberately just a placeholder.
                removeIdentities.getEntries().add(new SetScoreboardIdentityPacket.Entry(scoreboardId, 0L));
            }
            packets.add(removeIdentities);
        }
        if (!locatorWaypointGroups.isEmpty()) {
            LocatorBarPacket removeWaypoints = new LocatorBarPacket();
            for (UUID groupHandle : locatorWaypointGroups) {
                // A modern locator icon is owned by its group handle. PlayerLocation(HIDE), actor
                // removal and player-list removal do not delete it; a bare LocatorBar REMOVE does.
                removeWaypoints.getWaypoints().add(new LocatorBarPacket.Payload(
                        LocatorBarPacket.Action.REMOVE,
                        groupHandle,
                        new LocatorBarWaypoint()
                ));
            }
            packets.add(removeWaypoints);
        }
        if (!playerListEntries.isEmpty()) {
            PlayerListPacket removePlayers = new PlayerListPacket();
            removePlayers.setAction(PlayerListPacket.Action.REMOVE);
            for (UUID uuid : playerListEntries) {
                PlayerListPacket.Entry entry = new PlayerListPacket.Entry(uuid);
                // 1.26.40 encodes the action per entry rather than once per packet, so setting only
                // the packet-level action above leaves a 2168 client's serializer nothing to write.
                entry.setAction(PlayerListPacket.Action.REMOVE);
                removePlayers.getEntries().add(entry);
            }
            packets.add(removePlayers);
        }
        for (Long entityUniqueId : entityUniqueIds) {
            RemoveEntityPacket removeEntity = new RemoveEntityPacket();
            removeEntity.setUniqueEntityId(entityUniqueId);
            packets.add(removeEntity);
        }
        int entityCount = entityUniqueIds.size();
        int linkCount = entityLinks.size();
        int playerCount = playerListEntries.size();
        int bossBarCount = bossBars.size();
        int objectiveCount = scoreboardObjectives.size();
        int scoreboardIdentityCount = scoreboardIdentities.size();
        int locatorWaypointCount = locatorWaypointGroups.size();
        entityUniqueIds.clear();
        entityLinks.clear();
        playerListEntries.clear();
        bossBars.clear();
        scoreboardObjectives.clear();
        scoreboardIdentities.clear();
        locatorWaypointGroups.clear();
        if (ProxyConnection.isPacketTracingConfigured()) {
            System.out.printf(
                    "Prepared client world cleanup: entities=%d links=%d playerListEntries=%d bossBars=%d "
                            + "scoreboardObjectives=%d scoreboardIdentities=%d locatorWaypoints=%d packets=%d.%n",
                    entityCount,
                    linkCount,
                    playerCount,
                    bossBarCount,
                    objectiveCount,
                    scoreboardIdentityCount,
                    locatorWaypointCount,
                    packets.size()
            );
        }
        return packets;
    }

    private void addEntity(long uniqueEntityId) {
        if (uniqueEntityId != 0) {
            entityUniqueIds.add(uniqueEntityId);
        }
    }

    private void addLink(EntityLinkData link) {
        entityLinks.add(new EntityLinkKey(link.getFrom(), link.getTo()));
    }

    private void trackPlayerList(PlayerListPacket packet) {
        if (packet.getAction() == PlayerListPacket.Action.ADD) {
            for (PlayerListPacket.Entry entry : packet.getEntries()) {
                if (entry.getUuid() != null) {
                    playerListEntries.add(entry.getUuid());
                }
            }
        } else if (packet.getAction() == PlayerListPacket.Action.REMOVE) {
            for (PlayerListPacket.Entry entry : packet.getEntries()) {
                playerListEntries.remove(entry.getUuid());
            }
        }
    }

    private void trackBossEvent(BossEventPacket packet) {
        if (packet.getAction() == BossEventPacket.Action.CREATE) {
            bossBars.add(packet.getBossUniqueEntityId());
        } else if (packet.getAction() == BossEventPacket.Action.REMOVE) {
            bossBars.remove(packet.getBossUniqueEntityId());
        }
    }

    private void addObjective(String objectiveId) {
        if (objectiveId != null && !objectiveId.isEmpty()) {
            scoreboardObjectives.add(objectiveId);
        }
    }

    private void trackScoreboardIdentity(SetScoreboardIdentityPacket packet) {
        if (packet.getAction() == SetScoreboardIdentityPacket.Action.ADD) {
            for (SetScoreboardIdentityPacket.Entry entry : packet.getEntries()) {
                scoreboardIdentities.add(entry.getScoreboardId());
            }
        } else if (packet.getAction() == SetScoreboardIdentityPacket.Action.REMOVE) {
            for (SetScoreboardIdentityPacket.Entry entry : packet.getEntries()) {
                scoreboardIdentities.remove(entry.getScoreboardId());
            }
        }
    }

    private void trackLocatorBar(LocatorBarPacket packet) {
        for (LocatorBarPacket.Payload payload : packet.getWaypoints()) {
            UUID groupHandle = payload.getGroupHandle();
            if (groupHandle == null) {
                continue;
            }
            if (payload.getActionFlag() == LocatorBarPacket.Action.REMOVE) {
                locatorWaypointGroups.remove(groupHandle);
            } else {
                // ADD, UPDATE and NONE all prove that the client may hold state for this handle.
                // An extra REMOVE during the next switch is harmless and prevents a stale waypoint.
                locatorWaypointGroups.add(groupHandle);
            }
        }
    }

    private record EntityLinkKey(long from, long to) {
    }
}
