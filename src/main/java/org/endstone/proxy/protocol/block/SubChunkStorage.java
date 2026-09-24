package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.common.util.VarInts;

/**
 * One block storage layer of a sub-chunk, unpacked into a block per position.
 *
 * <p>{@link SubChunkPaletteRewriter} only ever rewrites palette entries, which is enough when every
 * block of a kind becomes the same new block. Stair corners are not like that: two stairs sharing a
 * palette entry can need different corner values, so the entry they point at has to change per
 * position, which means unpacking the bit array and packing it again.
 *
 * <p>Bedrock packs the 4096 positions into 32-bit words, {@code 32 / bitsPerBlock} of them per word,
 * low bits first, with the remainder of each word unused — 3, 5 and 6 bits per block each waste two
 * bits per word. Positions are indexed {@code (x << 8) | (z << 4) | y}, so a sub-chunk is an X-major,
 * then Z, then Y walk. Valid widths are 1, 2, 3, 4, 5, 6, 8 and 16 bits; a width of 0 means the whole
 * sub-chunk is one block and no words are written at all.
 */
public final class SubChunkStorage {

    static final int BLOCKS = 4096;

    /** The widths Bedrock will read, smallest first. */
    private static final int[] WIDTHS = {1, 2, 3, 4, 5, 6, 8, 16};

    private final int[] positions;
    private final int[] palette;

    private SubChunkStorage(int[] positions, int[] palette) {
        this.positions = positions;
        this.palette = palette;
    }

    /** The block at {@code (x << 8) | (z << 4) | y}, as a network id. */
    public int blockAt(int index) {
        return palette[positions[index]];
    }

    public int[] palette() {
        return palette;
    }

    /** Replaces the block at one position, extending the palette if the id is new. */
    public SubChunkStorage withBlockAt(int index, int runtimeId) {
        if (blockAt(index) == runtimeId) {
            return this;
        }
        int entry = -1;
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == runtimeId) {
                entry = i;
                break;
            }
        }
        int[] newPalette = palette;
        if (entry < 0) {
            newPalette = new int[palette.length + 1];
            System.arraycopy(palette, 0, newPalette, 0, palette.length);
            newPalette[palette.length] = runtimeId;
            entry = palette.length;
        }
        int[] newPositions = positions.clone();
        newPositions[index] = entry;
        return new SubChunkStorage(newPositions, newPalette);
    }

    /** A storage holding {@code runtimeIds[index]} at each position, with the palette they need. */
    public static SubChunkStorage fromBlocks(int[] runtimeIds) {
        if (runtimeIds.length != BLOCKS) {
            throw new IllegalArgumentException("a storage holds " + BLOCKS + " blocks, not " + runtimeIds.length);
        }
        int[] positions = new int[BLOCKS];
        java.util.Map<Integer, Integer> entries = new java.util.HashMap<>();
        int[] palette = new int[8];
        int size = 0;
        int lastId = 0;
        int lastEntry = -1;
        for (int index = 0; index < BLOCKS; index++) {
            int id = runtimeIds[index];
            if (lastEntry < 0 || id != lastId) {
                Integer entry = entries.get(id);
                if (entry == null) {
                    if (size == palette.length) {
                        palette = java.util.Arrays.copyOf(palette, size * 2);
                    }
                    palette[size] = id;
                    entry = size++;
                    entries.put(id, entry);
                }
                lastId = id;
                lastEntry = entry;
            }
            positions[index] = lastEntry;
        }
        return new SubChunkStorage(positions, java.util.Arrays.copyOf(palette, size));
    }

    /** A storage holding one block everywhere. */
    public static SubChunkStorage uniform(int runtimeId) {
        return new SubChunkStorage(new int[BLOCKS], new int[]{runtimeId});
    }

    public static int index(int x, int y, int z) {
        return (x << 8) | (z << 4) | y;
    }

    /**
     * Reads one storage layer, advancing {@code in} past it.
     *
     * @throws IllegalArgumentException if the layer is not a shape Bedrock can have written
     */
    public static SubChunkStorage read(ByteBuf in) {
        int header = in.readUnsignedByte();
        int bitsPerBlock = header >>> 1;

        if (bitsPerBlock == 0) {
            int[] palette = readPalette(in);
            if (palette.length != 1) {
                throw new IllegalArgumentException("a uniform storage must have one palette entry");
            }
            return new SubChunkStorage(new int[BLOCKS], palette);
        }
        if (!isValidWidth(bitsPerBlock)) {
            throw new IllegalArgumentException("bitsPerBlock " + bitsPerBlock);
        }

        int blocksPerWord = 32 / bitsPerBlock;
        int wordCount = (BLOCKS + blocksPerWord - 1) / blocksPerWord;
        int mask = (1 << bitsPerBlock) - 1;
        int[] positions = new int[BLOCKS];
        int position = 0;
        for (int word = 0; word < wordCount; word++) {
            int packed = in.readIntLE();
            for (int slot = 0; slot < blocksPerWord && position < BLOCKS; slot++) {
                positions[position++] = (packed >>> (slot * bitsPerBlock)) & mask;
            }
        }

        int[] palette = readPalette(in);
        for (int entry : positions) {
            if (entry >= palette.length) {
                throw new IllegalArgumentException("position points past the palette");
            }
        }
        return new SubChunkStorage(positions, palette);
    }

    /** Writes this storage in the narrowest width its palette fits in. */
    public void write(ByteBuf out) {
        if (palette.length == 1) {
            out.writeByte(1); // width 0, network ids
            VarInts.writeInt(out, 1);
            VarInts.writeInt(out, palette[0]);
            return;
        }

        int bitsPerBlock = widthFor(palette.length);
        out.writeByte((bitsPerBlock << 1) | 1);

        int blocksPerWord = 32 / bitsPerBlock;
        int wordCount = (BLOCKS + blocksPerWord - 1) / blocksPerWord;
        int position = 0;
        for (int word = 0; word < wordCount; word++) {
            int packed = 0;
            for (int slot = 0; slot < blocksPerWord && position < BLOCKS; slot++) {
                packed |= positions[position++] << (slot * bitsPerBlock);
            }
            out.writeIntLE(packed);
        }

        VarInts.writeInt(out, palette.length);
        for (int id : palette) {
            VarInts.writeInt(out, id);
        }
    }

    private static int[] readPalette(ByteBuf in) {
        int size = VarInts.readInt(in);
        if (size <= 0 || size > BLOCKS) {
            throw new IllegalArgumentException("palette size " + size);
        }
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = VarInts.readInt(in);
        }
        return palette;
    }

    private static boolean isValidWidth(int bitsPerBlock) {
        for (int width : WIDTHS) {
            if (width == bitsPerBlock) {
                return true;
            }
        }
        return false;
    }

    private static int widthFor(int paletteSize) {
        for (int width : WIDTHS) {
            if ((1 << width) >= paletteSize) {
                return width;
            }
        }
        throw new IllegalArgumentException("palette of " + paletteSize + " does not fit any width");
    }
}
