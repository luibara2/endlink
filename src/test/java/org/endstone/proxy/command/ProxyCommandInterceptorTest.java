package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProxyCommandInterceptorTest {
    @Test
    void consumesProxyCommandsOnly() {
        ProxyCommandInterceptor interceptor = new ProxyCommandInterceptor(ProxyCommandRegistry.defaults());

        CommandInterception.Consumed consumed = assertInstanceOf(
                CommandInterception.Consumed.class,
                interceptor.intercept(command("/server survival"))
        );
        assertEquals("server", consumed.command().name());

        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("hub")));
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/say hello")));
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/help")));
    }

    private static CommandRequestPacket command(String command) {
        CommandRequestPacket packet = new CommandRequestPacket();
        packet.setCommand(command);
        return packet;
    }
}
