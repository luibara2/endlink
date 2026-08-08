package org.endstone.proxy.backend;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientInputLocksPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BackendSwitchInputStateTest {

    @Test
    void explicitlyClearsSourceLocksDuringHandoff() {
        BackendSwitchInputState state = new BackendSwitchInputState(6);
        Vector3f position = Vector3f.from(10, 64, 20);

        UpdateClientInputLocksPacket packet = state.clearSource(position);

        assertEquals(0, packet.getLockComponentData());
        assertEquals(position, packet.getServerPosition());
    }

    @Test
    void defaultsToAnExplicitTargetUnlock() {
        BackendSwitchInputState state = new BackendSwitchInputState(0);

        assertEquals(0, state.restoreTarget(Vector3f.ZERO).getLockComponentData());
    }

    @Test
    void preservesLocksExplicitlyRequestedByTargetBackend() {
        BackendSwitchInputState state = new BackendSwitchInputState(0);
        state.rememberTarget(4);

        assertEquals(4, state.restoreTarget(Vector3f.ZERO).getLockComponentData());
    }
}
