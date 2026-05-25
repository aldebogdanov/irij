package dev.irij.lsp;

import dev.irij.parser.IrijParseDriver;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse + lint → LSP {@link Diagnostic} list. Pure function over
 * source text; no document state needed.
 *
 * <p>MVP scope: parse errors only. The compile pipeline's later
 * passes (ModuleInliner, EffectRowChecker) need module resolution
 * which is workspace-shaped; wiring those into the LSP server is
 * 2b.1.
 */
final class LspDiagnostics {

    private LspDiagnostics() {}

    /** Parse {@code source} via {@link IrijParseDriver} and convert
     *  every error string to an LSP Diagnostic. Errors come from
     *  ANTLR in the shape {@code "line:col message"} (1-based line,
     *  0-based column inside ANTLR). */
    static List<Diagnostic> parseErrors(String source) {
        List<Diagnostic> out = new ArrayList<>();
        try {
            IrijParseDriver.ParseResult pr = IrijParseDriver.parse(source);
            for (String err : pr.errors()) {
                Diagnostic d = parseErrorString(err);
                if (d != null) out.add(d);
            }
        } catch (Exception e) {
            // Defensive: don't take the server down on a parser bug.
            // Surface it as a workspace-level diagnostic at (0,0).
            Diagnostic d = new Diagnostic();
            d.setRange(LspText.zeroWidthAt(1, 1));
            d.setSeverity(DiagnosticSeverity.Error);
            d.setSource("irij");
            d.setMessage("internal parser error: " + e.getMessage());
            out.add(d);
        }
        return out;
    }

    /** Parse an ANTLR-style "<line>:<col> <message>" diagnostic string
     *  into an LSP {@link Diagnostic}. The numeric prefix has the
     *  shape `1:9` (1-based line, 0-based column from ANTLR);
     *  everything after the first whitespace is the message.
     *  Malformed input lands at the document's top with the whole
     *  string as the message. */
    private static Diagnostic parseErrorString(String err) {
        if (err == null || err.isEmpty()) return null;
        Diagnostic d = new Diagnostic();
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource("irij");
        int colon = err.indexOf(':');
        int sp = colon < 0 ? -1 : err.indexOf(' ', colon + 1);
        if (colon > 0 && sp > colon) {
            try {
                int line = Integer.parseInt(err.substring(0, colon));
                int col = Integer.parseInt(err.substring(colon + 1, sp));
                // ANTLR's char-position-in-line is 0-based; LSP positions
                // are also 0-based. ANTLR's line is 1-based; LSP is 0-based.
                // `zeroWidthAt` does the 1→0-based line conversion and
                // expects a 1-based column, so bump col by 1.
                d.setRange(LspText.zeroWidthAt(line, col + 1));
                d.setMessage(err.substring(sp + 1).strip());
                return d;
            } catch (NumberFormatException ignored) {}
        }
        d.setRange(LspText.zeroWidthAt(1, 1));
        d.setMessage(err);
        return d;
    }
}
