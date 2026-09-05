package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.endstone.proxy.permission.ProxyPermissions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class AvailableCommandsInjector {
    /** What vanilla names its free-text parameter, on `/me`, `/msg`, `/say` and friends. */
    private static final String FREE_TEXT_PARAM_NAME = "message";
    private static final AtomicBoolean FREE_TEXT_TYPE_REPORTED = new AtomicBoolean();
    private static final List<String> PERM_ACTIONS = List.of("set", "unset", "info", "list");

    private final ProxyCommandRegistry registry;
    private final List<String> backendNames;
    private final Predicate<String> visible;
    private final ProxyPlayerEnum playerEnum;
    private final boolean enabled;

    /**
     * Injects every registered command, for callers with no player in scope.
     *
     * <p>Real sessions use the {@link #AvailableCommandsInjector(ProxyCommandRegistry, Collection,
     * Predicate, ProxyPlayerEnum) four-argument form} so an admin command never appears in a
     * player's autocomplete, and the backend list is the one that player is allowed to see. Note
     * that hiding is cosmetic — the client can send any command line it likes, so
     * {@code BackendCommandRouter} checks again on execution.</p>
     */
    public AvailableCommandsInjector(ProxyCommandRegistry registry, Collection<String> backendNames) {
        this(registry, backendNames, name -> true, null, true);
    }

    /**
     * @param backendNames the backends this session's player may switch themselves to; a restricted
     *                     backend is left out so its existence is not advertised
     * @param visible      answers whether this session's player may use a command, by command name
     * @param playerEnum   supplies {@code /send}'s autocompletable player list, or null for none
     */
    public AvailableCommandsInjector(
            ProxyCommandRegistry registry,
            Collection<String> backendNames,
            Predicate<String> visible,
            ProxyPlayerEnum playerEnum
    ) {
        this(registry, backendNames, visible, playerEnum, true);
    }

    public AvailableCommandsInjector(
            ProxyCommandRegistry registry,
            Collection<String> backendNames,
            Predicate<String> visible,
            ProxyPlayerEnum playerEnum,
            boolean enabled
    ) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
        this.backendNames = List.copyOf(backendNames);
        this.visible = visible == null ? name -> true : visible;
        this.playerEnum = playerEnum;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AvailableCommandsPacket inject(AvailableCommandsPacket packet) {
        if (!enabled) {
            return packet;
        }
        // Read the backend's own tree before adding ours, so the free-text type comes from a
        // command this client has already proved it can render.
        CommandParam freeTextType = freeTextParamType(packet);

        Set<String> existing = packet.getCommands().stream()
                .map(CommandData::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<CommandData> injected = new ArrayList<>();
        for (ProxyCommand command : registry.commands()) {
            if (!visible.test(command.name())) {
                continue;
            }
            if (existing.add(command.name().toLowerCase(Locale.ROOT))) {
                injected.add(toCommandData(command, freeTextType));
            }
        }
        packet.getCommands().addAll(0, injected);
        return packet;
    }

    /**
     * Borrows the wire type of a free-text parameter from a vanilla command in the backend's own
     * command tree — {@code /me <message>}, {@code /msg <target> <message>}, and so on.
     *
     * <p>Naming a {@link CommandParam} constant instead would go through the codec's parameter type
     * map, and <b>that map is not trustworthy on this protocol</b>. A relayed command never exposes
     * it: {@code readParameter} keeps the raw wire id and writes back whatever it read, so decode
     * and encode cancel out even when the table is wrong. Only parameters the proxy invents actually
     * ask the table for a number — which is why {@code /alert} declared as
     * {@code CommandParam.MESSAGE} (67 in the v975 table this protocol inherits, against 55 in the
     * hand-derived v898 one) <b>crashed the client outright</b> the moment it finished rendering the
     * command name, while the structurally identical {@code STRING} parameter on {@code /send} was
     * fine. Copying a type the backend just sent sidesteps the table entirely and keeps working
     * across versions.</p>
     *
     * @return the borrowed type, or null when the tree has none to borrow — the caller must then
     *         declare no parameter at all rather than fall back to a guess
     */
    private static CommandParam freeTextParamType(AvailableCommandsPacket packet) {
        for (CommandData command : packet.getCommands()) {
            for (CommandOverloadData overload : command.getOverloads()) {
                for (CommandParamData parameter : overload.getOverloads()) {
                    if (!FREE_TEXT_PARAM_NAME.equalsIgnoreCase(parameter.getName())) {
                        continue;
                    }
                    // Enum and postfix parameters carry an index into a table of their own; only a
                    // plain typed parameter is a type we can reuse.
                    if (parameter.getEnumData() == null
                            && parameter.getPostfix() == null
                            && parameter.getType() != null) {
                        reportFreeTextType(command.getName(), parameter.getType());
                        return parameter.getType();
                    }
                }
            }
        }
        reportFreeTextType(null, null);
        return null;
    }

    /** Once per run: enough to diagnose a command tree, not enough to fill the log. */
    private static void reportFreeTextType(String sourceCommand, CommandParam type) {
        if (!FREE_TEXT_TYPE_REPORTED.compareAndSet(false, true)) {
            return;
        }
        if (type == null) {
            System.out.println("WARNING: the backend's command tree has no free-text parameter to copy, "
                    + "so /alert is advertised without one. Naming a parameter type directly is not an "
                    + "option here — the codec's type table for this protocol is unverified, and a wrong "
                    + "id crashes the client.");
            return;
        }
        if (Boolean.getBoolean("proxy.logPackets") || Integer.getInteger("proxy.traceMillis", 0) > 0) {
            System.out.printf("Sourced the /alert message parameter from the backend's /%s: %s.%n",
                    sourceCommand, type);
        }
    }

    private CommandData toCommandData(ProxyCommand command, CommandParam freeTextType) {
        return new CommandData(
                command.name(),
                command.description(),
                Set.of(CommandData.Flag.NOT_CHEAT),
                // ANY, not OPERATOR: the client hides commands above its own permission level, and
                // the proxy relays the backend's real level rather than pretending everyone is op.
                // Authorisation is the router's job, not the command tree's.
                CommandPermission.ANY,
                null,
                List.of(),
                overloadsFor(command, freeTextType)
        );
    }

    private CommandOverloadData[] overloadsFor(ProxyCommand command, CommandParam freeTextType) {
        return switch (command.name()) {
            case "server" -> new CommandOverloadData[]{
                    new CommandOverloadData(false, new CommandParamData[0]),
                    new CommandOverloadData(false, new CommandParamData[]{backendNameParameter("name")})
            };
            case "send" -> new CommandOverloadData[]{
                    new CommandOverloadData(false, new CommandParamData[]{
                            playerParameter("player", false),
                            backendNameParameter("server")
                    })
            };
            // Every parameter is an enum, so none of them touch the codec's parameter type table.
            // The trailing two are optional because `list` takes neither and `info` takes only the
            // player — one overload autocompletes all four forms without the client having to pick
            // between overloads as you type.
            case "perm" -> new CommandOverloadData[]{
                    new CommandOverloadData(false, new CommandParamData[]{
                            fixedEnumParameter("action", "ProxyPermActions", PERM_ACTIONS, false),
                            playerParameter("player", true),
                            fixedEnumParameter("node", "ProxyPermNodes", permissionNodes(), true)
                    })
            };
            case "alert" -> freeTextType == null
                    ? new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[0])}
                    : new CommandOverloadData[]{
                            new CommandOverloadData(false, new CommandParamData[]{
                                    freeTextParameter("message", freeTextType)
                            })
                    };
            default -> new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[0])};
        };
    }

    /**
     * The network's player list, as a soft enum so it autocompletes and can be refreshed as people
     * come and go.
     *
     * <p>Not a target selector: the player being sent is usually on another backend, where the
     * client has no entity to resolve the selector against and would reject the name as unknown.
     * Falls back to a plain string when no roster is available, which still accepts a typed name —
     * it just cannot suggest one.</p>
     */
    private CommandParamData playerParameter(String name, boolean optional) {
        CommandParamData parameter = new CommandParamData();
        parameter.setName(name);
        parameter.setOptional(optional);
        if (playerEnum == null) {
            parameter.setType(CommandParam.STRING);
        } else {
            parameter.setEnumData(playerEnum.snapshot());
        }
        return parameter;
    }

    /**
     * A hard enum: a fixed set of values baked into the command tree. Unlike the soft player enum
     * these never change during a session, so they need no follow-up update packet.
     */
    private CommandParamData fixedEnumParameter(
            String name,
            String enumName,
            List<String> values,
            boolean optional
    ) {
        CommandParamData parameter = new CommandParamData();
        parameter.setName(name);
        parameter.setOptional(optional);
        Map<String, Set<CommandEnumConstraint>> enumValues = new LinkedHashMap<>();
        for (String value : values) {
            enumValues.put(value, EnumSet.noneOf(CommandEnumConstraint.class));
        }
        parameter.setEnumData(new CommandEnumData(enumName, enumValues, false));
        return parameter;
    }

    /** {@code admin}, plus one node per proxy command and per backend. */
    private List<String> permissionNodes() {
        List<String> commandNames = registry.commands().stream().map(ProxyCommand::name).toList();
        return ProxyPermissions.knownNodes(commandNames, backendNames);
    }

    /** A free-text parameter, typed by whatever the backend uses for one. */
    private CommandParamData freeTextParameter(String name, CommandParam type) {
        CommandParamData parameter = new CommandParamData();
        parameter.setName(name);
        parameter.setOptional(false);
        parameter.setType(type);
        return parameter;
    }

    private CommandParamData backendNameParameter(String name) {
        CommandParamData backendName = new CommandParamData();
        backendName.setName(name);
        backendName.setOptional(false);
        backendName.setEnumData(new CommandEnumData("ProxyBackends", backendNameValues(), false));
        return backendName;
    }

    private Map<String, Set<CommandEnumConstraint>> backendNameValues() {
        Map<String, Set<CommandEnumConstraint>> values = new LinkedHashMap<>();
        for (String backendName : backendNames) {
            values.put(backendName, EnumSet.noneOf(CommandEnumConstraint.class));
        }
        return values;
    }
}
