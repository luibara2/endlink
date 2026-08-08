package org.cloudburstmc.protocol.common.util;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.experimental.UtilityClass;

@UtilityClass
public class NullableEnum {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NullableEnum.class);

    public static <E extends Enum<E>> E get(E[] enumValues, int index) {
        if (index >= 0 && index < enumValues.length) {
            return enumValues[index];
        }

        log.debug("Invalid index {} for enum {}", index, enumValues.getClass().getName());
        return null;
    }

    public static <E extends Enum<E>> E get(E[] enumValues, int index, E defaultValue) {
        if (index >= 0 && index < enumValues.length) {
            return enumValues[index];
        }

        log.debug("Invalid index {} for enum {}", index, enumValues.getClass().getName());
        return defaultValue;
    }
}
