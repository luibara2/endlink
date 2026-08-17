package org.endstone.proxy.palette;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Merging for the two entity registries a client reads once at level init.
 *
 * <p>{@code AvailableEntityIdentifiersPacket} carries {@code idlist}, a list of
 * {@code {id, rid, bid, hasspawnegg, summonable}} compounds; an entity whose {@code id} is missing
 * from it has no client-side definition to render and shows up as nothing at all — solid and
 * clickable, but invisible. {@code SyncEntityPropertyPacket} carries one {@code {type, properties}}
 * compound per entity type that declares properties.</p>
 *
 * <p>Entities travel the wire by string identifier (see {@code AddEntitySerializer_v313} onwards),
 * not by {@code rid}, so merging two backends' lists needs no id rewriting on any other packet —
 * only that every {@code rid} in the merged list stays unique.</p>
 */
public final class EntityPalettes {
    private static final String ID_LIST = "idlist";
    private static final String ID = "id";
    private static final String RUNTIME_ID = "rid";
    private static final String TYPE = "type";

    private EntityPalettes() {
    }

    public static String entityPropertyType(NbtMap property) {
        if (property == null) {
            return "";
        }
        return property.getString(TYPE, "");
    }

    public static List<NbtMap> idList(NbtMap identifiers) {
        if (identifiers == null) {
            return List.of();
        }
        return identifiers.getList(ID_LIST, NbtType.COMPOUND, List.of());
    }

    public static String entityId(NbtMap entry) {
        return entry == null ? "" : entry.getString(ID, "");
    }

    /**
     * Merges {@code additional} into {@code base}, keeping base's entries as they are and giving any
     * newly added entry an unused {@code rid}.
     *
     * @return the merged identifiers, or {@code base} when nothing was added
     */
    public static NbtMap mergeIdentifiers(NbtMap base, NbtMap additional) {
        List<NbtMap> baseList = idList(base);
        List<NbtMap> additionalList = idList(additional);
        if (additionalList.isEmpty()) {
            return base;
        }
        if (baseList.isEmpty()) {
            return additional;
        }

        Set<String> known = new LinkedHashSet<>();
        int maxRuntimeId = 0;
        for (NbtMap entry : baseList) {
            known.add(entityId(entry));
            maxRuntimeId = Math.max(maxRuntimeId, entry.getInt(RUNTIME_ID, 0));
        }

        List<NbtMap> merged = new ArrayList<>(baseList);
        boolean changed = false;
        for (NbtMap entry : additionalList) {
            String id = entityId(entry);
            if (id.isEmpty() || !known.add(id)) {
                continue;
            }
            NbtMapBuilder builder = entry.toBuilder();
            builder.putInt(RUNTIME_ID, ++maxRuntimeId);
            merged.add(builder.build());
            changed = true;
        }
        if (!changed) {
            return base;
        }
        return base.toBuilder().putList(ID_LIST, NbtType.COMPOUND, merged).build();
    }

    /** The entity identifiers in {@code additional} that {@code base} does not have. */
    public static List<String> missingFrom(NbtMap base, NbtMap additional) {
        Set<String> known = new LinkedHashSet<>();
        for (NbtMap entry : idList(base)) {
            known.add(entityId(entry));
        }
        List<String> missing = new ArrayList<>();
        for (NbtMap entry : idList(additional)) {
            String id = entityId(entry);
            if (!id.isEmpty() && !known.contains(id)) {
                missing.add(id);
            }
        }
        return missing;
    }
}
