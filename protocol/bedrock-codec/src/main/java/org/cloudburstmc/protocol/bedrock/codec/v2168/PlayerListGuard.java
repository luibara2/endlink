package org.cloudburstmc.protocol.bedrock.codec.v2168;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Byte-level repairs for the malformed 1.26.40 player packets a backend can broadcast.
 *
 * <p>A port of the {@code endstone-playerlist-guard} plugin's wire logic, so that a backend without
 * that plugin installed is covered too. The plugin stays the better place to fix this where it is
 * installed: it can tell recipients apart and order the entity spawn against the list entry. The
 * proxy sees only the broadcast, so it repairs the bytes and stops there.</p>
 *
 * <p><b>Why raw-forwarding is not good enough here.</b> An undecodable packet normally reaches the
 * client byte-exact and is harmless. These bytes are what the client rejects, and PlayerList is a
 * <em>broadcast</em> — one malformed identity disconnects every player on that backend, not just
 * whoever caused it.</p>
 *
 * <p>Two shapes are repaired, both narrowly:</p>
 * <ul>
 *   <li><b>BDS's truncated ADD.</b> The entry stops immediately after {@code platformChatId}, with
 *       the build platform and the entire skin missing. Only that exact shape is completed — if even
 *       one byte exists where the build platform belongs, the repair declines and the entry is
 *       dropped instead.</li>
 *   <li><b>A pre-1.26.40 removal</b>, {@code REMOVE | count | uuid...}, still emitted by plugins
 *       written against the old layout. It is exactly {@code 2 + 16 * count} bytes, so it can be
 *       upgraded without guessing. A 1.26.40 client otherwise reads those bytes as an ADD and
 *       disconnects looking for a skin.</li>
 * </ul>
 *
 * <p>Anything else malformed is fail-closed: complete leading entries are kept, the remainder is
 * dropped, and the result is always a structurally valid packet.</p>
 */
public final class PlayerListGuard {

    public static final int ACTION_ADD = 0;
    public static final int ACTION_REMOVE = 1;
    public static final int UUID_BYTES = 16;
    public static final int MAX_ENTRIES = 4096;

    /** {@code BuildPlatform.UNKNOWN}, which is what Geyser uses for synthetic player entities. */
    public static final byte[] UNKNOWN_BUILD_PLATFORM = {-1, -1, -1, -1};

    private static final byte[][] KNOWN_BUILD_PLATFORMS = knownBuildPlatforms();

    // Mojang's wide/default Steve texture as Bedrock RGBA, and Geyser's humanoid geometry. Embedded
    // compressed purely for source size. No online player's skin is read or copied - synthesising a
    // fixed skin is what Geyser does for a player it cannot resolve.
    private static final String STEVE_RGBA_ZLIB_B64 =
            "eNrtmr1rFFEUxRdUkICggiCCVokEbZQYggGzmkJI7JQUaYJgE7SzUEwTxCaptNAqbWxsUljY"
            + "5E/I/zTmjnuGM2fum7efycz6HhzezNs7s/t7976P2TudTn25f+dKZlqYvZbxMc4/v3pcq07L"
            + "C3i7925kfKz8H14slDRN/Myr9bfNboUdss+mxf8c93z+P/DzuNdxYIyh+J8GfvY3x4Kxm8Cp"
            + "8561fd1amQp+Hf8sYzTeUD0t45/9zvEA1rW5qyWhvS3jmxlnb16urPWId4j7gK/R8aJ7B7az"
            + "uknzO36fCRx8zOxa47rQfgnzJt/f2po2vr09zsGbtez3p63sz5d3ef3r/Wbe9mjueuZdj3Od"
            + "M7iP0dYE/2vssh+N03iNG+yQfeYxhcaDNy6atr/Bns7YjME4rbx8sl8w27EVOzcba7NrvHvx"
            + "2ND+aQK/+gc8LPj77dMHudj/LDB58ynHA5+fNz9+h9XwO9bu769Xin2MrWkfVxdzrd6eKdlg"
            + "rUMc8D2VX8/Pmx97NWbXPrDamK0PUIMZ/cPX4n68H7x7a6akpj4fLj/cz1hLS0u55ufnc0Vv"
            + "cHJSjA3uV2uzz/T+quj9j46yQoeHpfGZf8eY+cGNfuiHn9m5DybBz3PROJ63xuH/SfPPHB9X"
            + "+Jvk/4nG/ymzF//D+v/Z4kFmsu+2+vnyz0JoQw3x5xd//CiJ/cLrJ353yN5UamdO4i2Ett3d"
            + "svSaPvjR9/3w8+cm+62X9vZyFTw9TsSktnv2JUZtC7GDf3v7n8DPdn3ye3waGx6/cVzY2cll"
            + "x8X39mptU3vtA+2rCg/1R/65x8/XTICfj8ECxXjUXsdz6Xr7TONbBXbuA9aA/DoetA90vlKe"
            + "kr+Fn33v8VfGfW9+gzCXcu2JrxnG/3qs/cHnGs8FP813zF8X/17s6PMm1zzv67NKv+thbHz3"
            + "M/5ZGr/sU7X17PVaj4n7weMvrTt9rn/D8ut8nvtf41nWN7Z3+cne49fnTx4Pg/pfY1vnvro5"
            + "wQQeZgO3FwuenbfGwcaL+dDz9zD86+vrmSm2L4OdSvcz2hc6v1X2P8LM7CaN+VgfDMpve9pu"
            + "t1ti2tjYqHCajdracWht4LhBP4BLbXjssI3FYYhfx7qO/0H4IeNRPnB7dnZctz/mNQT+DM0n"
            + "3FfcrnN9aO7TdQ82nVRSSSWVVFJJpXXF/usMqfK/UG+vWbJJJZVUUkkllVRSmXAZOX8qeR79"
            + "z6dt/APnz4Wf88fjyG+3xf/Y3447v98q/58es/+bGP+h9we83KGXXw3m70P57gHz+2fN7zGG"
            + "3i8wBfPXdX0wQH7/rPnxv30ohx7jd9/9YF7tjxb4X/nZLpq/jyny/B/9/2AM/KO8PxDK34fy"
            + "Gprva4L/Q7nR0DskbK95nLpcv6c2xj+rLocfy/s3kT80/4XeH/DivC7f3zT/e/lebx8QGiPe"
            + "+K7ze9P4R31/IMYemwPOm3/U9wdi+fvYXNAE/lHeHwitdTrPhfL/o/7+v607PNM=";

    private static final String HUMANOID_GEOMETRY_ZLIB_B64 =
            "eNrtl02P2jAQhv+LzybKFzTl1mOlHirtEa2QSRzibRIjx2G7Rfz3joGwTuIEA6vSwwqBktgz"
            + "8/gdxzPsUMpFQeRyS0XFeInmyHM833ERRgUraSxIKudrygsqxRuaL3ZoxUtaHa5KUlAwWPHk"
            + "DaZviKClhPtXwiqpHrAth/uF67jYD+EHLp73+GxnmqdCn+fF9eoUiQu2ZgC3mExhPIJvAHMw"
            + "qtgf8LTwwHCGPXhQb5UjDPZwc4oTkw3V+RreLt7RpeCSyIMSR6DIbYhgMKFVLNjmOL5DLAGP"
            + "LGVUgNtGJOcUT9LfshZ0mVG2ziBQ4L8/e2WJzNB8Fu7VOnuKCs67wrgXdAkb8Sa+rkwED3HY"
            + "CKNEmunS3J26xtBAfDGVYeNcXenI8BlIZUZJYpPKDq7VDjjkv83KyjQnEjy4zhRfSQ7pbqOT"
            + "lmDNSobIDYINpDhspRjChpEeN6ep/CaKkeWrV8o3J0sTwNcVsEIJIwPKU07ptpUMDXAISLP/"
            + "Lmkxbj1TbMqFN7L3IqsFuJ3XRag3eVzMyQ1qWtMEfo+mr6cOOQiV85hILip1iuWwFZcZzxM1"
            + "E06IKZyk+3aUruqmGGdLs+Ku4x0OsbElwvnU3zE/6HrkpPGcr0MnzaDcNiiugeQnKWXV3Xwn"
            + "vMs47/EDmHQ5vmHrjUsxuUULOxbDxuuJoRPeQ2RbydpMLyT+RaV1bbCt5VldkJKzxInrSvLC"
            + "UNZnoaGsY7RlFVvldLnidZlU59l+b4SnaUXlMeNYrbczfvLp3dQpfHS5/gcdxoM6hf+16H8e"
            + "Fp9lZKQd9GCPtuMHH9IOKr9XtoOXUO5rB3Wg69pB7IXOWGPyxQr+5lbwOiWtae5rBXWosVaw"
            + "Ue6WZtCk+mOL/rV/CCfBHa3CU84e2y487/8CNztQow==";

    private PlayerListGuard() {
    }

    /** Thrown while walking a body that does not parse. Never escapes the serializer. */
    public static final class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }

    /** A cursor over one packet body. Every read is bounds-checked into {@link Malformed}. */
    public static final class Cursor {
        private final byte[] data;
        private int offset;

        public Cursor(byte[] data) {
            this.data = data;
        }

        public int offset() {
            return this.offset;
        }

        public int remaining() {
            return this.data.length - this.offset;
        }

        public byte[] take(int size, String field) {
            if (size < 0 || size > remaining()) {
                throw new Malformed(field + ": need " + size + " bytes at offset " + this.offset
                        + ", only " + remaining() + " remain");
            }
            byte[] slice = Arrays.copyOfRange(this.data, this.offset, this.offset + size);
            this.offset += size;
            return slice;
        }

        public int u8(String field) {
            return take(1, field)[0] & 0xFF;
        }

        public long varuint(String field, int maxBytes) {
            long value = 0;
            for (int index = 0; index < maxBytes; index++) {
                int b = u8(field);
                value |= (long) (b & 0x7F) << (index * 7);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new Malformed(field + ": varuint exceeds " + maxBytes + " bytes");
        }

        public long varuint(String field) {
            return varuint(field, 5);
        }

        public byte[] string(String field) {
            long length = varuint(field + " length");
            if (length > Integer.MAX_VALUE) {
                throw new Malformed(field + ": implausible length " + length);
            }
            return take((int) length, field);
        }
    }

    public static byte[] writeVaruint(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varuint cannot be negative");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        int remaining = value;
        while (true) {
            int b = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    /**
     * Upgrade a pre-1.26.40 {@code REMOVE | count | uuid...} body, or null when the bytes are not
     * unambiguously that shape. The exact-length check is what makes this safe without guessing.
     */
    public static byte[] upgradeLegacyRemoval(byte[] body) {
        if (body.length == 0 || (body[0] & 0xFF) != ACTION_REMOVE) {
            return null;
        }
        Cursor cursor = new Cursor(body);
        long count;
        try {
            cursor.u8("legacy action");
            count = cursor.varuint("legacy entry count");
        } catch (Malformed ignored) {
            return null;
        }
        if (count == 0 || count > MAX_ENTRIES || cursor.remaining() != count * UUID_BYTES) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, writeVaruint((int) count));
        for (int i = 0; i < count; i++) {
            write(out, writeVaruint(0)); // case index: RemoveEntry
            out.write(ACTION_REMOVE);    // the const BDS calls "Action"
            write(out, cursor.take(UUID_BYTES, "legacy entry " + i + " uuid"));
        }
        return out.toByteArray();
    }

    /**
     * The identity BDS emitted before stopping, or null when these trailing bytes are not exactly
     * its truncation. Strict by design: the partial entry must end precisely at EOF.
     */
    public static TruncatedAdd readTruncatedAdd(byte[] partial) {
        Cursor cursor = new Cursor(partial);
        byte[] uuid;
        byte[] name;
        byte[] xuid;
        try {
            long marker = cursor.varuint("add marker");
            int action = cursor.u8("action");
            if (marker != 1 || action != ACTION_ADD) {
                return null;
            }
            uuid = cursor.take(UUID_BYTES, "uuid");
            cursor.varuint("actor unique id", 10);
            name = cursor.string("name");
            xuid = cursor.string("xuid");
            cursor.string("platform chat id");
        } catch (Malformed ignored) {
            return null;
        }
        return cursor.remaining() == 0 ? new TruncatedAdd(uuid, name, xuid) : null;
    }

    /** The UUID, name and XUID salvaged from a truncated ADD entry. */
    public static final class TruncatedAdd {
        public final byte[] uuid;
        public final String name;
        public final String xuid;

        TruncatedAdd(byte[] uuid, byte[] name, byte[] xuid) {
            this.uuid = uuid;
            this.name = new String(name, StandardCharsets.UTF_8);
            this.xuid = new String(xuid, StandardCharsets.UTF_8);
        }
    }

    /**
     * The build-platform and skin tail missing from a truncated ADD, using the fixed Steve assets.
     * Appending it to the partial entry yields a complete, parseable 1.26.40 entry.
     */
    public static byte[] defaultSteveAddTail(byte[] subjectUuid) {
        if (subjectUuid.length != UUID_BYTES) {
            throw new IllegalArgumentException("subject UUID must be 16 bytes");
        }
        byte[] skinId = ("playerlist_guard:steve:" + hex(subjectUuid)).getBytes(StandardCharsets.UTF_8);
        byte[] resourcePatch =
                "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, UNKNOWN_BUILD_PLATFORM);
        writeString(out, skinId);
        writeString(out, new byte[0]);                  // playfab id
        writeString(out, resourcePatch);
        writeImage(out, 64, 64, stevePixels());
        write(out, writeVaruint(0));                    // animations
        writeImage(out, 0, 0, new byte[0]);             // cape
        writeString(out, humanoidGeometry());
        writeString(out, "1.26.40".getBytes(StandardCharsets.UTF_8));
        writeString(out, new byte[0]);                  // animation data
        writeString(out, new byte[0]);                  // cape id
        writeString(out, skinId);                       // full id
        out.write(1);                                   // wide arms
        write(out, new byte[]{0, 0, 0, 0});             // transparent skin colour
        write(out, writeVaruint(0));                    // persona pieces
        write(out, writeVaruint(0));                    // persona tints
        write(out, new byte[]{1, 0, 0, 0, 1});          // premium .. overrides appearance
        writeString(out, "true".getBytes(StandardCharsets.UTF_8));
        writeString(out, new byte[0]);                  // profile hash
        write(out, new byte[]{0, 0, 0});                // teacher, host, sub-client
        write(out, new byte[]{-1, -1, -1, -1});         // white player-list colour
        return out.toByteArray();
    }

    /**
     * Append {@code BuildPlatform.UNKNOWN} to an AddPlayer body that stops before its final field.
     *
     * <p>This is the other half of the same BDS truncation, and repairing the player list without it
     * is not enough — the plugin shipped three versions that still disconnected clients before this
     * was found. Idempotent: a body already ending in a defined platform is returned unchanged.</p>
     */
    public static byte[] completeAddPlayerBuildPlatform(byte[] body) {
        if (body.length >= UUID_BYTES + 4) {
            byte[] last4 = Arrays.copyOfRange(body, body.length - 4, body.length);
            for (byte[] known : KNOWN_BUILD_PLATFORMS) {
                if (Arrays.equals(known, last4)) {
                    return body;
                }
            }
        }
        byte[] completed = Arrays.copyOf(body, body.length + 4);
        System.arraycopy(UNKNOWN_BUILD_PLATFORM, 0, completed, body.length, 4);
        return completed;
    }

    private static byte[][] knownBuildPlatforms() {
        int[] values = {-1, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        byte[][] encoded = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            encoded[i] = intLE(values[i]);
        }
        return encoded;
    }

    private static volatile byte[] stevePixels;
    private static volatile byte[] humanoidGeometry;

    private static byte[] stevePixels() {
        byte[] pixels = stevePixels;
        if (pixels == null) {
            pixels = inflate(STEVE_RGBA_ZLIB_B64);
            if (pixels.length != 64 * 64 * 4) {
                throw new IllegalStateException("embedded Steve texture has an invalid size");
            }
            stevePixels = pixels;
        }
        return pixels;
    }

    private static byte[] humanoidGeometry() {
        byte[] geometry = humanoidGeometry;
        if (geometry == null) {
            geometry = inflate(HUMANOID_GEOMETRY_ZLIB_B64);
            if (!new String(geometry, StandardCharsets.UTF_8).contains("\"geometry.humanoid.custom\"")) {
                throw new IllegalStateException("embedded humanoid geometry is invalid");
            }
            humanoidGeometry = geometry;
        }
        return geometry;
    }

    private static byte[] inflate(String base64) {
        byte[] compressed = Base64.getDecoder().decode(base64);
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 8);
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.finished()) {
                int read = inflater.inflate(chunk);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(chunk, 0, read);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("embedded asset is not valid zlib", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] intLE(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static void writeString(ByteArrayOutputStream out, byte[] value) {
        write(out, writeVaruint(value.length));
        write(out, value);
    }

    private static void writeImage(ByteArrayOutputStream out, int width, int height, byte[] pixels) {
        write(out, intLE(width));
        write(out, intLE(height));
        write(out, writeVaruint(pixels.length));
        write(out, pixels);
    }

    private static void write(ByteArrayOutputStream out, byte[] value) {
        out.write(value, 0, value.length);
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
