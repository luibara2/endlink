package org.endstone.proxy.command;

import java.util.List;

public final class ProxyCommands {
    private ProxyCommands() {
    }

    public static List<ProxyCommand> defaults() {
        return List.of(
                new ProxyCommand("hub", "Send yourself to the fallback hub"),
                new ProxyCommand("lobby", "Send yourself to the fallback lobby"),
                new ProxyCommand("server", "List or switch backend servers"),
                new ProxyCommand("glist", "List every player on the network and where they are"),
                new ProxyCommand("send", "Move another player to a backend server"),
                new ProxyCommand("alert", "Broadcast a message to every player on the network"),
                new ProxyCommand("perm", "Grant or revoke proxy permissions")
        );
    }
}
