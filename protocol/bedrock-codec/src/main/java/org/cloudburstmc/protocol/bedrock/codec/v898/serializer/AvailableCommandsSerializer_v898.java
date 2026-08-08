package org.cloudburstmc.protocol.bedrock.codec.v898.serializer;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongObjectPair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v594.serializer.AvailableCommandsSerializer_v594;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.common.util.NullableEnum;
import org.cloudburstmc.protocol.common.util.LongKeys;
import org.cloudburstmc.protocol.common.util.SequencedHashSet;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.*;
import java.util.function.Consumer;

import static org.cloudburstmc.protocol.common.util.Preconditions.checkArgument;

public class AvailableCommandsSerializer_v898 extends AvailableCommandsSerializer_v594 {

    private static final List<String> PERMISSION_LEVEL = Arrays.asList("any", "game_directors", "admin", "host", "owner", "internal");
    private static final int PARAM_TYPE_VALID = 16;
    private static final int PARAM_TYPE_ENUM = 48;
    private static final int PARAM_TYPE_SUFFIXED = 256;
    private static final int PARAM_TYPE_SOFT_ENUM = 1040;

    public AvailableCommandsSerializer_v898(TypeMap<CommandParam> paramTypeMap) {
        super(paramTypeMap);
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, AvailableCommandsPacket packet) {
        SequencedHashSet<String> enumValues = new SequencedHashSet<>();
        SequencedHashSet<String> subCommandValues = new SequencedHashSet<>();
        SequencedHashSet<String> postFixes = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> enums = new SequencedHashSet<>();
        SequencedHashSet<ChainedSubCommandData> subCommandData = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> softEnums = new SequencedHashSet<>();
        SequencedHashSet<LongObjectPair<Set<CommandEnumConstraint>>> enumConstraints = new SequencedHashSet<>();

        for (CommandData data : packet.getCommands()) {
            if (data.getAliases() != null) {
                enumValues.addAll(data.getAliases().getValues().keySet());
                enums.add(data.getAliases());
            }

            for (ChainedSubCommandData subcommand : data.getSubcommands()) {
                if (subCommandData.contains(subcommand)) {
                    continue;
                }

                subCommandData.add(subcommand);
                for (ChainedSubCommandData.Value value : subcommand.getValues()) {
                    if (hasValidSubCommandValuePair(value)) {
                        subCommandValues.add(value.getFirst());
                        subCommandValues.add(value.getSecond());
                    }
                }
            }

            for (CommandOverloadData overload : data.getOverloads()) {
                for (CommandParamData parameter : overload.getOverloads()) {
                    CommandEnumData commandEnumData = parameter.getEnumData();
                    if (commandEnumData != null) {
                        if (commandEnumData.isSoft()) {
                            softEnums.add(commandEnumData);
                        } else {
                            enums.add(commandEnumData);
                            int enumIndex = enums.indexOf(commandEnumData);
                            commandEnumData.getValues().forEach((key, constraints) -> {
                                enumValues.add(key);
                                if (!constraints.isEmpty()) {
                                    int valueIndex = enumValues.indexOf(key);
                                    enumConstraints.add(LongObjectPair.of(LongKeys.key(valueIndex, enumIndex), constraints));
                                }
                            });
                        }
                    }

                    String postfix = parameter.getPostfix();
                    if (postfix != null) {
                        postFixes.add(postfix);
                    }
                }
            }
        }

        helper.writeArray(buffer, enumValues, helper::writeString);
        helper.writeArray(buffer, subCommandValues, helper::writeString);
        helper.writeArray(buffer, postFixes, helper::writeString);

        this.writeEnums(buffer, helper, enumValues, enums);
        helper.writeArray(buffer, subCommandData, (buf, value) -> this.writeSubCommand(buffer, helper, subCommandValues, value));

        helper.writeArray(buffer, packet.getCommands(), (buf, command) ->
                this.writeCommand(buffer, helper, command, enums, softEnums, postFixes, subCommandData));

        helper.writeArray(buffer, softEnums, helper::writeCommandEnum);
        helper.writeArray(buffer, enumConstraints, this::writeEnumConstraint);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, AvailableCommandsPacket packet) {
        SequencedHashSet<String> enumValues = new SequencedHashSet<>();
        SequencedHashSet<String> subCommandValues = new SequencedHashSet<>();
        SequencedHashSet<String> postFixes = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> enums = new SequencedHashSet<>();
        SequencedHashSet<ChainedSubCommandData> subCommandData = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> softEnums = new SequencedHashSet<>();
        Set<Consumer<List<CommandEnumData>>> softEnumParameters = new HashSet<>();

        helper.readArray(buffer, enumValues, helper::readString, -1);
        helper.readArray(buffer, subCommandValues, helper::readString, -1);
        helper.readArray(buffer, postFixes, helper::readString, -1);

        this.readEnums(buffer, helper, enumValues, enums);

        helper.readArray(buffer, subCommandData, (buf, hel) -> this.readSubCommand(buf, hel, subCommandValues), -1);

        helper.readArray(buffer, packet.getCommands(), (buf, aHelper) ->
                this.readCommand(buf, aHelper, enums, postFixes, softEnumParameters, subCommandData), -1);

        helper.readArray(buffer, softEnums, buf -> helper.readCommandEnum(buffer, true), -1);

        this.readConstraints(buffer, helper, enums, enumValues);

        softEnumParameters.forEach(consumer -> consumer.accept(softEnums));
    }

    @Override
    protected void writeCommand(ByteBuf buffer, BedrockCodecHelper helper, CommandData commandData,
                                List<CommandEnumData> enums, List<CommandEnumData> softEnums, List<String> postFixes, List<ChainedSubCommandData> subCommands) {
        helper.writeString(buffer, commandData.getName());
        helper.writeString(buffer, commandData.getDescription());
        this.writeFlags(buffer, commandData.getFlags());
        CommandPermission permission = commandData.getPermission() == null ? CommandPermission.ANY : commandData.getPermission();
        helper.writeString(buffer, PERMISSION_LEVEL.get(permission.ordinal()));

        CommandEnumData aliases = commandData.getAliases();
        buffer.writeIntLE(aliases == null ? -1 : enums.indexOf(aliases));

        helper.writeArray(buffer, commandData.getSubcommands(), (buf, subcommand) -> {
            int index = subCommands.indexOf(subcommand);
            checkArgument(index > -1, "Invalid subcommand index: " + subcommand);
            buf.writeIntLE(index);
        });

        CommandOverloadData[] overloads = commandData.getOverloads();
        VarInts.writeUnsignedInt(buffer, overloads.length);
        for (CommandOverloadData overload : overloads) {
            buffer.writeBoolean(overload.isChaining());
            VarInts.writeUnsignedInt(buffer, overload.getOverloads().length);
            for (CommandParamData param : overload.getOverloads()) {
                this.writeParameter(buffer, helper, param, enums, softEnums, postFixes);
            }
        }
    }

    @Override
    protected CommandData readCommand(ByteBuf buffer, BedrockCodecHelper helper, List<CommandEnumData> enums,
                                      List<String> postfixes, Set<Consumer<List<CommandEnumData>>> softEnumParameters, List<ChainedSubCommandData> subCommandsList) {
        String name = helper.readString(buffer);
        String description = helper.readString(buffer);
        Set<CommandData.Flag> flags = this.readFlags(buffer);
        CommandPermission permissions = NullableEnum.get(PERMISSIONS, PERMISSION_LEVEL.indexOf(helper.readString(buffer)));
        int aliasIndex = buffer.readIntLE();
        CommandEnumData aliases = aliasIndex == -1 ? null : enums.get(aliasIndex);

        List<ChainedSubCommandData> subcommands = new ObjectArrayList<>();
        helper.readArray(buffer, subcommands, (buf, help) -> {
            int index = Math.toIntExact(buf.readUnsignedIntLE());
            return subCommandsList.get(index);
        });

        CommandOverloadData[] overloads = new CommandOverloadData[VarInts.readUnsignedInt(buffer)];
        for (int i = 0; i < overloads.length; i++) {
            boolean chaining = buffer.readBoolean();
            CommandParamData[] params = new CommandParamData[VarInts.readUnsignedInt(buffer)];
            overloads[i] = new CommandOverloadData(chaining, params);
            for (int i2 = 0; i2 < params.length; i2++) {
                params[i2] = readParameter(buffer, helper, enums, postfixes, softEnumParameters);
            }
        }
        return new CommandData(name, description, flags, permissions, aliases, subcommands, overloads);
    }

    @Override
    protected void writeEnums(ByteBuf buffer, BedrockCodecHelper helper, List<String> values, List<CommandEnumData> enums) {
        helper.writeArray(buffer, enums, (buf, commandEnum) -> {
            helper.writeString(buf, commandEnum.getName());

            VarInts.writeUnsignedInt(buffer, commandEnum.getValues().size());
            for (String value : commandEnum.getValues().keySet()) {
                int index = values.indexOf(value);
                checkArgument(index > -1, "Invalid enum value detected: %s", value);
                buffer.writeIntLE(index);
            }
        });
    }

    @Override
    protected void readEnums(ByteBuf buffer, BedrockCodecHelper helper, List<String> values, List<CommandEnumData> enums) {
        helper.readArray(buffer, enums, buf -> {
            String name = helper.readString(buf);

            int length = VarInts.readUnsignedInt(buffer);
            LinkedHashMap<String, Set<CommandEnumConstraint>> enumValues = new LinkedHashMap<>();
            for (int i = 0; i < length; i++) {
                enumValues.put(values.get((int) buf.readUnsignedIntLE()), EnumSet.noneOf(CommandEnumConstraint.class));
            }
            return new CommandEnumData(name, enumValues, false);
        });
    }

    @Override
    protected void writeSubCommand(ByteBuf buffer, BedrockCodecHelper helper, List<String> values, ChainedSubCommandData data) {
        helper.writeString(buffer, data.getName());
        List<ChainedSubCommandData.Value> validValues = data.getValues().stream()
                .filter(AvailableCommandsSerializer_v898::hasValidSubCommandValuePair)
                .toList();
        helper.writeArray(buffer, validValues, (buf, val) -> {
            int first = values.indexOf(val.getFirst());
            checkArgument(first > -1, "Invalid enum value detected: %s", val.getFirst());

            int second = values.indexOf(val.getSecond());
            checkArgument(second > -1, "Invalid enum value detected: %s", val.getSecond());

            VarInts.writeUnsignedInt(buf, first);
            VarInts.writeUnsignedInt(buf, second);
        });
    }

    @Override
    protected ChainedSubCommandData readSubCommand(ByteBuf buffer, BedrockCodecHelper helper, List<String> values) {
        String name = helper.readString(buffer);
        ChainedSubCommandData data = new ChainedSubCommandData(name);

        helper.readArray(buffer, data.getValues(), buf -> {
            int first = VarInts.readUnsignedInt(buf);
            int second = VarInts.readUnsignedInt(buf);
            return new ChainedSubCommandData.Value(values.get(first), values.get(second));
        });
        return data;
    }

    private static boolean hasValidSubCommandValuePair(ChainedSubCommandData.Value value) {
        return value != null && value.getFirst() != null && value.getSecond() != null;
    }

    @Override
    protected void writeEnumConstraint(ByteBuf buffer, BedrockCodecHelper helper, LongObjectPair<Set<CommandEnumConstraint>> pair) {
        buffer.writeIntLE(LongKeys.high(pair.keyLong()));
        buffer.writeIntLE(LongKeys.low(pair.keyLong()));
        helper.writeArray(buffer, pair.value(), (buf, constraint) -> buf.writeByte(constraint.ordinal()));
    }

    @Override
    protected void readConstraints(ByteBuf buffer, BedrockCodecHelper helper, List<CommandEnumData> enums,
                                   List<String> enumValues) {
        int count = VarInts.readUnsignedInt(buffer);
        for (int i = 0; i < count; i++) {
            String key = enumValues.get((int) buffer.readUnsignedIntLE());
            CommandEnumData enumData = enums.get((int) buffer.readUnsignedIntLE());
            Set<CommandEnumConstraint> constraints = enumData.getValues().get(key);
            helper.readArray(buffer, constraints, buf -> NullableEnum.get(CONSTRAINTS, buf.readUnsignedByte()));
        }
    }

    @Override
    protected void writeParameter(ByteBuf buffer, BedrockCodecHelper helper, CommandParamData param,
                                  List<CommandEnumData> enums, List<CommandEnumData> softEnums, List<String> postfixes) {
        helper.writeString(buffer, param.getName());

        int valueType;
        int enumType;
        if (shouldPreserveRawParameterType(param)) {
            valueType = param.getProtocolValueType();
            enumType = param.getProtocolEnumType();
        } else if (param.getPostfix() != null) {
            valueType = postfixes.indexOf(param.getPostfix());
            checkArgument(valueType > -1, "Invalid postfix detected: " + param.getPostfix());
            enumType = PARAM_TYPE_SUFFIXED;
        } else if (param.getEnumData() != null) {
            if (param.getEnumData().isSoft()) {
                valueType = softEnums.indexOf(param.getEnumData());
                checkArgument(valueType > -1, "Invalid soft enum detected: " + param.getEnumData());
                enumType = PARAM_TYPE_SOFT_ENUM;
            } else {
                valueType = enums.indexOf(param.getEnumData());
                checkArgument(valueType > -1, "Invalid enum detected: " + param.getEnumData());
                enumType = PARAM_TYPE_ENUM;
            }
        } else if (param.getType() != null) {
            valueType = this.paramTypeMap.getIdUnsafe(param.getType());
            if (valueType == -1 && param.getType().getDefaultValue() >= 0) {
                valueType = param.getType().getDefaultValue();
            }
            checkArgument(valueType > -1, "Invalid parameter type detected: " + param.getType());
            enumType = PARAM_TYPE_VALID;
        } else {
            throw new IllegalStateException("No param type specified: " + param);
        }

        buffer.writeShortLE(valueType);
        buffer.writeShortLE(enumType);
        buffer.writeBoolean(param.isOptional());

        Set<CommandParamOption> options = param.getOptions();
        int optionsBits = 0;
        for (CommandParamOption option : options) {
            optionsBits |= 1 << (option.ordinal() + 1);
        }
        buffer.writeByte(optionsBits);
    }

    private static boolean shouldPreserveRawParameterType(CommandParamData param) {
        int enumType = param.getProtocolEnumType();
        return param.getProtocolValueType() >= 0
                && enumType >= 0
                && enumType != PARAM_TYPE_VALID
                && enumType != PARAM_TYPE_ENUM
                && enumType != PARAM_TYPE_SUFFIXED
                && enumType != PARAM_TYPE_SOFT_ENUM;
    }

    @Override
    protected CommandParamData readParameter(ByteBuf buffer, BedrockCodecHelper helper, List<CommandEnumData> enums,
                                             List<String> postfixes, Set<Consumer<List<CommandEnumData>>> softEnumParameters) {
        CommandParamData param = new CommandParamData();

        param.setName(helper.readString(buffer));

        int valueType = buffer.readUnsignedShortLE();
        int enumType = buffer.readUnsignedShortLE();
        param.setProtocolValueType(valueType);
        param.setProtocolEnumType(enumType);
        switch (enumType) {
            case PARAM_TYPE_SUFFIXED -> param.setPostfix(postfixes.get(valueType));
            case PARAM_TYPE_SOFT_ENUM -> softEnumParameters.add((softEnums) -> param.setEnumData(softEnums.get(valueType)));
            case PARAM_TYPE_ENUM -> param.setEnumData(enums.get(valueType));
            case PARAM_TYPE_VALID -> {
                CommandParam type = paramTypeMap.getTypeUnsafe(valueType);
                if (type == null) {
                    type = new CommandParam(valueType);
                }
                param.setType(type);
            }
            default -> {
                if ((enumType & PARAM_TYPE_VALID) != 0) {
                    CommandParam type = paramTypeMap.getTypeUnsafe(valueType);
                    if (type == null) {
                        type = new CommandParam(valueType);
                    }
                    param.setType(type);
                }
            }
        }

        param.setOptional(buffer.readBoolean());

        Set<CommandParamOption> options = param.getOptions();
        int optionsBits = buffer.readUnsignedByte();

        for (CommandParamOption option : OPTIONS) {
            if ((optionsBits & 1 << (option.ordinal() + 1)) != 0) {
                options.add(option);
            }
        }
        return param;
    }
}
