package org.endstone.proxy.command;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.data.command.ChainedSubCommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.endstone.proxy.config.PermissionsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailableCommandsInjectorTest {
    @Test
    void injectsDefaultProxyCommands() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();

        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        Set<String> names = packet.getCommands().stream()
                .map(CommandData::getName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("hub"));
        assertTrue(names.contains("lobby"));
        assertTrue(names.contains("server"));
    }

    @Test
    void injectionIsIdempotent() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        AvailableCommandsInjector injector = new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby"));

        injector.inject(packet);
        injector.inject(packet);

        assertEquals(ProxyCommands.defaults().size(), packet.getCommands().size());
    }

    @Test
    void preservesBackendCommands() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        packet.getCommands().add(new CommandData(
                "gamemode",
                "Set a player's game mode",
                Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.ANY,
                null,
                List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[0])}
        ));

        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        Set<String> names = packet.getCommands().stream()
                .map(CommandData::getName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("gamemode"));
        assertTrue(names.contains("hub"));
        assertEquals(ProxyCommands.defaults().size() + 1, packet.getCommands().size());
    }

    @Test
    void hidesCommandsThePlayerMayNotUse() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();

        new AvailableCommandsInjector(
                ProxyCommandRegistry.defaults(),
                List.of("default", "lobby"),
                name -> !PermissionsConfig.DEFAULT_ADMIN_COMMANDS.contains(name),
                null
        ).inject(packet);

        Set<String> names = packet.getCommands().stream()
                .map(CommandData::getName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("server"));
        // Hiding is cosmetic — the router re-checks — but a player should not see /send in
        // autocomplete and wonder why it refuses them.
        assertFalse(names.contains("send"));
        assertFalse(names.contains("alert"));
        assertFalse(names.contains("glist"));
    }

    @Test
    void sendTakesAPlayerNameAndABackendName() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        CommandData send = packet.getCommands().stream()
                .filter(command -> "send".equals(command.getName()))
                .findFirst()
                .orElseThrow();

        CommandParamData[] parameters = send.getOverloads()[0].getOverloads();
        assertEquals(2, parameters.length);
        // A string, not a target selector: the player being sent may be on another backend, where
        // the client has no entity to resolve the selector against.
        assertEquals(CommandParam.STRING, parameters[0].getType());
        assertEquals("ProxyBackends", parameters[1].getEnumData().getName());
    }

    /**
     * Naming a {@link CommandParam} constant makes the serializer look its wire id up in the codec's
     * parameter type table, and that table is unverified on this protocol — a relayed command never
     * exposes it, because decode keeps the raw id and encode writes back what it read. Declaring
     * {@code /alert} as {@code CommandParam.MESSAGE} crashed a live 1.26.30 client the instant it
     * finished rendering the command name. The type has to come from the backend's own tree.
     */
    @Test
    void alertBorrowsItsMessageTypeFromTheBackendsOwnTree() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        // What the client has already proved it can render: /me <message>, straight off the wire.
        CommandParam backendMessageType = new CommandParam(55);
        packet.getCommands().add(vanillaMessageCommand("me", backendMessageType));

        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        CommandParamData[] parameters = alertOverload(packet);
        assertEquals(1, parameters.length);
        assertSame(backendMessageType, parameters[0].getType());
    }

    @Test
    void alertDeclaresNoParameterWhenThereIsNoTypeToBorrow() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();

        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        // Degrades to a command the client refuses to pass arguments to, rather than guessing an id
        // again. An unusable command is an annoyance; a wrong id takes the client down.
        assertEquals(0, alertOverload(packet).length);
    }

    @Test
    void ignoresAnEnumParameterNamedMessageWhenBorrowing() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        CommandParamData enumMessage = new CommandParamData();
        enumMessage.setName("message");
        enumMessage.setEnumData(new CommandEnumData("Messages", Map.of(), false));
        packet.getCommands().add(new CommandData(
                "canned",
                "Send a canned message",
                Set.of(),
                CommandPermission.ANY,
                null,
                List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{enumMessage})}
        ));

        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        // An enum parameter's wire value indexes the packet's enum table, not the type table, so
        // reusing it would point /alert at an unrelated enum.
        assertEquals(0, alertOverload(packet).length);
    }

    /**
     * The property that actually matters, checked at the wire level on the protocol the crash
     * happened on: whatever id the backend used for its own free-text parameter is the id
     * {@code /alert} goes out with. Asserting on the {@link CommandParam} object alone would not
     * catch a serializer that resolves it through the type table anyway.
     */
    @Test
    void alertSerializesWithTheSameWireIdTheBackendUsed() {
        AvailableCommandsPacket backendTree = new AvailableCommandsPacket();
        backendTree.getCommands().add(vanillaMessageCommand("me", new CommandParam(55)));

        int backendId = messageParamWireId(backendTree, "me");
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby"))
                .inject(backendTree);

        // Spelled out, because "they match" would also hold if both collapsed to some default:
        // 55 is what the backend sent, and 67 is what the type table would have produced.
        assertEquals(55, backendId);
        assertEquals(backendId, messageParamWireId(backendTree, "alert"));
    }

    /** Encodes with the 1.26.30 codec and reads back the parameter's value type off the wire. */
    private static int messageParamWireId(AvailableCommandsPacket packet, String commandName) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(Bedrock_v1001.CODEC.createHelper(), buffer, packet);
            AvailableCommandsPacket decoded = (AvailableCommandsPacket) Bedrock_v1001.CODEC.tryDecode(
                    Bedrock_v1001.CODEC.createHelper(),
                    buffer,
                    Bedrock_v1001.CODEC.getPacketDefinition(AvailableCommandsPacket.class).getId()
            );
            return decoded.getCommands().stream()
                    .filter(command -> commandName.equals(command.getName()))
                    .findFirst()
                    .orElseThrow()
                    .getOverloads()[0]
                    .getOverloads()[0]
                    // Set by the deserializer straight from the wire, before any type lookup.
                    .getProtocolValueType();
        } finally {
            buffer.release();
        }
    }

    private static CommandParamData[] alertOverload(AvailableCommandsPacket packet) {
        return packet.getCommands().stream()
                .filter(command -> "alert".equals(command.getName()))
                .findFirst()
                .orElseThrow()
                .getOverloads()[0]
                .getOverloads();
    }

    private static CommandData vanillaMessageCommand(String name, CommandParam messageType) {
        CommandParamData message = new CommandParamData();
        message.setName("message");
        message.setOptional(false);
        message.setType(messageType);
        return new CommandData(
                name,
                "Vanilla command taking free text",
                Set.of(),
                CommandPermission.ANY,
                null,
                List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{message})}
        );
    }

    @Test
    void injectedCommandsSerializeForProtocol898() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);

            AvailableCommandsPacket decoded = (AvailableCommandsPacket) Bedrock_v898.CODEC.tryDecode(
                    Bedrock_v898.CODEC.createHelper(),
                    buffer,
                    Bedrock_v898.CODEC.getPacketDefinition(AvailableCommandsPacket.class).getId()
            );
            Set<String> names = decoded.getCommands().stream()
                    .map(CommandData::getName)
                    .collect(Collectors.toSet());
            assertTrue(names.contains("hub"));
            assertTrue(names.contains("lobby"));
            assertTrue(names.contains("server"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void serverCommandUsesBackendNameEnum() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "lobby")).inject(packet);

        CommandData server = packet.getCommands().stream()
                .filter(command -> "server".equals(command.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(2, server.getOverloads().length);
        CommandParamData backendName = server.getOverloads()[1].getOverloads()[0];
        assertEquals("ProxyBackends", backendName.getEnumData().getName());
        assertTrue(backendName.getEnumData().getValues().containsKey("default"));
        assertTrue(backendName.getEnumData().getValues().containsKey("lobby"));
    }

    @Test
    void protocol898WritesCommandPermissionNames() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        packet.getCommands().add(new CommandData(
                "hub",
                "Send yourself to the fallback hub",
                Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.ANY,
                null,
                List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[0])}
        ));

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);
            skipAvailableCommandPools(buffer, helper);
            skipEnums(buffer, helper);
            skipChainedSubcommands(buffer, helper);
            assertEquals(1, org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer));
            assertEquals("hub", helper.readString(buffer));
            assertEquals("Send yourself to the fallback hub", helper.readString(buffer));
            buffer.readUnsignedShortLE();
            assertEquals("any", helper.readString(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void protocol898CommandTreeAllowsBackendNullChainedSubcommandValues() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        ChainedSubCommandData subcommand = new ChainedSubCommandData("camera");
        subcommand.getValues().add(new ChainedSubCommandData.Value("set", null));
        packet.getCommands().add(new CommandData(
                "camera",
                "Change camera settings",
                Set.of(),
                CommandPermission.ANY,
                null,
                List.of(subcommand),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[0])}
        ));

        BedrockCodecHelper helper = Bedrock_v898.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v898.CODEC.tryEncode(helper, buffer, packet);
            assertTrue(buffer.readableBytes() > 0);
        } finally {
            buffer.release();
        }
    }

    private static void skipAvailableCommandPools(ByteBuf buffer, BedrockCodecHelper helper) {
        int enumValueCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < enumValueCount; i++) {
            helper.readString(buffer);
        }
        int chainedValueCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < chainedValueCount; i++) {
            helper.readString(buffer);
        }
        int suffixCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < suffixCount; i++) {
            helper.readString(buffer);
        }
    }

    private static void skipEnums(ByteBuf buffer, BedrockCodecHelper helper) {
        int enumCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < enumCount; i++) {
            helper.readString(buffer);
            int valueCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
            buffer.skipBytes(valueCount * Integer.BYTES);
        }
    }

    private static void skipChainedSubcommands(ByteBuf buffer, BedrockCodecHelper helper) {
        int subcommandCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < subcommandCount; i++) {
            helper.readString(buffer);
            int valueCount = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
            for (int value = 0; value < valueCount; value++) {
                org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
                org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
            }
        }
    }
}
