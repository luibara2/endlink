package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendSwitchConfigTest {
    @Test
    void retriesForLongEnoughToCoverABackendRestart() {
        // The point of the retry is a backend that is restarting; one attempt cannot catch that.
        BackendSwitchConfig config = ProxyConfig.from(new Properties()).backendSwitch();

        assertTrue(config.retryWindowMillis() >= 30_000);
        assertTrue(config.retryDelayMillis() > 0);
        // A window shorter than one dial-out would only ever allow a single attempt.
        assertTrue(config.retryWindowMillis() > config.connectTimeoutMillis());
    }

    @Test
    void failsTheDialOutSoonerThanRaknetsOwnTenSecondTimeout() {
        BackendSwitchConfig config = ProxyConfig.from(new Properties()).backendSwitch();

        assertTrue(config.connectTimeoutMillis() < 10_000);
    }

    @Test
    void readsOverridesFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("switch.retryWindowMillis", "45000");
        properties.setProperty("switch.retryDelayMillis", "1500");
        properties.setProperty("switch.timeoutMillis", "9000");
        properties.setProperty("switch.connectTimeoutMillis", "2500");

        BackendSwitchConfig config = ProxyConfig.from(properties).backendSwitch();

        assertEquals(45_000, config.retryWindowMillis());
        assertEquals(1500, config.retryDelayMillis());
        assertEquals(9000, config.timeoutMillis());
        assertEquals(2500, config.connectTimeoutMillis());
    }

    @Test
    void allowsRetriesToBeTurnedOffWithAZeroWindow() {
        Properties properties = new Properties();
        properties.setProperty("switch.retryWindowMillis", "0");

        assertEquals(0, ProxyConfig.from(properties).backendSwitch().retryWindowMillis());
    }

    @Test
    void rejectsATimeoutThatWouldNeverWait() {
        Properties properties = new Properties();
        properties.setProperty("switch.timeoutMillis", "0");

        assertThrows(IllegalArgumentException.class, () -> ProxyConfig.from(properties));
    }
}
