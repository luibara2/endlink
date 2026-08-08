package org.endstone.proxy.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * An append-only file holding only protocol faults, so they can be found without reading the relay.
 *
 * <p>The ordinary log is a running commentary on a working proxy; a fault that drops a player is a
 * handful of lines somewhere inside it, usually noticed only because someone complained. This file
 * has nothing else in it, so "has anything gone wrong today" is answered by its size.</p>
 *
 * <p>Every entry is one line and self-contained — timestamp, player, backend, and the decoded
 * violation including the packet id and the schema member BDS named. That is deliberately enough to
 * act on without going back to the relay log, because the relay log rolls and this does not.</p>
 */
public final class ProtocolFaultLog {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path file;
    private boolean unwritable;

    public ProtocolFaultLog(Path file) {
        this.file = file;
    }

    /** A log that discards everything, for a proxy that has the file configured empty. */
    public static ProtocolFaultLog disabled() {
        return new ProtocolFaultLog(null);
    }

    public boolean enabled() {
        return this.file != null;
    }

    public Path file() {
        return this.file;
    }

    /**
     * Appends one fault. Failing to write must never take the connection down with it, so an I/O
     * problem is reported once and then the log goes quiet rather than reporting it per fault.
     */
    public synchronized void record(ProtocolFault fault) {
        if (this.file == null || this.unwritable) {
            return;
        }
        String line = TIMESTAMP.format(Instant.now()) + " " + fault.describe() + System.lineSeparator();
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(this.file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            this.unwritable = true;
            System.err.printf("Cannot write the protocol fault log at %s, disabling it: %s.%n",
                    this.file, failure);
        }
    }
}
