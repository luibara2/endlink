package org.endstone.proxy.diagnostics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.endstone.proxy.config.FailoverConfig;
import org.endstone.proxy.config.ProtocolFaultPolicy;
import org.endstone.proxy.config.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend going down and a backend rejecting our packets are different failures and must be
 * handled differently: the first is worth a failover, the second is a bug that travels with the
 * player. These cover the classification, not the failover machinery itself.
 */
class ProtocolFaultPolicyTest {

    /**
     * The real thing, from the proxy log: {@code id=156 payloadBytes=82}. Severity 2, cause packet
     * 144, and BDS naming the schema member it could not read.
     */
    private static final String CAPTURED_TERMINATING =
            "0004a0024d77726f6e6720636f6e73742076616c756520666f72206d656d626572"
                    + "2022416374696f6e2074797065220a726561644e6f486561646572206661696c"
                    + "656421207061636b657449643a20313434";

    @Test
    void aCapturedViolationDecodesToSomethingActionable() {
        ByteBuf payload = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(CAPTURED_TERMINATING));
        try {
            PacketViolation violation = PacketViolation.decode(payload);

            assertNotNull(violation);
            assertEquals(144, violation.causePacketId(), "PlayerAuthInput");
            assertEquals(PacketViolation.SEVERITY_TERMINATING, violation.severity());
            assertTrue(violation.isTerminating());
            assertTrue(violation.message().contains("Action type"),
                    "the schema member BDS named is the whole point of decoding this");
            // The payload must be left alone - it is still going to be raw-forwarded.
            assertEquals(CAPTURED_TERMINATING.length() / 2, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    @Test
    void aWarningSeverityIsNotFatal() {
        // type 0, severity 0, cause 1, "x" - BDS complaining but carrying on. Dropping a player over
        // this would be worse than the bug being reported.
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{0x00, 0x00, 0x02, 0x01, 'x'});
        try {
            PacketViolation violation = PacketViolation.decode(payload);
            assertNotNull(violation);
            assertFalse(violation.isTerminating());
        } finally {
            payload.release();
        }
    }

    @Test
    void garbageDecodesToNullRatherThanThrowing() {
        // A broken diagnostic must not become a second fault.
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        try {
            assertNull(PacketViolation.decode(payload));
        } finally {
            payload.release();
        }
    }

    @Test
    void theDefaultIsToDisconnectRatherThanFailOver() {
        ProtocolFaultPolicy policy = ProtocolFaultPolicy.defaults();
        assertTrue(policy.disconnects());
        assertTrue(policy.logsToFile());
        assertEquals(ProtocolFaultPolicy.DEFAULT_LOG_FILE, policy.logFile());
    }

    @Test
    void theConfigCanRestoreFailoverAndSilenceTheFile() {
        Properties properties = new Properties();
        properties.setProperty("protocolFault.action", "failover");
        properties.setProperty("protocolFault.logFile", "");
        properties.setProperty("protocolFault.message", "custom");

        ProtocolFaultPolicy policy = failoverOf(properties).protocolFault();

        assertFalse(policy.disconnects(), "failover is opt-in for protocol faults");
        assertFalse(policy.logsToFile(), "an empty file keeps the rule but drops the dedicated log");
        assertEquals("custom", policy.message());
    }

    @Test
    void anUnknownActionFallsBackToDisconnectInsteadOfRefusingToStart() {
        Properties properties = new Properties();
        properties.setProperty("protocolFault.action", "explode");

        assertTrue(failoverOf(properties).protocolFault().disconnects());
    }

    @Test
    void theFaultLogIsOneSelfContainedLinePerFault(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested").resolve("protocol-errors.log");
        ProtocolFaultLog log = new ProtocolFaultLog(file);

        log.record(new ProtocolFault("skygen", "Stoom fabriek35", "packet 144 rejected: Action type"));
        log.record(new ProtocolFault("lobby", "itzdbYTX", "packet 63 rejected: Descriptor Type"));

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "the parent directory is created rather than dropping the fault");
        assertTrue(lines.get(0).contains("backend=skygen"));
        assertTrue(lines.get(0).contains("player=Stoom fabriek35"));
        assertTrue(lines.get(0).contains("Action type"));
        assertTrue(lines.get(1).contains("backend=lobby"));
    }

    @Test
    void aDisabledFaultLogWritesNothingAndDoesNotThrow() {
        ProtocolFaultLog log = ProtocolFaultLog.disabled();
        assertFalse(log.enabled());
        log.record(new ProtocolFault("skygen", "someone", "detail"));
    }

    private static FailoverConfig failoverOf(Properties properties) {
        return ProxyConfig.from(properties).failover();
    }
}
