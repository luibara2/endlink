package org.endstone.proxy.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Xbox gamertags may contain spaces, and the client quotes a string argument that does — so
 * {@code /send "Some Player" lobby} has to survive as two arguments or the command sends nobody.
 */
class CommandArgumentsTest {
    @Test
    void splitsPlainArguments() {
        assertEquals(List.of("Steve", "lobby"), CommandArguments.split("/send Steve lobby"));
        assertEquals(List.of("lobby"), CommandArguments.split("/server lobby"));
    }

    @Test
    void keepsAQuotedNameTogether() {
        assertEquals(List.of("Some Player", "lobby"), CommandArguments.split("/send \"Some Player\" lobby"));
    }

    @Test
    void toleratesTheMissingSlashAndExtraSpacing() {
        assertEquals(List.of("Steve", "lobby"), CommandArguments.split("send   Steve    lobby  "));
    }

    @Test
    void aCommandWithNoArgumentsSplitsToNothing() {
        assertTrue(CommandArguments.split("/server").isEmpty());
        assertTrue(CommandArguments.split("/server ").isEmpty());
        assertTrue(CommandArguments.split(null).isEmpty());
    }

    @Test
    void remainderKeepsFreeTextIntact() {
        assertEquals("the server restarts in 5 minutes",
                CommandArguments.remainder("/alert the server restarts in 5 minutes"));
        // Punctuation and quotes belong to the message, not to the parser.
        assertEquals("\"quoted\" text!", CommandArguments.remainder("/alert \"quoted\" text!"));
        assertEquals("", CommandArguments.remainder("/alert"));
        assertEquals("", CommandArguments.remainder(null));
    }
}
