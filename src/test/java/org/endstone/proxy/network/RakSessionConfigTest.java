package org.endstone.proxy.network;

import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakSessionConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RakSessionConfigTest {
    @Test
    void outboundQueueHasABoundedDefaultAndCanBeConfigured() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            DefaultRakSessionConfig config = new DefaultRakSessionConfig(channel);

            assertEquals(64 * 1024 * 1024,
                    config.getOption(RakChannelOption.RAK_MAX_QUEUED_BYTES));

            config.setOption(RakChannelOption.RAK_MAX_QUEUED_BYTES, 8 * 1024 * 1024);
            assertEquals(8 * 1024 * 1024, config.getMaxQueuedBytes());
            assertThrows(IllegalArgumentException.class, () -> config.setMaxQueuedBytes(-1));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
