package org.endstone.proxy.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Hands log bytes to a thread of their own, so writing a line never makes the caller wait.
 *
 * <p>Most proxy lines are printed from network event loops. Writing them in place meant a slow
 * reader stalled gameplay: MCSManager reads a proxy's console through a pseudo-terminal, and with
 * packet logging on it could not keep up with a busy PowerNukkitX backend. The pty filled, the event
 * loop that printed the line blocked on it, its RakNet acks went out late, the backend throttled,
 * and players saw a scoreboard clock running at a third of real time and forms opening twenty
 * seconds after the click - while the proxy's own CPU sat mostly idle.
 *
 * <p>Bounded: when the target cannot keep up, what does not fit is dropped and counted rather than
 * queued without limit, and the count is written into the stream once it catches up. Each target
 * has its own queue, so a slow console costs console lines and never the log file.
 */
final class AsyncLogOutput extends OutputStream {

    static final long DEFAULT_LIMIT = 16L * 1024 * 1024;

    private final OutputStream target;
    private final long limit;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long queued;
    private long dropped;
    private boolean writing;
    private boolean closed;

    AsyncLogOutput(OutputStream target, String name, long limit) {
        this.target = target;
        this.limit = limit;
        Thread writer = new Thread(this::run, "log-writer-" + name);
        writer.setDaemon(true);
        writer.start();
    }

    @Override
    public void write(int b) {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) {
        if (length <= 0 || closed) {
            return;
        }
        if (queued + length > limit) {
            dropped += length;
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(buffer, offset, copy, 0, length);
        queue.addLast(copy);
        queued += length;
        notifyAll();
    }

    /** Never blocks: the writer flushes the target itself whenever it runs out of queued bytes. */
    @Override
    public void flush() {
    }

    /**
     * Waits until everything queued so far is written, or the timeout passes.
     *
     * @return whether it all went out
     */
    synchronized boolean drain(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!queue.isEmpty() || dropped > 0 || writing) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private void run() {
        while (true) {
            byte[][] batch;
            long lost;
            synchronized (this) {
                writing = false;
                notifyAll();
                while (queue.isEmpty() && dropped == 0 && !closed) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
                if (queue.isEmpty() && dropped == 0) {
                    return;
                }
                batch = queue.toArray(new byte[0][]);
                queue.clear();
                queued = 0;
                lost = dropped;
                dropped = 0;
                writing = true;
            }
            try {
                for (byte[] chunk : batch) {
                    target.write(chunk);
                }
                if (lost > 0) {
                    target.write(droppedNote(lost));
                }
                target.flush();
            } catch (IOException | RuntimeException ignored) {
                // Nowhere left to report a failure to write the log.
            }
        }
    }

    private static byte[] droppedNote(long bytes) {
        return (System.lineSeparator()
                + "[log] " + bytes + " bytes of output were dropped here: this output could not keep up."
                + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }
}
