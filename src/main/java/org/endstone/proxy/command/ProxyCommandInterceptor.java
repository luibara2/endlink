package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;

public final class ProxyCommandInterceptor {
    private final ProxyCommandRegistry registry;

    public ProxyCommandInterceptor(ProxyCommandRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
    }

    public CommandInterception intercept(CommandRequestPacket packet) {
        String commandLine = packet.getCommand();
        return registry.find(commandLine)
                .<CommandInterception>map(command -> new CommandInterception.Consumed(command, commandLine))
                .orElseGet(() -> new CommandInterception.Forward(commandLine));
    }
}
