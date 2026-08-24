package org.endstone.proxy.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stamps every line the proxy writes with the time it was written.
 *
 * <p>Without this a proxy log is a list of things that happened in some order and no other
 * information. That is enough right up until the question is "did these fourteen players drop at the
 * same moment, or over ten minutes?" — which is the question that separates a broadcast packet
 * corrupting for everyone at once from a backend slowly falling over, and the log could not answer
 * it. Milliseconds are there for the same reason: the interesting bursts happen inside one second.
 *
 * <p><strong>One sink is shared by stdout and stderr, and that is the point.</strong> They are two
 * streams writing to one file, so "am I at the start of a line?" is a property of the file, not of
 * either stream. Sharing it also serialises them against each other, which the previous unsynchronised
 * pair did not: real logs contain lines like
 * {@code "Backend Backend afkafk died under  disconnected player ..."}, two messages spliced together
 * a few bytes at a time. A timestamp injected into the middle of that would be worse than no timestamp
 * at all, so the lock is a prerequisite for stamping rather than a separate tidy-up.
 *
 * <p>Deliberately not a logging framework. Every line the proxy prints goes through
 * {@code System.out}/{@code System.err}, including the ones from vendored libraries that were never
 * going to be converted, and wrapping the streams is the only thing that catches all of them.
 */
final class TimestampedLogSink {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final OutputStream file;
    private final Clock clock;

    /**
     * Whether the next byte begins a line. Starts true so the first byte of the run is stamped, and
     * is the only piece of state that makes a prefix land in the right place when a single logical
     * line arrives as several {@code write} calls — which it routinely does, because
     * {@code PrintStream} is free to split a formatted message however it likes.
     */
    private boolean atLineStart = true;

    /** The date the last stamp fell on, so a run spanning midnight says so once rather than never. */
    private LocalDate stampedDate;

    TimestampedLogSink(OutputStream file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Writes {@code len} bytes to {@code console} and to the log file, inserting a stamp at the start
     * of each line.
     *
     * @param console where this particular stream's bytes also go — stdout's console for the stdout
     *                façade, stderr's for stderr's. The stamp goes to both, so a line looks the same
     *                on screen as it does in the file and one can be grepped for in the other.
     */
    synchronized void write(OutputStream console, byte[] buffer, int offset, int length) throws IOException {
        int index = offset;
        int end = offset + length;
        while (index < end) {
            // One run per line, newline included, so the whole line reaches both streams in a single
            // write rather than a byte at a time.
            int runEnd = index;
            while (runEnd < end && buffer[runEnd] != '\n') {
                runEnd++;
            }
            boolean endsLine = runEnd < end;
            if (endsLine) {
                runEnd++;
            }

            if (atLineStart && !isBlank(buffer, index, runEnd)) {
                writePrefix(console);
            }
            console.write(buffer, index, runEnd - index);
            file.write(buffer, index, runEnd - index);

            atLineStart = endsLine;
            index = runEnd;
        }
    }

    synchronized void write(OutputStream console, int b) throws IOException {
        write(console, new byte[]{(byte) b}, 0, 1);
    }

    synchronized void flush(OutputStream console) throws IOException {
        console.flush();
        file.flush();
    }

    /**
     * A line with nothing on it but its terminator. Left unstamped: a blank line is used to space
     * output out, and a blank line carrying a timestamp is no longer blank.
     */
    private static boolean isBlank(byte[] buffer, int from, int to) {
        int length = to - from;
        if (length == 1) {
            return buffer[from] == '\n';
        }
        return length == 2 && buffer[from] == '\r' && buffer[from + 1] == '\n';
    }

    private void writePrefix(OutputStream console) throws IOException {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate date = now.toLocalDate();
        if (!date.equals(stampedDate)) {
            // The stamp carries no date, to keep it to thirteen characters on lines that are already
            // long. That is fine for a single session and wrong for a proxy that has been up for four
            // days, so the date is announced whenever it changes — including the first time, which
            // dates the log from its first line.
            stampedDate = date;
            emit(console, "--- " + date + " ---" + System.lineSeparator());
        }
        emit(console, "[" + TIME.format(now) + "] ");
    }

    private void emit(OutputStream console, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        console.write(bytes);
        file.write(bytes);
    }

    /** An {@link OutputStream} façade that routes one console stream through this shared sink. */
    OutputStream streamFor(OutputStream console) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                TimestampedLogSink.this.write(console, b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                TimestampedLogSink.this.write(console, b, off, len);
            }

            @Override
            public void flush() throws IOException {
                TimestampedLogSink.this.flush(console);
            }
        };
    }
}
