package dev.irij.cli;

import dev.irij.runtime.QuintCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `irij quint doctor`'s report, uncoloured. */
class QuintCommandTest {

    private static final Ansi PLAIN = new Ansi(false);

    private static String report(String found, String error) {
        return String.join("\n", QuintCommand.report(PLAIN, found, error));
    }

    @Test void testedVersionIsATick() {
        String r = report(QuintCapability.TESTED_VERSION, null);
        assertTrue(r.contains("✓ quint     " + QuintCapability.TESTED_VERSION + "  (the tested version)"), r);
        assertFalse(r.contains("may differ"), r);
    }

    @Test void otherVersionWarnsAndSaysWhichWasTested() {
        String r = report("0.0.1", null);
        assertTrue(r.contains("⚠ quint     0.0.1  (tested: " + QuintCapability.TESTED_VERSION + ")"), r);
        assertTrue(r.contains("may differ here"), r);
        assertTrue(r.indexOf("ℹ apalache") < r.indexOf("may differ"), "statuses come before notes");
    }

    @Test void missingQuintSaysWhyAndThatReplayStillWorks() {
        String r = report(null, "quint is not on PATH");
        assertTrue(r.contains("✗ quint     not usable"), r);
        assertTrue(r.contains("quint is not on PATH"), r);
        assertTrue(r.contains("regression traces still run here"), r);
    }

    @Test void colourIsOffInPlainMode() {
        assertFalse(String.join("", QuintCommand.report(PLAIN, "0.0.1", null)).contains("\u001b["));
        assertTrue(String.join("", QuintCommand.report(new Ansi(true), "0.0.1", null)).contains("\u001b["));
    }
}
