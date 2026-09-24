package org.endstone.proxy.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

public final class ProxyLogFile {
    private ProxyLogFile() {
    }

    public static Path install(Path configPath) throws IOException {
        Path parent = configPath.toAbsolutePath().normalize().getParent();
        Path logPath = (parent == null ? Path.of(".") : parent).resolve("logs/latest.log");
        Files.createDirectories(logPath.getParent());

        OutputStream file = Files.newOutputStream(
                logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        // Written from threads of their own: a line printed on a network event loop must never wait
        // on a slow console. See AsyncLogOutput.
        AsyncLogOutput asyncFile = new AsyncLogOutput(file, "file", AsyncLogOutput.DEFAULT_LIMIT);
        AsyncLogOutput originalOut = new AsyncLogOutput(System.out, "stdout", AsyncLogOutput.DEFAULT_LIMIT);
        AsyncLogOutput originalErr = new AsyncLogOutput(System.err, "stderr", AsyncLogOutput.DEFAULT_LIMIT);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (AsyncLogOutput output : new AsyncLogOutput[]{asyncFile, originalOut, originalErr}) {
                try {
                    output.drain(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "log-drain"));

        // One sink for both streams: they share a file, so they have to share the lock and the
        // are-we-at-a-line-start state. See TimestampedLogSink.
        Clock clock = Clock.systemDefaultZone();
        TimestampedLogSink sink = new TimestampedLogSink(asyncFile, clock);
        System.setOut(new PrintStream(sink.streamFor(originalOut), true));
        System.setErr(new PrintStream(sink.streamFor(originalErr), true));

        System.out.printf("Writing proxy log to %s.%n", logPath.toAbsolutePath().normalize());
        // The absolute instant stays, because it is the one value that is unambiguous no matter where
        // the log is read. The per-line stamps are local time and carry no zone, so name it here once.
        ZoneId zone = clock.getZone();
        System.out.printf(
                "Log started at %s. Lines below are stamped [HH:mm:ss.SSS] in %s.%n",
                Instant.now(),
                zone
        );
        return logPath;
    }
}
