package org.endstone.proxy.backend;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientInputLocksPacket;

/**
 * Input-permission state carried across a backend switch.
 *
 * <p>Input locks are client-session state rather than world state. Disconnecting normally clears
 * them, but a seamless backend switch deliberately keeps the client session alive. The source
 * backend's mask therefore has to be cleared explicitly and the target backend's mask restored
 * after the dimension reset finishes.</p>
 */
final class BackendSwitchInputState {
    private int targetLockComponentData;

    BackendSwitchInputState(int targetLockComponentData) {
        this.targetLockComponentData = targetLockComponentData;
    }

    synchronized void rememberTarget(int targetLockComponentData) {
        this.targetLockComponentData = targetLockComponentData;
    }

    UpdateClientInputLocksPacket clearSource(Vector3f position) {
        return packet(0, position);
    }

    synchronized UpdateClientInputLocksPacket restoreTarget(Vector3f position) {
        return packet(targetLockComponentData, position);
    }

    private static UpdateClientInputLocksPacket packet(int lockComponentData, Vector3f position) {
        UpdateClientInputLocksPacket packet = new UpdateClientInputLocksPacket();
        packet.setLockComponentData(lockComponentData);
        // Protocols before v944 serialize this field. Newer clients ignore it, but keeping it valid
        // lets the same switch path continue to work for every client codec the proxy supports.
        packet.setServerPosition(position == null ? Vector3f.ZERO : position);
        return packet;
    }
}
