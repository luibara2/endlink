package org.endstone.proxy.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

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
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream teeOut = new PrintStream(new TeeOutputStream(originalOut, file), true);
        PrintStream teeErr = new PrintStream(new TeeOutputStream(originalErr, file), true);
        System.setOut(teeOut);
        System.setErr(teeErr);

        System.out.printf("Writing proxy log to %s.%n", logPath.toAbsolutePath().normalize());
        System.out.printf("Log started at %s.%n", Instant.now());
        return logPath;
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        private TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            file.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            console.flush();
            file.flush();
        }
    }
}
