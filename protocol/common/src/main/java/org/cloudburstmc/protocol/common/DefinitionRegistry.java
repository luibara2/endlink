package org.cloudburstmc.protocol.common;

/**
 * A basic registry for protocol definitions that can be expanded upon.
 *
 * @param <D>
 */
public interface DefinitionRegistry<D extends Definition> {

    D getDefinition(int runtimeId);

    default D getDefinition(String identifier) {
        throw new UnsupportedOperationException();
    }

    boolean isRegistered(D definition);
}
