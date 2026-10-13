package dev.irij.repl;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParseDriver;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.Token;

/**
 * Decides whether the REPL has a complete input or must read another
 * line. Offside rule, as in Python's REPL: a block ends at a blank line
 * or at a line back in column 0.
 *
 * <p>The input is incomplete while
 * <ul>
 *   <li>its last line is indented — a block body may continue;</li>
 *   <li>a bracket is still open;</li>
 *   <li>the parser fails past the last line ({@code if c}, {@code x :=},
 *       {@code xs |>}): more text would fix it, whereas an error inside
 *       the text (e.g. {@code x := )}) is reported right away;</li>
 *   <li>a top-level {@code fn} has a header but no body yet — bodies
 *       are always on the following, indented lines.</li>
 * </ul>
 * A blank last line always completes the input.
 */
final class ReplInput {

    private ReplInput() {}

    /** {@code input} is everything read so far, each line ending in {@code \n}. */
    static boolean isIncomplete(String input) {
        String text = input.endsWith("\n") ? input.substring(0, input.length() - 1) : input;
        String last = text.substring(text.lastIndexOf('\n') + 1);
        if (last.isBlank()) return false;
        if (Character.isWhitespace(last.charAt(0))) return true;
        try {
            if (openBrackets(text) > 0) return true;
            IrijParseDriver.ParseResult pr = IrijParseDriver.parse(text + "\n");
            int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
            for (String err : pr.errors()) {
                int colon = err.indexOf(':');
                if (colon > 0 && Integer.parseInt(err.substring(0, colon)) > lines) return true;
            }
            return pr.tree() != null && lastFnHasNoBody(pr.tree());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int openBrackets(String text) {
        int depth = 0;
        for (Token t : IrijParseDriver.tokenize(text)) {
            switch (t.getType()) {
                case IrijLexer.LPAREN, IrijLexer.LBRACKET, IrijLexer.LBRACE,
                     IrijLexer.VEC_OPEN, IrijLexer.SET_OPEN, IrijLexer.TUPLE_OPEN -> depth++;
                case IrijLexer.RPAREN, IrijLexer.RBRACKET, IrijLexer.RBRACE -> depth--;
                default -> { }
            }
        }
        return depth;
    }

    private static boolean lastFnHasNoBody(IrijParser.CompilationUnitContext cu) {
        var decls = cu.topLevelDecl();
        if (decls.isEmpty()) return false;
        var d = decls.get(decls.size() - 1);
        IrijParser.FnDeclContext fn = d.fnDecl() != null ? d.fnDecl()
                : d.pubDecl() != null ? d.pubDecl().fnDecl() : null;
        return fn != null && fn.fnBody() == null;
    }
}
