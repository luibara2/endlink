package org.cloudburstmc.protocol.bedrock.codec;

/**
 * Thrown by a serializer that has positively determined a packet's bytes are invalid — a variant tag
 * outside its enum, two copies of one discriminator that disagree, a length that cannot be right.
 *
 * <p>The distinction from an ordinary decode failure is the whole reason this type exists, and it is
 * a distinction only the serializer can draw.</p>
 *
 * <p>A relay's default answer to a packet it cannot decode is to forward the original bytes. That is
 * usually correct: between two peers on the same protocol, a decode failure normally means <em>this
 * codec is incomplete</em>, not that the bytes are bad, and the recipient — which has the real
 * implementation — reads them fine. Dropping those would break working features for no reason.</p>
 *
 * <p>It is exactly wrong when the bytes really are malformed. Forwarding them hands the recipient
 * something it will reject, and a Bedrock client rejects by closing the connection with
 * {@code BadPacket} and no message. So a serializer that <em>knows</em> the value is impossible
 * throws this instead, and the relay drops the packet rather than passing the fault along. Losing one
 * locator-bar update is a cosmetic defect; losing the session is not.</p>
 *
 * <p>Throw it only where the protocol itself forbids the value. A field this codec merely does not
 * model yet is not a validation failure — it is the first case above, and must stay relayable.</p>
 */
public class PacketValidationException extends IllegalArgumentException {

    public PacketValidationException(String message) {
        super(message);
    }

    /** True if {@code failure}, or anything that caused it, was a validation failure. */
    public static boolean isValidationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PacketValidationException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
