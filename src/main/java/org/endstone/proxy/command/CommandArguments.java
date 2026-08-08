package org.endstone.proxy.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the arguments off a command line the client sent.
 *
 * <p>Quote-aware because Xbox gamertags may contain spaces, and the client quotes a
 * {@code CommandParam.STRING} argument that does: {@code /send "Some Player" lobby} has to arrive as
 * two arguments, not three.</p>
 */
public final class CommandArguments {
    private CommandArguments() {
    }

    /** The arguments after the command name, with surrounding quotes removed. */
    public static List<String> split(String commandLine) {
        String arguments = remainder(commandLine);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (character == '"') {
                quoted = !quoted;
                // A quoted empty string is still an argument, so remember that one began.
                started = true;
            } else if (!quoted && Character.isWhitespace(character)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(character);
                started = true;
            }
        }
        if (started) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }

    /** Everything after the command name, verbatim — for free-text arguments such as an alert. */
    public static String remainder(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String trimmed = commandLine.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = indexOfWhitespace(trimmed);
        if (space < 0) {
            return "";
        }
        return trimmed.substring(space + 1).trim();
    }

    private static int indexOfWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
