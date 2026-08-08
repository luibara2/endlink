package org.endstone.proxy.command;

import org.endstone.proxy.backend.BackendDirectory;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.PermissionsConfig;
import org.endstone.proxy.permission.ProxyPermissions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console is how a proxy with no administrators becomes administrable, so the case that matters
 * most is granting the first {@code admin} node from a standing start.
 */
class ProxyConsoleTest {
    private final ProxyPermissions permissions = ProxyPermissions.inMemory(PermissionsConfig.defaults());

    private static BackendDirectory directory() {
        return new BackendDirectory(
                Map.of(
                        "default", new BackendConfig("default", new InetSocketAddress("127.0.0.1", 19133)),
                        "staff", new BackendConfig("staff", new InetSocketAddress("127.0.0.1", 19134))
                ),
                "default",
                "default"
        );
    }

    private ProxyConsole console(Runnable shutdown) {
        NetworkCommands commands = new NetworkCommands(
                null,
                directory(),
                null,
                permissions,
                ProxyCommandRegistry.defaults(),
                null
        );
        return new ProxyConsole(commands, shutdown, null);
    }

    private String run(String line) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            console(() -> {
            }).execute(line);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void grantsTheFirstAdministratorOnAProxyThatHasNone() {
        assertFalse(permissions.isAdmin("2535412345678901", "Steve"));

        run("perm set 2535412345678901 admin");

        assertTrue(permissions.isAdmin("2535412345678901", "Steve"));
        assertTrue(permissions.allows("2535412345678901", "Steve", "perm"));
    }

    @Test
    void revokesAgain() {
        permissions.grant("Steve", ProxyPermissions.ADMIN);

        run("perm unset Steve admin");

        assertFalse(permissions.isAdmin("999", "Steve"));
    }

    @Test
    void refusesANodeThatDoesNotExist() {
        String output = run("perm set Steve command.nonsense");

        assertTrue(output.contains("Unknown permission node"), output);
        // A typo stored happily would look exactly like the permission system being broken.
        assertTrue(permissions.nodesOf("Steve").isEmpty());
    }

    @Test
    void acceptsALeadingSlashSoAnInGameCommandCanBePasted() {
        run("/perm set Steve command.send");

        assertTrue(permissions.allows("999", "Steve", "send"));
    }

    @Test
    void offersEveryBackendAndCommandAsANode() {
        String output = run("perm");

        assertTrue(output.contains("server.staff"), output);
        assertTrue(output.contains("command.send"), output);
        assertTrue(output.contains(ProxyPermissions.ADMIN), output);
    }

    @Test
    void stopsTheProxy() {
        boolean[] stopped = {false};
        console(() -> stopped[0] = true).execute("stop");

        assertTrue(stopped[0]);
    }

    @Test
    void anUnknownCommandSaysSoInsteadOfThrowing() {
        String output = run("wibble");

        assertTrue(output.contains("Unknown command"), output);
    }

    @Test
    void ignoresBlankInput() {
        // A bare Enter at the terminal must not print a usage wall.
        assertTrue(run("").isEmpty());
        assertTrue(run("   ").isEmpty());
    }

    @Test
    void quotesKeepAGamertagWithSpacesTogether() {
        run("perm set \"Some Player\" admin");

        assertTrue(permissions.isAdmin("999", "Some Player"));
        assertFalse(permissions.isAdmin("999", "Some"));
    }

    @Test
    void theConsoleIsAnAdministratorWithoutBeingConfiguredAsOne() {
        NetworkCommands commands = new NetworkCommands(
                null, directory(), null, permissions, ProxyCommandRegistry.defaults(), null);

        // Otherwise a fresh install with an empty permissions.admins could never grant anyone
        // anything, from anywhere.
        assertTrue(commands.authorize(CommandSender.console(), "perm"));
        assertTrue(CommandSender.console().isConsole());
        assertFalse(permissions.allows("", "CONSOLE", "perm"));
    }

    @Test
    void listsEveryRuntimeGrant() {
        permissions.grant("Steve", "command.send");

        String output = run("perm list");

        assertTrue(output.contains("steve"), output);
        assertTrue(output.contains("command.send"), output);
    }

    @Test
    void reportsWhatAPlayerMayDo() {
        permissions.grant("Steve", "command.send");

        String output = run("perm info Steve");

        assertTrue(output.contains("command.send"), output);
        assertTrue(output.contains("administrator: false"), output);
    }

    @Test
    void doesNotStoreANodeForASubjectTheFileCouldNotRepresent() {
        String output = run("perm set a=b admin");

        assertTrue(output.contains("Cannot store that"), output);
        assertTrue(permissions.subjects().isEmpty());
    }

    @Test
    void configuredAdminBackendsBecomeGrantableNodes() {
        PermissionsConfig config = new PermissionsConfig(Set.of(), Set.of("send"), Set.of("staff"));
        ProxyPermissions restricted = ProxyPermissions.inMemory(config);
        NetworkCommands commands = new NetworkCommands(
                null, directory(), null, restricted, ProxyCommandRegistry.defaults(), null);

        assertTrue(commands.knownNodes().contains("server.staff"));
        assertFalse(restricted.mayJoinBackend("999", "Steve", "staff"));
        restricted.grant("Steve", "server.staff");
        assertTrue(restricted.mayJoinBackend("999", "Steve", "staff"));
    }
}
