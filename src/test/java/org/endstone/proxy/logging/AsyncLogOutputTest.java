package org.endstone.proxy.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Log output that a slow reader cannot turn into a stalled network thread.
 */
class AsyncLogOutputTest {

    @Test
    void everythingArrivesInOrder() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        AsyncLogOutput output = new AsyncLogOutput(target, "test", AsyncLogOutput.DEFAULT_LIMIT);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            String line = "line " + i + "\n";
            expected.append(line);
            output.write(line.getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(output.drain(5, TimeUnit.SECONDS));
        assertEquals(expected.toString(), target.toString(StandardCharsets.UTF_8));
    }

    /**
     * MCSManager's console pty stopped being read fast enough, and every thread that printed a line
     * waited on it - including the one relaying a backend to its players.
     */
    @Test
    void aStuckTargetNeverBlocksTheWriterAndWhatDidNotFitIsCounted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        OutputStream stuck = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                written.write(buffer, offset, length);
            }
        };
        AsyncLogOutput output = new AsyncLogOutput(stuck, "test", 1024);

        long start = System.nanoTime();
        byte[] line = "0123456789abcdef\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 10_000; i++) {
            output.write(line);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1000, "writing to a stuck target took " + millis + " ms");

        release.countDown();
        assertTrue(output.drain(5, TimeUnit.SECONDS));
        String out = written.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("bytes of output were dropped here"), out);
        assertTrue(out.length() < 10_000 * line.length);
    }
}
