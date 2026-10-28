package org.cloudburstmc.protocol.bedrock.data.attributelayer;

import lombok.Value;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;

@Value
public class EnvironmentAttributeData {

    String attributeName;

    @Nullable
    AttributeData from;
    AttributeData attribute;
    @Nullable
    AttributeData to;

    int currentTransitionTicks;
    int totalTransitionTicks;
    CameraEase easing;
    /**
     * @since v2225
     */
    String clockName;
    /**
     * @since v1001
     */
    int localTransitionTicks;
    /**
     * @since v1001
     */
    boolean noiseTransition;
    /**
     * @since v2225
     */
    String noiseName;
    /**
     * @since v2192
     */
    NoiseAlignment noiseAlignment;

    public EnvironmentAttributeData(String attributeName, @Nullable AttributeData from, AttributeData attribute,
                                    @Nullable AttributeData to, int currentTransitionTicks, int totalTransitionTicks,
                                    CameraEase easing, String clockName, int localTransitionTicks,
                                    boolean noiseTransition, String noiseName, NoiseAlignment noiseAlignment) {
        this.attributeName = attributeName;
        this.from = from;
        this.attribute = attribute;
        this.to = to;
        this.currentTransitionTicks = currentTransitionTicks;
        this.totalTransitionTicks = totalTransitionTicks;
        this.easing = easing;
        this.clockName = clockName;
        this.localTransitionTicks = localTransitionTicks;
        this.noiseTransition = noiseTransition;
        this.noiseName = noiseName;
        this.noiseAlignment = noiseAlignment;
    }

    /**
     * The constructor as it was before 1.26.60 added the clock and noise names, kept so that addons
     * and tests built against it still compile. Both names read as absent.
     */
    public EnvironmentAttributeData(String attributeName, @Nullable AttributeData from, AttributeData attribute,
                                    @Nullable AttributeData to, int currentTransitionTicks, int totalTransitionTicks,
                                    CameraEase easing, int localTransitionTicks, boolean noiseTransition,
                                    NoiseAlignment noiseAlignment) {
        this(attributeName, from, attribute, to, currentTransitionTicks, totalTransitionTicks, easing, null,
                localTransitionTicks, noiseTransition, null, noiseAlignment);
    }
}
