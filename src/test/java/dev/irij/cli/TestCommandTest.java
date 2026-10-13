package dev.irij.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── The plain format is a contract ───────────────────────────────
    // invar's gates (invar/lang/irij.irj) parse `irij test` run in a
    // container, where there is no colour: they need these shapes.

    private static final Ansi PLAIN = new Ansi(false);

    @Test
    void plainFileLinesKeepTheNameRightBeforeTheCounts() {
        assertEquals("✓ test-a.irj (3/3)       42 ms",
                TestCommand.fileLine(PLAIN, "test-a.irj", 3, 0, 42, 25));
        // longer than the column: the time still keeps two spaces off
        assertEquals("✗ test-core.irj (1/2, 1 FAILED)  1.2 s",
                TestCommand.fileLine(PLAIN, "test-core.irj", 1, 1, 1234, 10));
    }

    @Test
    void plainSummaryStartsWithAllPassedOrTotal() {
        assertTrue(TestCommand.summaryLines(PLAIN, 5, 0, 0, 2, List.of(), 10).get(0)
                .startsWith("All passed: 5/5 tests in 2 files"));
        List<String> failed = TestCommand.summaryLines(PLAIN, 4, 1, 0, 2, List.of("test-b.irj"), 10);
        assertTrue(failed.get(0).startsWith("Total: 4/5 tests passed"), failed.get(0));
        assertEquals("Failed: test-b.irj", failed.get(1));
    }

    @Test
    void verboseListsPassingTestsAsOk() {
        String output = "  [ok] one\n  [FAIL] two -- no\n  [ok] three";
        assertEquals(List.of("    [ok] one", "    [FAIL] two -- no", "    [ok] three"),
                TestCommand.testLines(PLAIN, output, true));
    }

    @Test
    void colourWrapsButDoesNotChangeTheText() {
        Ansi colour = new Ansi(true);
        String line = TestCommand.fileLine(colour, "test-a.irj", 3, 0, 42, 25);
        assertTrue(line.contains("\u001b[32m"), "green check");
        assertEquals(TestCommand.fileLine(PLAIN, "test-a.irj", 3, 0, 42, 25),
                line.replaceAll("\u001b\\[[0-9;]*m", ""));
    }
}
