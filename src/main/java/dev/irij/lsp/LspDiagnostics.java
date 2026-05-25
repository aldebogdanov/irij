package dev.irij.lsp;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.compiler.EffectRowChecker;
import dev.irij.compiler.IrijCompiler;
import dev.irij.parser.IrijParseDriver;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse + lint → LSP {@link Diagnostic} list. Pure function over
 * source text; no document state needed.
 *
 * <p>Scope: parse errors, plus single-file
 * {@link EffectRowChecker} pass for effect-row diagnostics
 * (perform-without-row, fn calls whose row isn't in the caller's
 * available row, etc.). Cross-module checks are still future work —
 * they need workspace-shaped module resolution.
 */
final class LspDiagnostics {

    private LspDiagnostics() {}

    /** Combined diagnostics: parse errors + effect-row errors. Run
     *  the effect-row pass only if parse succeeded with a tree, so
     *  a half-typed file doesn't double-spam the editor. */
    static List<Diagnostic> all(String source) {
        List<Diagnostic> out = parseErrors(source);
        // Skip effect-row check on broken source — parse errors are
        // already surfaced, and the checker would just crash on a
        // partial AST.
        if (out.isEmpty()) out.addAll(effectRowErrors(source));
        return out;
    }

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

    /** {@code "  at 12:5"} appearing somewhere in a CompileException
     *  message — the EffectRowChecker appends locations in this
     *  shape (1-based line, 0-based column). */
    private static final Pattern AT_LOC = Pattern.compile("\\bat (\\d+):(\\d+)");

    /** Build the AST from {@code source} and run the single-file
     *  EffectRowChecker pass. Catches the first
     *  {@link IrijCompiler.CompileException} the checker throws and
     *  converts it to a diagnostic anchored at the location embedded
     *  in the message; if no location is found, anchors at (1,1).
     *
     *  <p>Cross-module checks are skipped (no inliner — imported
     *  decls aren't available), so false positives are rare; the
     *  trade-off is that perform-without-row issues that hide
     *  behind a missing import won't be caught. */
    static List<Diagnostic> effectRowErrors(String source) {
        List<Diagnostic> out = new ArrayList<>();
        try {
            IrijParseDriver.ParseResult pr = IrijParseDriver.parse(source);
            if (pr.tree() == null || !pr.errors().isEmpty()) return out;
            List<Decl> decls = new AstBuilder().build(pr.tree());
            EffectRowChecker.check(decls);
        } catch (IrijCompiler.CompileException ex) {
            Diagnostic d = new Diagnostic();
            d.setSeverity(DiagnosticSeverity.Error);
            d.setSource("irij");
            String msg = ex.getMessage() == null ? "effect-row check failed" : ex.getMessage();
            Matcher m = AT_LOC.matcher(msg);
            if (m.find()) {
                try {
                    int line = Integer.parseInt(m.group(1));
                    int col = Integer.parseInt(m.group(2));
                    // Checker emits 1-based line + 0-based col (matches
                    // SourceLoc convention); zeroWidthAt expects 1-based
                    // for both, so bump col.
                    d.setRange(LspText.zeroWidthAt(line, col + 1));
                } catch (NumberFormatException e) {
                    d.setRange(LspText.zeroWidthAt(1, 1));
                }
            } else {
                d.setRange(LspText.zeroWidthAt(1, 1));
            }
            d.setMessage(msg);
            out.add(d);
        } catch (Exception ignored) {
            // Defensive: AST/checker bugs shouldn't break the LSP.
        }
        return out;
    }
}
