package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.endstone.proxy.config.CommandsConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

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

    @Test
    void forwardsCommandsThisBackendHasTakenOver() {
        ProxyCommandInterceptor interceptor = passthrough("hub", "server");

        // The hub plugin's own /hub teleports to spawn and its /server opens a selector form.
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/hub")));
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/server")));
        // Everything the backend did not claim is still the proxy's, on the same backend.
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/lobby")));
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/glist")));
    }

    /** The same names, on a backend with no plugin that wants them, stay the proxy's. */
    @Test
    void keepsCommandsOnABackendThatClaimsNothing() {
        ProxyCommandInterceptor interceptor = passthrough();

        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/hub")));
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/server skygen")));
    }

    @Test
    void qualifiedFormReachesTheProxyThroughAPassthrough() {
        ProxyCommandInterceptor interceptor = passthrough("hub", "server", "perm");

        CommandInterception.Consumed consumed = assertInstanceOf(
                CommandInterception.Consumed.class,
                interceptor.intercept(command("/proxy:server skygen"))
        );
        // The router switches on the bare name, so the qualifier must not survive the lookup.
        assertEquals("server", consumed.command().name());
        // And the arguments are read off the original line, qualifier and all.
        assertEquals(java.util.List.of("skygen"), CommandArguments.split(consumed.originalCommandLine()));

        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/proxy:hub")));
        // The way back in after passing the permission command itself through by mistake.
        assertInstanceOf(CommandInterception.Consumed.class,
                interceptor.intercept(command("/proxy:perm list")));
    }

    @Test
    void qualifiedFormIsCaseInsensitiveAndSurvivesAMissingSlash() {
        ProxyCommandInterceptor interceptor = passthrough("hub");

        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/PROXY:HUB")));
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("proxy:hub")));
    }

    /** A qualified name the proxy does not have is the backend's problem, not ours to answer. */
    @Test
    void forwardsAQualifiedNameThatIsNotAProxyCommand() {
        assertInstanceOf(CommandInterception.Forward.class,
                passthrough().intercept(command("/proxy:parkour")));
    }

    /**
     * An empty qualifier must switch the qualified form off, not turn every command into a
     * qualified one — which is what a startsWith("") test would do, making passthrough a no-op.
     */
    @Test
    void anEmptyQualifierDisablesTheQualifiedFormWithoutBreakingPassthrough() {
        ProxyCommandInterceptor interceptor =
                new ProxyCommandInterceptor(ProxyCommandRegistry.defaults(), Set.of("hub"), "");

        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/hub")));
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/proxy:hub")));
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("/lobby")));
    }

    private static ProxyCommandInterceptor passthrough(String... commands) {
        return new ProxyCommandInterceptor(
                ProxyCommandRegistry.defaults(),
                Set.of(commands),
                CommandsConfig.DEFAULT_QUALIFIER
        );
    }

    private static CommandRequestPacket command(String command) {
        CommandRequestPacket packet = new CommandRequestPacket();
        packet.setCommand(command);
        return packet;
    }
}
