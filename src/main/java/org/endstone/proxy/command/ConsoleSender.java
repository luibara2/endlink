package org.endstone.proxy.command;

/**
 * The operator at the proxy's own terminal.
 *
 * <p>Unconditionally an administrator: anyone who can type into this process can already stop it,
 * edit its config and read its keys, so gating the console behind a permission would protect
 * nothing. It is also the escape hatch — an operator who has revoked their own {@code admin} node in
 * game gets it back from here without editing files.</p>
 */
final class ConsoleSender implements CommandSender {
    static final ConsoleSender INSTANCE = new ConsoleSender();

    private ConsoleSender() {
    }

    @Override
    public String name() {
        return "CONSOLE";
    }

    @Override
    public String xuid() {
        return "";
    }

    @Override
    public boolean isConsole() {
        return true;
    }

    @Override
    public void sendMessage(String message) {
        System.out.println(message);
    }
}
