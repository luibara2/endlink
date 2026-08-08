package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.PlayerListGuard;
import org.cloudburstmc.protocol.bedrock.codec.v800.serializer.PlayerListSerializer_v800;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.Arrays;

public class PlayerListSerializer_v2168 extends PlayerListSerializer_v800 {

    public static final PlayerListSerializer_v2168 INSTANCE = new PlayerListSerializer_v2168();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerListPacket packet) {
        VarInts.writeUnsignedInt(buffer, packet.getEntries().size());

        for (PlayerListPacket.Entry entry : packet.getEntries()) {
            // Local delta: fall back to the packet-level action instead of upstream's outright
            // NullPointerException. 1.26.40 moved the action from the packet to each entry, so
            // anything that populated the old model leaves every entry's action null:
            //
            //  - a PlayerList decoded from a 1.26.30 backend. PlayerListSerializer_v390.deserialize
            //    sets packet.action and nothing on the entries, so relaying a player list to a
            //    1.26.40 client would throw on the first entry — and that fires on every join.
            //  - a PlayerList the proxy builds itself, e.g. the REMOVE sent on a backend switch,
            //    which sets only the packet-level action. That path breaks even at matching versions.
            //
            // The two carry the same meaning: pre-1.26.40 a player list was all-add or all-remove,
            // so the packet action *is* each entry's action. Only a packet with neither is a real bug.
            PlayerListPacket.Action action = entry.getAction() == null ? packet.getAction() : entry.getAction();
            if (action == null) {
                throw new NullPointerException("PlayerListPacket has no action, on the packet or on entry " + entry.getUuid());
            }

            VarInts.writeUnsignedInt(buffer, action == PlayerListPacket.Action.ADD ? 1 : 0);
            buffer.writeByte(action.ordinal());

            if (action == PlayerListPacket.Action.ADD) {
                this.writeEntryBase(buffer, helper, entry);
            } else {
                helper.writeUuid(buffer, entry.getUuid());
            }
        }
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerListPacket packet) {
        // Repair the body before reading it. PlayerList is a *broadcast*, so one malformed entry on
        // a backend disconnects every player on it, not just whoever caused it — raw-forwarding the
        // bytes intact, which is the right answer for most undecodable packets, is exactly wrong
        // here because the bytes are what the client rejects. Repairing on read also means the
        // ordinary relay re-encodes a clean packet with no further special-casing. PlayerListGuard
        // documents the two shapes that are repaired and why nothing else is guessed at.
        ByteBuf repaired = guard(buffer, helper);
        try {
            readEntries(repaired, helper, packet);
        } finally {
            if (repaired != buffer) {
                repaired.release();
            }
        }
    }

    /**
     * {@code buffer} itself when the body already parses, otherwise a repaired copy the caller owns
     * and this method has consumed {@code buffer} to produce. Never throws.
     */
    private ByteBuf guard(ByteBuf buffer, BedrockCodecHelper helper) {
        byte[] body = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), body);

        byte[] replacement = PlayerListGuard.upgradeLegacyRemoval(body);
        if (replacement == null) {
            replacement = rebuild(body, helper);
        }
        if (replacement == null) {
            return buffer;
        }
        buffer.skipBytes(body.length);
        return Unpooled.wrappedBuffer(replacement);
    }

    /**
     * Walks the entries with this class's own reads, so entry boundaries can never drift from what
     * {@link #readEntries} will do. Returns a corrected body, or null when none is needed.
     */
    private byte[] rebuild(byte[] body, BedrockCodecHelper helper) {
        ByteBuf scratch = Unpooled.wrappedBuffer(body);
        try {
            long declared = VarInts.readUnsignedInt(scratch);
            if (declared < 0 || declared > PlayerListGuard.MAX_ENTRIES) {
                return PlayerListGuard.writeVaruint(0);
            }

            int entriesStart = scratch.readerIndex();
            int complete = 0;
            int completeEnd = entriesStart;
            for (int i = 0; i < declared; i++) {
                int entryStart = scratch.readerIndex();
                try {
                    skipEntry(scratch, helper);
                } catch (Exception failure) {
                    return salvage(body, helper, entriesStart, complete, completeEnd, entryStart, failure);
                }
                complete++;
                completeEnd = scratch.readerIndex();
            }
            if (scratch.isReadable()) {
                // Every declared entry read, but bytes follow that nothing accounts for. Keep the
                // entries and drop the tail rather than hand the client a body it cannot finish.
                return concat(PlayerListGuard.writeVaruint(complete),
                        Arrays.copyOfRange(body, entriesStart, completeEnd));
            }
            return null;
        } catch (Exception malformedCount) {
            return PlayerListGuard.writeVaruint(0);
        } finally {
            scratch.release();
        }
    }

    private byte[] salvage(byte[] body, BedrockCodecHelper helper, int entriesStart, int complete,
                           int completeEnd, int entryStart, Exception failure) {
        byte[] kept = Arrays.copyOfRange(body, entriesStart, completeEnd);
        byte[] partial = Arrays.copyOfRange(body, entryStart, body.length);
        PlayerListGuard.TruncatedAdd truncated = PlayerListGuard.readTruncatedAdd(partial);

        if (truncated != null) {
            byte[] entry = concat(partial, PlayerListGuard.defaultSteveAddTail(truncated.uuid));
            byte[] rebuilt = concat(PlayerListGuard.writeVaruint(complete + 1), concat(kept, entry));
            // Only ship the repair if this same codec can read it back. A synthesised tail that does
            // not parse would replace one disconnecting packet with another.
            if (parses(rebuilt, helper)) {
                // Silent on success. This fires on every broadcast carrying the malformed identity,
                // which is once per join per recipient, and a line per repair would bury the case
                // below — the one where a player actually loses an entry.
                return rebuilt;
            }
        }

        warnDropped(complete, failure);
        return concat(PlayerListGuard.writeVaruint(complete), kept);
    }

    private boolean parses(byte[] body, BedrockCodecHelper helper) {
        ByteBuf check = Unpooled.wrappedBuffer(body);
        try {
            long declared = VarInts.readUnsignedInt(check);
            for (int i = 0; i < declared; i++) {
                skipEntry(check, helper);
            }
            return !check.isReadable();
        } catch (Exception ignored) {
            return false;
        } finally {
            check.release();
        }
    }

    /** Consumes exactly one entry, with the same reads {@link #readEntries} performs. */
    private void skipEntry(ByteBuf buffer, BedrockCodecHelper helper) {
        boolean add = VarInts.readUnsignedInt(buffer) == 1;
        buffer.readUnsignedByte();
        if (add) {
            this.readEntryBase(buffer, helper);
        } else {
            helper.readUuid(buffer);
        }
    }

    /**
     * The one thing worth saying out loud: an entry could not be repaired and a player has gone
     * missing from someone's list. Rate-limited because a backend that emits one malformed entry
     * emits it to every recipient, repeatedly.
     */
    private static void warnDropped(int complete, Exception failure) {
        long now = System.nanoTime();
        long last = LAST_DROP_WARNING.get();
        if (now - last < DROP_WARNING_INTERVAL_NANOS && last != 0) {
            return;
        }
        if (!LAST_DROP_WARNING.compareAndSet(last, now)) {
            return;
        }
        System.err.printf("PlayerList guard: dropped an unrepairable entry, kept %d complete entr%s (%s).%n",
                complete, complete == 1 ? "y" : "ies", failure.getMessage());
    }

    private static final long DROP_WARNING_INTERVAL_NANOS = 30_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_DROP_WARNING =
            new java.util.concurrent.atomic.AtomicLong();

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private void readEntries(ByteBuf buffer, BedrockCodecHelper helper, PlayerListPacket packet) {
        int length = VarInts.readUnsignedInt(buffer);

        for (int i = 0; i < length; i++) {
            PlayerListPacket.Action action = VarInts.readUnsignedInt(buffer) == 1 ? PlayerListPacket.Action.ADD : PlayerListPacket.Action.REMOVE;
            buffer.readUnsignedByte();

            PlayerListPacket.Entry entry;
            if (action == PlayerListPacket.Action.ADD) {
                entry = this.readEntryBase(buffer, helper);
            } else {
                entry = new PlayerListPacket.Entry(helper.readUuid(buffer));
            }
            entry.setAction(action);
            packet.getEntries().add(entry);

            // Local delta: keep the packet-level action populated too. 1.26.40 moved the action onto
            // each entry and upstream stops there, which leaves packet.getAction() null for anything
            // decoded from a 1.26.40 peer — and consumers still read it. In this proxy alone,
            // ClientWorldState.trackPlayerList and the PlayerList XUID backfill both branch on it,
            // so leaving it null silently stops tracking player-list entries and strands ghost
            // players in the client's list after a backend switch. Nothing throws; the feature just
            // quietly stops.
            //
            // First entry wins. Before 1.26.40 the wire could not express a mixed add/remove batch,
            // so for every packet the old model could represent this is exact; for a genuinely mixed
            // 1.26.40 batch there is no single right answer, and per-entry consumers are unaffected.
            if (packet.getAction() == null) {
                packet.setAction(action);
            }
        }
    }
}
