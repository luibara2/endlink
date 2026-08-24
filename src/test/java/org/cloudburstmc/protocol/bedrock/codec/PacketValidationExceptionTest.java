package org.cloudburstmc.protocol.bedrock.codec;

import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract the relay's drop rule rests on: an unmodelled packet stays relayable, a rejected one
 * does not.
 *
 * <p>Getting this backwards is costly in both directions. Treating every decode failure as a
 * rejection would drop the packets this codec merely does not model yet — which are relayed byte-
 * exact today and work fine. Treating a rejection as unmodelled forwards bytes the recipient will
 * refuse, and a Bedrock client refuses by closing the connection with no message.
 */
class PacketValidationExceptionTest {

    @Test
    void anUnknownPacketIsRelayableUnlessSaidOtherwise() {
        assertTrue(new UnknownPacket().isRelayable(),
                "an unmodelled packet id must still be forwarded byte-exact");
    }

    @Test
    void recognisesADirectValidationFailure() {
        assertTrue(PacketValidationException.isValidationFailure(
                new PacketValidationException("variant 3 selects no case")));
    }

    /**
     * The codec wraps whatever a serializer throws in a {@link PacketSerializeException}, so the
     * check has to look through the cause chain rather than at the top of it.
     */
    @Test
    void looksThroughTheCodecsWrapping() {
        Throwable wrapped = new PacketSerializeException(
                "Error whilst deserializing PlayerLocationPacket",
                new PacketValidationException("variant -1 selects no case"));

        assertTrue(PacketValidationException.isValidationFailure(wrapped));
    }

    @Test
    void looksThroughSeveralLayers() {
        Throwable deep = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new PacketValidationException("inner")));

        assertTrue(PacketValidationException.isValidationFailure(deep));
    }

    /**
     * The ordinary decode failure — a buffer that ran out because this codec models a packet
     * imperfectly. The composter {@code LegacyTelemetryEventPacket} fails exactly this way and is
     * relayed byte-exact today, which is the behaviour that must not change.
     */
    @Test
    void anOrdinaryDecodeFailureIsNotAValidationFailure() {
        Throwable underflow = new PacketSerializeException(
                "Error whilst deserializing EventPacket",
                new IndexOutOfBoundsException("readerIndex(14) + length(1) exceeds writerIndex(14)"));

        assertFalse(PacketValidationException.isValidationFailure(underflow));
    }

    @Test
    void toleratesNullAndSelfReferentialCauses() {
        assertFalse(PacketValidationException.isValidationFailure(null));

        Throwable loop = new RuntimeException("loops to itself") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(PacketValidationException.isValidationFailure(loop));
    }
}
