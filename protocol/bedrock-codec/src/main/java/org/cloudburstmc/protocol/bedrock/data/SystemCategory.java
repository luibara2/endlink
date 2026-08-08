package org.cloudburstmc.protocol.bedrock.data;

import lombok.Value;

/**
 * One entry of {@code ServerboundDiagnosticsPacket}'s system-category list.
 *
 * <p>Named {@code ECS::Profiling::Diagnostics::SystemCategory} in the BDS schema dump.</p>
 *
 * @since v2168
 */
@Value
public class SystemCategory {
    String categoryName;
    long systemIndex;
}
