package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.endstone.proxy.protocol.block.BedrockBlockStateHash;
import org.endstone.proxy.protocol.block.SubChunkStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves a sub-chunk request mode client from a backend that only sends whole chunks.
 *
 * <p>A client that met a BDS backend first stays in sub-chunk request mode for the whole session:
 * it expects each {@code LevelChunkPacket} to carry only biomes, and asks for the blocks with
 * {@code SubChunkRequestPacket}s, which it keeps waiting on. PowerNukkitX (and Geyser) put every
 * sub-chunk inside the {@code LevelChunkPacket} and never answer a request, so a player handed over
 * seamlessly sat on "Building terrain". This does what BDS would have done on the backend's behalf:
 * it takes each whole chunk apart, forwards the biome-only shell a request-mode server sends, keeps
 * the sub-chunks, and answers the client's requests from them.
 *
 * <p>A whole chunk's payload is its sub-chunks bottom-up, then one biome palette per section (a
 * backend may send fewer, ending at its highest sub-chunk), then the border-block list, then the
 * block entities as consecutive network-NBT compounds. A request-mode answer is one sub-chunk with
 * the block entities inside it appended, plus a height map the client lights the column from.
 *
 * <p>Requests for a column that has not arrived yet are held and answered when it does, rather than
 * refused: the requests a client sends on entering a world go out before the backend's first chunk,
 * and an unanswered one is exactly the hang this exists to remove.
 *
 * <p>Block changes the backend sends later are applied to the kept sub-chunks, so a re-request never
 * brings an old block back. Everything is bounded: the least recently touched columns are dropped
 * past {@link #MAX_COLUMNS}, which only costs a request that finds nothing and is answered as not
 * found, the same as a BDS backend does for a chunk it has unloaded.
 *
 * <p>Thread-safe: chunks arrive on the backend's event loop and requests on the client's.
 */
public final class SubChunkBridge {

    /** {@code -Dproxy.noSubChunkBridge=true} restores reaching whole-chunk backends by reconnect. */
    public static final boolean ENABLED = !Boolean.getBoolean("proxy.noSubChunkBridge");

    static final int MAX_COLUMNS = 2048;
    /**
     * Per player. A dense PowerNukkitX column is about 85 KB, so this keeps a view radius of roughly
     * ten chunks of solid terrain in full, and a sky world's mostly-air columns many times that.
     */
    static final long MAX_BYTES = 32L * 1024 * 1024;
    static final int MAX_HELD = 8192;

    private static final int HEIGHT_MAP_LENGTH = 256;
    private static final int COPY_LAST_BIOME = (127 << 1) | 1;
    private static final int NO_BLOCK = Integer.MIN_VALUE;

    private final Map<ColumnKey, Column> columns = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<ColumnKey, List<Held>> held = new LinkedHashMap<>();
    private int heldCount;
    private long bytes;
    private boolean active;
    /** The dimension of the newest bridged chunk: block changes carry no dimension of their own. */
    private int dimension;

    /**
     * Whether a whole chunk has been bridged since the last {@link #clear()}. Requests are served
     * here from that point on even for a backend nobody has told the proxy about yet.
     */
    public synchronized boolean isActive() {
        return active;
    }

    /** Forgets every column and held request: a new world, dimension or backend. */
    public synchronized void clear() {
        columns.clear();
        held.clear();
        heldCount = 0;
        bytes = 0;
        active = false;
    }

    public synchronized int columnCount() {
        return columns.size();
    }

    /** The shell sent in place of a bridged chunk, and the held requests it answers. */
    public record Bridged(LevelChunkPacket shell, List<SubChunkPacket> answers) {
    }

    /**
     * Takes a whole chunk apart, keeps its sub-chunks and returns the request-mode shell to send in
     * its place, followed by the answers to any requests that were waiting for it.
     *
     * <p>The chunk itself is only read: its buffer and reference count are left to the caller.
     *
     * @return null when the chunk is not a whole chunk or its payload could not be parsed, in which
     * case it should be forwarded as it is
     */
    public synchronized Bridged bridge(LevelChunkPacket chunk) {
        if (chunk.isRequestSubChunks() || chunk.isCachingEnabled() || chunk.getData() == null) {
            return null;
        }
        Column column;
        ByteBuf shellData;
        try {
            Parsed parsed = parse(chunk);
            column = parsed.column();
            shellData = parsed.shellData();
        } catch (RuntimeException malformed) {
            System.out.printf(
                    "Sub-chunk bridge could not parse chunk (%d,%d) dimension=%d: %s. Forwarding it whole.%n",
                    chunk.getChunkX(), chunk.getChunkZ(), chunk.getDimension(), malformed);
            return null;
        }

        ColumnKey key = new ColumnKey(chunk.getDimension(), chunk.getChunkX(), chunk.getChunkZ());
        Column replaced = columns.put(key, column);
        if (replaced != null) {
            bytes -= replaced.bytes();
        }
        bytes += column.bytes();
        trimColumns();
        active = true;
        dimension = chunk.getDimension();

        LevelChunkPacket shell = new LevelChunkPacket();
        shell.setChunkX(chunk.getChunkX());
        shell.setChunkZ(chunk.getChunkZ());
        shell.setDimension(chunk.getDimension());
        shell.setCachingEnabled(false);
        shell.setRequestSubChunks(true);
        shell.setSubChunksLength(0);
        shell.setSubChunkLimit(column.sent());
        shell.setData(shellData);

        List<SubChunkPacket> answers = new ArrayList<>();
        List<Held> waiting = held.remove(key);
        if (waiting != null) {
            heldCount -= waiting.size();
            Map<CenterKey, SubChunkPacket> byCenter = new LinkedHashMap<>();
            for (Held entry : waiting) {
                SubChunkPacket packet = byCenter.computeIfAbsent(
                        new CenterKey(entry.dimension(), entry.center()),
                        center -> newAnswer(center.dimension(), center.center()));
                packet.getSubChunks().add(entry(column, entry.offset(), entry.absoluteY()));
            }
            answers.addAll(byCenter.values());
        }
        return new Bridged(shell, answers);
    }

    /**
     * Answers a client's sub-chunk request from the kept columns. Offsets in a column that has not
     * arrived are held for {@link #bridge}; the rest are answered now.
     *
     * @return the answer, or null when everything asked for is being held
     */
    public synchronized SubChunkPacket answer(SubChunkRequestPacket request) {
        Vector3i center = request.getSubChunkPosition() == null ? Vector3i.ZERO : request.getSubChunkPosition();
        SubChunkPacket answer = newAnswer(request.getDimension(), center);
        for (Vector3i offset : request.getPositionOffsets()) {
            Vector3i at = center.add(offset);
            ColumnKey key = new ColumnKey(request.getDimension(), at.getX(), at.getZ());
            Column column = columns.get(key);
            if (column != null) {
                answer.getSubChunks().add(entry(column, offset, at.getY()));
            } else if (heldCount < MAX_HELD) {
                held.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new Held(request.getDimension(), center, offset, at.getY()));
                heldCount++;
            } else {
                answer.getSubChunks().add(notFound(offset));
            }
        }
        if (answer.getSubChunks().isEmpty()) {
            answer.release();
            return null;
        }
        return answer;
    }

    /** Applies one block change the backend sent, so a later request gets the block as it is now. */
    public synchronized void updateBlock(Vector3i position, int layer, int runtimeId) {
        if (!active || position == null || layer < 0 || layer > 1) {
            return;
        }
        Column column = columns.get(new ColumnKey(dimension, position.getX() >> 4, position.getZ() >> 4));
        if (column == null) {
            return;
        }
        int index = (position.getY() >> 4) - column.bottom();
        if (index < 0 || index >= column.subChunks().length) {
            return;
        }
        long before = column.bytes();
        try {
            column.setBlock(index, position.getX() & 15, position.getY() & 15, position.getZ() & 15, layer, runtimeId);
            bytes += column.bytes() - before;
        } catch (RuntimeException malformed) {
            // A kept sub-chunk that no longer parses is worse than none: forget the column and let a
            // re-request be answered as not found.
            columns.remove(new ColumnKey(dimension, position.getX() >> 4, position.getZ() >> 4));
            bytes -= before;
        }
    }

    /** Replaces (or, with null, removes) a kept block entity. */
    public synchronized void updateBlockEntity(Vector3i position, NbtMap data) {
        if (!active || position == null) {
            return;
        }
        Column column = columns.get(new ColumnKey(dimension, position.getX() >> 4, position.getZ() >> 4));
        if (column == null) {
            return;
        }
        long packed = packPosition(position.getX(), position.getY(), position.getZ());
        if (data == null || data.isEmpty()) {
            column.blockEntities().remove(packed);
        } else {
            column.blockEntities().put(packed, data);
        }
    }

    /**
     * Keeps the columns within budget, dropping the least recently used one the client has already
     * been given in full before any it is still waiting on: a delivered column is only ever asked
     * for again if the client throws its copy away, while an undelivered one is about to be.
     */
    private void trimColumns() {
        while (!columns.isEmpty() && (columns.size() > MAX_COLUMNS || bytes > MAX_BYTES)) {
            ColumnKey victim = null;
            for (Map.Entry<ColumnKey, Column> kept : columns.entrySet()) {
                if (kept.getValue().delivered()) {
                    victim = kept.getKey();
                    break;
                }
            }
            if (victim == null) {
                victim = columns.keySet().iterator().next();
            }
            bytes -= columns.remove(victim).bytes();
        }
    }

    synchronized long bytes() {
        return bytes;
    }

    private static SubChunkPacket newAnswer(int dimension, Vector3i center) {
        SubChunkPacket packet = new SubChunkPacket();
        packet.setDimension(dimension);
        packet.setCacheEnabled(false);
        packet.setCenterPosition(center);
        return packet;
    }

    private static SubChunkData notFound(Vector3i offset) {
        SubChunkData data = new SubChunkData();
        data.setPosition(offset);
        data.setResult(SubChunkRequestResult.CHUNK_NOT_FOUND);
        data.setHeightMapType(HeightMapDataType.NO_DATA);
        data.setRenderHeightMapType(HeightMapDataType.NO_DATA);
        return data;
    }

    private static SubChunkData entry(Column column, Vector3i offset, int absoluteY) {
        SubChunkData data = new SubChunkData();
        data.setPosition(offset);
        int index = absoluteY - column.bottom();
        if (index < 0 || index >= column.sections()) {
            data.setResult(SubChunkRequestResult.INDEX_OUT_OF_BOUNDS);
            data.setHeightMapType(HeightMapDataType.NO_DATA);
            data.setRenderHeightMapType(HeightMapDataType.NO_DATA);
            return data;
        }

        byte[] heightMap = new byte[HEIGHT_MAP_LENGTH];
        HeightMapDataType heightType = column.heightMap(absoluteY, heightMap);
        data.setHeightMapType(heightType);
        if (heightType == HeightMapDataType.HAS_DATA) {
            data.setHeightMapData(Unpooled.wrappedBuffer(heightMap));
            // What BDS sends: the render height map is the terrain one whenever there is one.
            data.setRenderHeightMapType(HeightMapDataType.COPIED);
        } else {
            data.setRenderHeightMapType(heightType);
        }

        column.markDelivered(index);
        byte[] subChunk = index < column.subChunks().length ? column.subChunks()[index] : null;
        byte[] blockEntities = column.blockEntitiesIn(absoluteY);
        if (subChunk == null || (column.allAir()[index] && blockEntities.length == 0)) {
            data.setResult(SubChunkRequestResult.SUCCESS_ALL_AIR);
            return data;
        }
        data.setResult(SubChunkRequestResult.SUCCESS);
        ByteBuf payload = Unpooled.buffer(subChunk.length + blockEntities.length);
        payload.writeBytes(subChunk);
        payload.writeBytes(blockEntities);
        data.setData(payload);
        return data;
    }

    private record Parsed(Column column, ByteBuf shellData) {
    }

    /** Splits a whole chunk's payload. Throws on anything that is not the expected shape. */
    static Parsed parse(LevelChunkPacket chunk) {
        int dimension = chunk.getDimension();
        int bottom = bottomSection(dimension);
        int sections = sectionCount(dimension);
        int sent = chunk.getSubChunksLength();
        if (sent < 0 || sent > sections) {
            throw new IllegalArgumentException("subChunksLength " + sent + " for " + sections + " sections");
        }

        ByteBuf in = chunk.getData().slice();
        byte[][] subChunks = new byte[sections][];
        boolean[] allAir = new boolean[sections];
        int[] highest = new int[HEIGHT_MAP_LENGTH];
        java.util.Arrays.fill(highest, NO_BLOCK);
        for (int i = 0; i < sent; i++) {
            int start = in.readerIndex();
            SubChunkLayers layers = SubChunkLayers.read(in, bottom + i);
            int y = layers.y();
            int index = y - bottom;
            if (index < 0 || index >= sections) {
                throw new IllegalArgumentException("sub-chunk y " + y + " outside the dimension");
            }
            byte[] bytes = new byte[in.readerIndex() - start];
            in.getBytes(start, bytes);
            subChunks[index] = bytes;
            allAir[index] = layers.raiseHighest(highest);
        }

        ByteBuf shell = Unpooled.buffer();
        int biomes = 0;
        while (biomes < sections && in.isReadable() && (in.getUnsignedByte(in.readerIndex()) & 1) == 1) {
            copyBiomeSection(in, shell, biomes == 0);
            biomes++;
        }
        if (biomes == 0) {
            // A backend sent no biomes at all (an empty placeholder chunk). The client still reads a
            // biome section per sub-chunk from a shell, so give it the plainest one there is.
            writeUniformBiome(shell, 0);
            biomes = 1;
        }
        for (; biomes < sections; biomes++) {
            shell.writeByte(COPY_LAST_BIOME);
        }

        // Border blocks: a count, then one byte each. Copied as they are.
        if (in.isReadable()) {
            int border = in.readUnsignedByte();
            shell.writeByte(border);
            shell.writeBytes(in, border);
        } else {
            shell.writeByte(0);
        }

        Map<Long, NbtMap> blockEntities = new LinkedHashMap<>();
        readBlockEntities(in, blockEntities);

        return new Parsed(new Column(bottom, sections, sent, subChunks, allAir, highest, blockEntities), shell);
    }

    private static void copyBiomeSection(ByteBuf in, ByteBuf out, boolean first) {
        int header = in.readUnsignedByte();
        out.writeByte(header);
        if (header == COPY_LAST_BIOME) {
            if (first) {
                throw new IllegalArgumentException("the first biome section cannot copy the last");
            }
            return;
        }
        int bits = header >>> 1;
        if (bits == 0) {
            copyVarInt(in, out);
            return;
        }
        if (bits > 16) {
            throw new IllegalArgumentException("biome bitsPerBlock " + bits);
        }
        int blocksPerWord = 32 / bits;
        int words = (4096 + blocksPerWord - 1) / blocksPerWord;
        out.writeBytes(in, words * 4);
        int size = VarInts.readInt(in);
        if (size <= 0 || size > 4096) {
            throw new IllegalArgumentException("biome palette size " + size);
        }
        VarInts.writeInt(out, size);
        for (int i = 0; i < size; i++) {
            copyVarInt(in, out);
        }
    }

    private static void writeUniformBiome(ByteBuf out, int biome) {
        out.writeByte((1 << 1) | 1);
        out.writeZero(512);
        VarInts.writeInt(out, 1);
        VarInts.writeInt(out, biome);
    }

    private static void copyVarInt(ByteBuf in, ByteBuf out) {
        int b;
        int count = 0;
        do {
            b = in.readUnsignedByte();
            out.writeByte(b);
            if (++count > 5) {
                throw new IllegalArgumentException("varint too long");
            }
        } while ((b & 0x80) != 0);
    }

    private static void readBlockEntities(ByteBuf in, Map<Long, NbtMap> out) {
        if (!in.isReadable()) {
            return;
        }
        try (NBTInputStream reader = NbtUtils.createNetworkReader(new ByteBufInputStream(in))) {
            while (in.isReadable()) {
                if (in.getUnsignedByte(in.readerIndex()) == 0) {
                    // A lone end tag: PowerNukkitX writes one for "no block entities".
                    in.skipBytes(1);
                    continue;
                }
                Object tag = reader.readTag();
                if (tag instanceof NbtMap map && map.containsKey("x") && map.containsKey("y") && map.containsKey("z")) {
                    out.put(packPosition(map.getInt("x"), map.getInt("y"), map.getInt("z")), map);
                }
            }
        } catch (IOException | RuntimeException malformed) {
            // The blocks are what matter; the backend also sends each block entity on its own.
            System.out.printf("Sub-chunk bridge skipped unreadable block entities: %s.%n", malformed);
        }
    }

    /**
     * For the packet trace: checks a BDS-sent height map against the blocks of its own sub-chunk, to
     * confirm the layout the bridge writes. Counts the columns whose value fits the sub-chunk's own
     * blocks under four readings - the top block or one above it, z-major or x-major - so a change
     * in how BDS writes the map shows up as the matching count moving.
     */
    static String describeHeightMap(SubChunkData subChunk, int absoluteY) {
        if (subChunk.getHeightMapType() != HeightMapDataType.HAS_DATA || subChunk.getHeightMapData() == null) {
            return "";
        }
        ByteBuf map = subChunk.getHeightMapData();
        SubChunkLayers layers = null;
        if (subChunk.getData() != null && subChunk.getData().isReadable()) {
            try {
                layers = SubChunkLayers.read(subChunk.getData().slice(), absoluteY);
            } catch (RuntimeException ignored) {
                layers = null;
            }
        }
        java.util.TreeMap<Integer, Integer> values = new java.util.TreeMap<>();
        // [zMajor top, xMajor top, zMajor top+1, xMajor top+1]
        int[] matches = new int[4];
        int checked = 0;
        for (int i = 0; i < HEIGHT_MAP_LENGTH; i++) {
            int value = map.getByte(map.readerIndex() + i);
            values.merge(value, 1, Integer::sum);
            if (layers == null || value < 0 || value > 16) {
                continue;
            }
            checked++;
            if (value < 16 && layers.isTop(i & 15, value, i >> 4)) {
                matches[0]++;
            }
            if (value < 16 && layers.isTop(i >> 4, value, i & 15)) {
                matches[1]++;
            }
            if (layers.isHeight(i & 15, value, i >> 4)) {
                matches[2]++;
            }
            if (layers.isHeight(i >> 4, value, i & 15)) {
                matches[3]++;
            }
        }
        return ":hm[checked=" + checked + " top=" + matches[0] + "/" + matches[1]
                + " top+1=" + matches[2] + "/" + matches[3] + " values=" + values + "]";
    }

    static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The lowest sub-chunk index of a vanilla dimension. */
    static int bottomSection(int dimension) {
        return dimension == 0 ? -4 : 0;
    }

    /** How many sub-chunks tall a vanilla dimension is. */
    static int sectionCount(int dimension) {
        return switch (dimension) {
            case 1 -> 8;
            case 2 -> 16;
            default -> 24;
        };
    }

    static int heightIndex(int x, int z) {
        return (z << 4) | x;
    }

    private record ColumnKey(int dimension, int x, int z) {
    }

    private record CenterKey(int dimension, Vector3i center) {
    }

    private record Held(int dimension, Vector3i center, Vector3i offset, int absoluteY) {
    }

    /** One chunk column as the backend last described it. */
    static final class Column {
        private final int bottom;
        private final int sections;
        private final int sent;
        private final byte[][] subChunks;
        private final boolean[] allAir;
        private final int[] highest;
        private final Map<Long, NbtMap> blockEntities;
        private final boolean[] delivered;
        private int deliveredCount;
        private long bytes;
        private boolean heightStale;

        Column(int bottom, int sections, int sent, byte[][] subChunks, boolean[] allAir, int[] highest,
               Map<Long, NbtMap> blockEntities) {
            this.bottom = bottom;
            this.sections = sections;
            this.sent = sent;
            this.subChunks = subChunks;
            this.allAir = allAir;
            this.highest = highest;
            this.blockEntities = blockEntities;
            this.delivered = new boolean[sections];
            for (byte[] subChunk : subChunks) {
                bytes += subChunk == null ? 0 : subChunk.length;
            }
        }

        long bytes() {
            return bytes;
        }

        void markDelivered(int index) {
            if (index >= 0 && index < delivered.length && !delivered[index]) {
                delivered[index] = true;
                deliveredCount++;
            }
        }

        /** Whether the client has been sent every sub-chunk it was told to ask for. */
        boolean delivered() {
            return deliveredCount >= sent();
        }

        int bottom() {
            return bottom;
        }

        int sections() {
            return sections;
        }

        /** How many sub-chunks the backend sent, which is how far up the client should ask. */
        int sent() {
            int top = 0;
            for (int i = 0; i < subChunks.length; i++) {
                if (subChunks[i] != null) {
                    top = i + 1;
                }
            }
            return Math.max(top, sent);
        }

        byte[][] subChunks() {
            return subChunks;
        }

        boolean[] allAir() {
            return allAir;
        }

        Map<Long, NbtMap> blockEntities() {
            return blockEntities;
        }

        void setBlock(int index, int x, int y, int z, int layer, int runtimeId) {
            byte[] current = subChunks[index];
            SubChunkLayers layers = current == null
                    ? SubChunkLayers.empty(bottom + index)
                    : SubChunkLayers.read(Unpooled.wrappedBuffer(current), bottom + index);
            layers = layers.withBlock(layer, SubChunkStorage.index(x, y, z), runtimeId);
            ByteBuf out = Unpooled.buffer();
            layers.write(out);
            byte[] written = new byte[out.readableBytes()];
            out.readBytes(written);
            bytes += written.length - (subChunks[index] == null ? 0 : subChunks[index].length);
            subChunks[index] = written;
            allAir[index] = layers.isAllAir();
            heightStale = true;
        }

        /**
         * Fills in the height map of the sub-chunk at {@code absoluteY} the way BDS does, z-major:
         * where each column's open sky starts (one above its top block, or the world's floor for an
         * empty column) relative to the sub-chunk, 16 when that is above it and -1 when below.
         *
         * <p>Read off a BDS hub through the packet trace ({@link #describeHeightMap}): "top block
         * plus one", z-major, matched up to 253 of 256 columns against the sub-chunk's own blocks,
         * where "top block" and x-major never passed 88. The misses are blocks light passes through,
         * which BDS does not count and this cannot tell apart from solid ones.
         */
        HeightMapDataType heightMap(int absoluteY, byte[] out) {
            if (heightStale) {
                java.util.Arrays.fill(highest, NO_BLOCK);
                for (int i = 0; i < subChunks.length; i++) {
                    if (subChunks[i] != null) {
                        SubChunkLayers.read(Unpooled.wrappedBuffer(subChunks[i]), bottom + i).raiseHighest(highest);
                    }
                }
                heightStale = false;
            }
            int base = absoluteY << 4;
            boolean higher = false;
            boolean lower = false;
            boolean within = false;
            for (int i = 0; i < HEIGHT_MAP_LENGTH; i++) {
                int sky = highest[i] == NO_BLOCK ? bottom << 4 : highest[i] + 1;
                if (sky >= base + 16) {
                    out[i] = 16;
                    higher = true;
                } else if (sky < base) {
                    out[i] = -1;
                    lower = true;
                } else {
                    out[i] = (byte) (sky - base);
                    within = true;
                }
            }
            if (!within && !lower) {
                return HeightMapDataType.TOO_HIGH;
            }
            if (!within && !higher) {
                return HeightMapDataType.TOO_LOW;
            }
            return HeightMapDataType.HAS_DATA;
        }

        byte[] blockEntitiesIn(int absoluteY) {
            if (blockEntities.isEmpty()) {
                return new byte[0];
            }
            ByteBuf out = Unpooled.buffer();
            try (NBTOutputStream writer = NbtUtils.createNetworkWriter(new ByteBufOutputStream(out))) {
                for (NbtMap entity : blockEntities.values()) {
                    if ((entity.getInt("y") >> 4) == absoluteY) {
                        writer.writeTag(entity);
                    }
                }
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        }
    }

    /**
     * One sub-chunk's block storages, read far enough to know which blocks are air and to change
     * one. Versions 1, 8 and 9 are read; a sub-chunk is always written back as version 9.
     */
    static final class SubChunkLayers {
        private final int y;
        private final SubChunkStorage[] layers;

        private SubChunkLayers(int y, SubChunkStorage[] layers) {
            this.y = y;
            this.layers = layers;
        }

        static SubChunkLayers empty(int y) {
            return new SubChunkLayers(y, new SubChunkStorage[]{SubChunkStorage.uniform(BedrockBlockStateHash.AIR)});
        }

        static SubChunkLayers read(ByteBuf in, int fallbackY) {
            int version = in.readUnsignedByte();
            int count;
            int y = fallbackY;
            switch (version) {
                case 1 -> count = 1;
                case 8 -> count = in.readUnsignedByte();
                case 9 -> {
                    count = in.readUnsignedByte();
                    y = in.readByte();
                }
                default -> throw new IllegalArgumentException("sub-chunk version " + version);
            }
            if (count > 8) {
                throw new IllegalArgumentException("storage count " + count);
            }
            SubChunkStorage[] layers = new SubChunkStorage[count];
            for (int i = 0; i < count; i++) {
                layers[i] = SubChunkStorage.read(in);
            }
            return new SubChunkLayers(y, layers);
        }

        int y() {
            return y;
        }

        boolean isAllAir() {
            for (SubChunkStorage layer : layers) {
                if (hasBlocks(layer)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether any position holds something other than air. The palette answers it almost always;
         * only a palette with an entry nothing points at any more, which a changed block leaves
         * behind, needs the positions read.
         */
        private static boolean hasBlocks(SubChunkStorage layer) {
            boolean paletteHasBlocks = false;
            for (int id : layer.palette()) {
                if (id != BedrockBlockStateHash.AIR) {
                    paletteHasBlocks = true;
                    break;
                }
            }
            if (!paletteHasBlocks) {
                return false;
            }
            if (layer.palette().length == 1) {
                return true;
            }
            for (int index = 0; index < 4096; index++) {
                if (layer.blockAt(index) != BedrockBlockStateHash.AIR) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Raises each column's highest non-air block to include this sub-chunk.
         *
         * @return whether the sub-chunk is entirely air
         */
        boolean raiseHighest(int[] highest) {
            if (isAllAir()) {
                return true;
            }
            int base = y << 4;
            for (SubChunkStorage layer : layers) {
                if (!hasBlocks(layer)) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int column = heightIndex(x, z);
                        for (int localY = 15; localY >= 0; localY--) {
                            if (base + localY <= highest[column]) {
                                break;
                            }
                            if (layer.blockAt(SubChunkStorage.index(x, localY, z)) != BedrockBlockStateHash.AIR) {
                                highest[column] = base + localY;
                                break;
                            }
                        }
                    }
                }
            }
            return false;
        }

        /** Whether (x, y, z) holds a block and everything above it in this sub-chunk is air. */
        boolean isTop(int x, int y, int z) {
            boolean solid = false;
            for (SubChunkStorage layer : layers) {
                if (layer.blockAt(SubChunkStorage.index(x, y, z)) != BedrockBlockStateHash.AIR) {
                    solid = true;
                }
                for (int above = y + 1; above < 16; above++) {
                    if (layer.blockAt(SubChunkStorage.index(x, above, z)) != BedrockBlockStateHash.AIR) {
                        return false;
                    }
                }
            }
            return solid;
        }

        /**
         * Whether {@code height} is where open sky starts in this sub-chunk's column: air from there
         * up, and a block just below it (or the sub-chunk's floor).
         */
        boolean isHeight(int x, int height, int z) {
            for (SubChunkStorage layer : layers) {
                for (int y = height; y < 16; y++) {
                    if (layer.blockAt(SubChunkStorage.index(x, y, z)) != BedrockBlockStateHash.AIR) {
                        return false;
                    }
                }
            }
            if (height == 0) {
                return true;
            }
            for (SubChunkStorage layer : layers) {
                if (layer.blockAt(SubChunkStorage.index(x, height - 1, z)) != BedrockBlockStateHash.AIR) {
                    return true;
                }
            }
            return false;
        }

        SubChunkLayers withBlock(int layer, int index, int runtimeId) {
            SubChunkStorage[] next = java.util.Arrays.copyOf(layers, Math.max(layers.length, layer + 1));
            for (int i = 0; i < next.length; i++) {
                if (next[i] == null) {
                    next[i] = SubChunkStorage.uniform(BedrockBlockStateHash.AIR);
                }
            }
            next[layer] = next[layer].withBlockAt(index, runtimeId);
            return new SubChunkLayers(y, next);
        }

        void write(ByteBuf out) {
            out.writeByte(9);
            out.writeByte(layers.length);
            out.writeByte(y);
            for (SubChunkStorage layer : layers) {
                layer.write(out);
            }
        }
    }
}
