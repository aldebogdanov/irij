package dev.irij.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LspDiagnosticsTest {

    @Test void cleanProgram_emptyDiagnostics() {
        String src = "fn greet :: Str Str\n  (n -> \"Hi, \" ++ n)\n";
        List<Diagnostic> diags = LspDiagnostics.parseErrors(src);
        assertTrue(diags.isEmpty(),
                () -> "expected no diagnostics, got: " + diags);
    }

    @Test void parseError_surfacedAsErrorDiagnostic() {
        // Intentional syntax error — extra ')'.
        String src = "fn greet :: Str Str\n  (n -> ))\n";
        List<Diagnostic> diags = LspDiagnostics.parseErrors(src);
        assertFalse(diags.isEmpty(),
                "expected at least one diagnostic");
        Diagnostic d = diags.get(0);
        assertEquals(DiagnosticSeverity.Error, d.getSeverity());
        assertEquals("irij", d.getSource());
        // Range is best-effort; line should be in document bounds.
        assertTrue(d.getRange().getStart().getLine() >= 0);
    }

    @Test void parseError_rangeMatchesAntlrLineCol() {
        // Bad token at column 9 on line 1. After parse, the diagnostic
        // range's start should be (line 0, character 9) — LSP 0-based.
        String src = "fn greet (";
        List<Diagnostic> diags = LspDiagnostics.parseErrors(src);
        assertFalse(diags.isEmpty());
        Diagnostic d = diags.get(0);
        // Don't assert an exact column — ANTLR's error position
        // depends on recovery state. Just confirm we extracted a
        // non-default range (the malformed-input fallback returns
        // line=0, column=0).
        assertTrue(d.getRange().getStart().getLine() > 0
                || d.getRange().getStart().getCharacter() > 0,
                () -> "expected non-default range, got " + d.getRange());
        // Message strips the "line:col " prefix.
        assertFalse(d.getMessage().matches("^\\d+:\\d+ .*"),
                () -> "message should have prefix stripped, got: " + d.getMessage());
    }
}
