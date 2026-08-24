package org.endstone.proxy.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line stamping, which is easy to get wrong in exactly one way: a logical line does not arrive in one
 * {@code write} call, so anything that stamps per call rather than per line either stamps the middle
 * of a message or misses the start of one.
 */
class TimestampedLogSinkTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant START = Instant.parse("2026-08-24T01:23:45.678Z");

    /** A clock that stands still until a test moves it. */
    private static final class MovableClock extends Clock {
        private Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final ByteArrayOutputStream file = new ByteArrayOutputStream();
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final TimestampedLogSink sink = new TimestampedLogSink(file, clock);

    private OutputStream stream() {
        return sink.streamFor(console);
    }

    private void write(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream().write(bytes, 0, bytes.length);
    }

    private String logged() {
        return file.toString(StandardCharsets.UTF_8);
    }

    /**
     * {@code logged()} with line endings normalised. The date marker ends with the platform
     * separator, as every {@code printf("...%n")} in the proxy does, so an assertion that spelled
     * out a bare newline would pass on Linux and fail on the machine this is developed on.
     */
    private String loggedNormalised() {
        return logged().replace("\r\n", "\n");
    }

    /** Everything after the date marker the first stamp always emits. */
    private String loggedBody() {
        String text = logged();
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(newline + 1);
    }

    @Test
    void stampsALineWrittenInOneCall() throws IOException {
        write("Player Someone joined the proxy.\n");

        assertEquals("[01:23:45.678] Player Someone joined the proxy.\n", loggedBody());
    }

    /**
     * The case that matters. {@code PrintStream} splits a formatted message across several writes, so
     * a sink that stamped per call would put a timestamp inside this sentence.
     */
    @Test
    void stampsALineOnlyOnceWhenItArrivesInPieces() throws IOException {
        write("Player ");
        clock.advance(Duration.ofMillis(5));
        write("Someone");
        clock.advance(Duration.ofMillis(5));
        write(" joined.\n");

        assertEquals("[01:23:45.678] Player Someone joined.\n", loggedBody());
    }

    @Test
    void stampsEveryLineOfAMultiLineWrite() throws IOException {
        write("first\nsecond\nthird\n");

        assertEquals("""
                [01:23:45.678] first
                [01:23:45.678] second
                [01:23:45.678] third
                """, loggedBody());
    }

    /** A stack trace is the reason multi-line stamping has to work; it is also the hardest to read. */
    @Test
    void stampsEachFrameOfAStackTrace() throws IOException {
        PrintStream stderr = new PrintStream(stream(), true);
        stderr.println("backend pipeline exception:");
        stderr.println("\tat org.example.Thing.method(Thing.java:1)");

        String body = loggedBody();
        assertTrue(body.contains("[01:23:45.678] backend pipeline exception:"), body);
        assertTrue(body.contains("[01:23:45.678] \tat org.example.Thing.method(Thing.java:1)"), body);
    }

    @Test
    void movesTheStampForwardWithTheClock() throws IOException {
        write("first\n");
        clock.advance(Duration.ofSeconds(90));
        write("second\n");

        assertEquals("""
                [01:23:45.678] first
                [01:25:15.678] second
                """, loggedBody());
    }

    /** A blank line spaces output out; a blank line with a timestamp on it does not. */
    @Test
    void leavesABlankLineBlank() throws IOException {
        write("before\n\nafter\n");

        assertEquals("""
                [01:23:45.678] before

                [01:23:45.678] after
                """, loggedBody());
    }

    @Test
    void handlesWindowsLineEndings() throws IOException {
        write("first\r\nsecond\r\n");

        assertEquals("[01:23:45.678] first\r\n[01:23:45.678] second\r\n", loggedBody());
    }

    @Test
    void stampsAfterASingleByteWrite() throws IOException {
        OutputStream stream = stream();
        for (byte b : "hi\n".getBytes(StandardCharsets.UTF_8)) {
            stream.write(b);
        }

        assertEquals("[01:23:45.678] hi\n", loggedBody());
    }

    /**
     * A trailing fragment with no newline is stamped when it starts, not when it ends — otherwise a
     * message that never gets flushed with a newline would never be stamped at all.
     */
    @Test
    void stampsAnUnterminatedLineWhenItStarts() throws IOException {
        write("no newline yet");

        assertEquals("[01:23:45.678] no newline yet", loggedBody());
    }

    @Test
    void announcesTheDateOnTheFirstLineAndWhenItChanges() throws IOException {
        write("before midnight\n");
        clock.advance(Duration.ofHours(23));
        write("after midnight\n");

        assertEquals("""
                --- 2026-08-24 ---
                [01:23:45.678] before midnight
                --- 2026-08-25 ---
                [00:23:45.678] after midnight
                """, loggedNormalised());
    }

    @Test
    void announcesTheDateOnlyOncePerDay() throws IOException {
        write("one\n");
        clock.advance(Duration.ofHours(1));
        write("two\n");

        assertEquals(1, logged().lines().filter(line -> line.startsWith("--- ")).count());
    }

    /**
     * stdout and stderr share the sink, so a line from one cannot land inside a line from the other.
     * Real logs from the unshared version contain messages spliced together a few bytes at a time.
     */
    @Test
    void keepsTwoStreamsOnTheirOwnLines() throws IOException {
        ByteArrayOutputStream otherConsole = new ByteArrayOutputStream();
        OutputStream out = sink.streamFor(console);
        OutputStream err = sink.streamFor(otherConsole);

        byte[] first = "from stdout\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "from stderr\n".getBytes(StandardCharsets.UTF_8);
        out.write(first, 0, first.length);
        err.write(second, 0, second.length);

        assertEquals("""
                [01:23:45.678] from stdout
                [01:23:45.678] from stderr
                """, loggedBody());
    }

    /** Each stream's own console still sees only its own bytes, stamp included. */
    @Test
    void mirrorsStampedOutputToTheConsole() throws IOException {
        ByteArrayOutputStream otherConsole = new ByteArrayOutputStream();
        byte[] bytes = "to stdout\n".getBytes(StandardCharsets.UTF_8);
        sink.streamFor(console).write(bytes, 0, bytes.length);

        String consoleText = console.toString(StandardCharsets.UTF_8);
        assertTrue(consoleText.endsWith("[01:23:45.678] to stdout\n"), consoleText);
        assertEquals("", otherConsole.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writesNothingForAnEmptyWrite() throws IOException {
        stream().write(new byte[0], 0, 0);

        assertEquals("", logged());
        assertFalse(logged().contains("["));
    }
}
