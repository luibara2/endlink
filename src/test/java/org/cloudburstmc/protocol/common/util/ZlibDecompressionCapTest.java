package org.cloudburstmc.protocol.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the pre-auth zlib decompression-cap bypass.
 *
 * <p>An unauthenticated client, once compression is negotiated but before it logs in, can send a
 * small raw-zlib batch that inflates without bound. The cap passed into {@link Zlib#inflate} was
 * commented out, so the proxy would allocate arbitrary heap on an I/O thread — a remote,
 * unauthenticated memory-exhaustion DoS. This test drives {@link Zlib#inflate} directly with a
 * highly compressible "bomb" and asserts the cap is enforced. It fails (no exception) if the cap
 * check is ever removed again.
 */
class ZlibDecompressionCapTest {

    /** Raw DEFLATE stream, matching what {@code ZlibCompression} feeds {@link Zlib#RAW}. */
    private static ByteBuf rawDeflate(byte[] plaintext) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setInput(plaintext);
        deflater.finish();
        byte[] buffer = new byte[8192];
        ByteBuf out = Unpooled.buffer();
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            out.writeBytes(buffer, 0, written);
        }
        deflater.end();
        return out;
    }

    @Test
    void inflateRejectsDataBeyondTheCap() {
        int cap = 1 << 20; // 1 MiB — small so the test is fast
        // Zeros compress to almost nothing, so the compressed input is tiny: this IS the bomb.
        ByteBuf bomb = rawDeflate(new byte[cap * 8]);
        try {
            assertThrows(
                    DataFormatException.class,
                    () -> Zlib.RAW.inflate(bomb, cap),
                    "inflate must refuse output larger than the cap"
            );
        } finally {
            bomb.release();
        }
    }

    @Test
    void inflateStillAcceptsDataWithinTheCap() throws DataFormatException {
        int cap = 1 << 20;
        byte[] plaintext = new byte[cap / 4]; // well under the cap
        ByteBuf compressed = rawDeflate(plaintext);
        ByteBuf inflated = null;
        try {
            inflated = Zlib.RAW.inflate(compressed, cap);
            assertEquals(plaintext.length, inflated.readableBytes(), "legitimate payload must survive");
            assertTrue(inflated.readableBytes() <= cap, "output stays within the cap");
        } finally {
            compressed.release();
            if (inflated != null) {
                inflated.release();
            }
        }
    }
}
