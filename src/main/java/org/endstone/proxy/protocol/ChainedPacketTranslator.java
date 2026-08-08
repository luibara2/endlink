package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

/**
 * Composes a chain of adjacent-version {@link PacketTranslator}s into a single translator, the way
 * ViaVersion / endweave chain per-version protocols to bridge a multi-version gap.
 *
 * <p>The {@code steps} are ordered from the client side to the backend side. Each step is an adjacent
 * translator authored with the <em>newer</em> protocol as its "client" side: {@code translateServerbound}
 * goes newer&rarr;older and {@code translateClientbound} goes older&rarr;newer. Because a path through the
 * version graph is monotonic in protocol number, every step moves in the same direction, captured once by
 * {@code downgrade} (client protocol &gt; backend protocol).</p>
 *
 * <ul>
 *     <li><b>downgrade</b> (client newer than backend): serverbound applies each step's
 *     {@code translateServerbound} in client&rarr;backend order; clientbound applies each step's
 *     {@code translateClientbound} in reverse.</li>
 *     <li><b>upgrade</b> (client older than backend): the roles flip &mdash; serverbound uses
 *     {@code translateClientbound} and clientbound uses {@code translateServerbound}.</li>
 * </ul>
 *
 * <p>A {@code null} from any step drops the packet (no representation in the next protocol).</p>
 *
 * @see org.endstone.proxy.protocol.ProtocolRegistry#findBinding(int, int)
 */
public final class ChainedPacketTranslator implements PacketTranslator {
    private final List<PacketTranslator> steps;
    private final boolean downgrade;

    public ChainedPacketTranslator(List<PacketTranslator> steps, boolean downgrade) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps cannot be empty");
        }
        this.steps = List.copyOf(steps);
        this.downgrade = downgrade;
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        BedrockPacket current = packet;
        if (downgrade) {
            for (PacketTranslator step : steps) {
                current = step.translateServerbound(current, context);
                if (current == null) {
                    return null;
                }
            }
        } else {
            for (int i = steps.size() - 1; i >= 0; i--) {
                current = steps.get(i).translateClientbound(current, context);
                if (current == null) {
                    return null;
                }
            }
        }
        return current;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        BedrockPacket current = packet;
        if (downgrade) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                current = steps.get(i).translateClientbound(current, context);
                if (current == null) {
                    return null;
                }
            }
        } else {
            for (PacketTranslator step : steps) {
                current = step.translateServerbound(current, context);
                if (current == null) {
                    return null;
                }
            }
        }
        return current;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        AvailableCommandsPacket current = packet;
        // Command trees flow backend -> client, i.e. the clientbound direction.
        if (downgrade) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                current = steps.get(i).translateCommandTree(current, context);
                if (current == null) {
                    return null;
                }
            }
        } else {
            for (PacketTranslator step : steps) {
                current = step.translateCommandTree(current, context);
                if (current == null) {
                    return null;
                }
            }
        }
        return current;
    }
}
