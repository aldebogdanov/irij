package dev.irij.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplInputTest {

    static boolean more(String input) { return ReplInput.isIncomplete(input); }

    @Test void completeOneLiners() {
        assertFalse(more("x := 1\n"));
        assertFalse(more("println (square 7)\n"));
        assertFalse(more("effect E\n"));            // body is optional
    }

    @Test void errorsInsideTheTextAreReportedNow() {
        assertFalse(more("x := )\n"));
    }

    @Test void fnHeaderWaitsForBody() {
        assertTrue(more("fn square\n"));
        assertTrue(more("pub fn f :: Int Int\n"));
    }

    @Test void indentedLineKeepsBlockOpen() {
        assertTrue(more("fn square\n  (n -> n * n)\n"));
        assertTrue(more("fn fact\n  0 => 1\n  n => n * fact (n - 1)\n"));
    }

    @Test void blankOrColumnZeroLineEndsBlock() {
        assertFalse(more("fn square\n  (n -> n * n)\n\n"));
        assertFalse(more("fn square\n  (n -> n * n)\nx := square 3\n"));
    }

    @Test void unfinishedExpressionsWait() {
        assertTrue(more("if c\n"));
        assertTrue(more("x :=\n"));
        assertTrue(more("xs |>\n"));
        assertTrue(more("match x\n"));
    }

    @Test void openBracketsWait() {
        assertTrue(more("x := foo (\n"));
        assertTrue(more("x := #[1 2\n"));
        assertFalse(more("x := #[1 2\n3]\n"));
    }
}
