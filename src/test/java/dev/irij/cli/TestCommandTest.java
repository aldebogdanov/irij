package dev.irij.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What `irij test` shows of a failing file: each [FAIL] line, and the
 *  indented lines that continue its message. */
class TestCommandTest {

    @Test
    void aMultiLineFailureKeepsItsLaterLines() {
        String output = String.join("\n",
                "  [ok] first",
                "  [FAIL] conforms -- diverged on trace 0 of 30, seed 7",
                "        step     8, action overdraft",
                "        expected {balance= 44}",
                "  [ok] last",
                "printed by the file itself");
        assertEquals(List.of(
                "    [FAIL] conforms -- diverged on trace 0 of 30, seed 7",
                "        step     8, action overdraft",
                "        expected {balance= 44}"),
                TestCommand.failureLines(output));
    }

    @Test
    void outputAfterAFailureIsNotPartOfIt() {
        String output = String.join("\n",
                "  [FAIL] one -- short",
                "not indented, so not the message",
                "      indented, but no longer after a failure");
        assertEquals(List.of("    [FAIL] one -- short"), TestCommand.failureLines(output));
    }
}
