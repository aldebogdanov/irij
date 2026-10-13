package dev.irij.lsp;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParseDriver;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SelectionRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code textDocument/selectionRange}: expand/shrink selection along
 * the ANTLR parse tree. Pure function of the source text.
 *
 * <p>For each position: the token under the cursor, then every
 * enclosing rule context, outermost last, ending with the whole
 * document. A context's span is its first to last <em>real</em>
 * token — the lexer's synthetic INDENT / DEDENT / NEWLINE tokens and
 * EOF sit at line ends and would otherwise stretch a block over the
 * following newline. Two extra steps make the chain read like an
 * editor's: the contents of a bracketed group before the group
 * itself, and a string's text before its quotes. Spans that repeat
 * (single-child rule chains) collapse into one step.
 *
 * <p>Offsets: ANTLR indexes code points; LSP columns count UTF-16
 * units. {@link Text} converts both ways.
 */
final class LspSelection {

    private LspSelection() {}

    static List<SelectionRange> compute(String source, List<Position> positions) {
        Text text = new Text(source);
        IrijParseDriver.ParseResult pr;
        try {
            pr = IrijParseDriver.parse(source);
        } catch (RuntimeException e) {
            pr = null;
        }
        Map<Integer, TerminalNode> terminals = new HashMap<>();
        if (pr != null && pr.tree() != null) collectTerminals(pr.tree(), terminals);
        List<Token> tokens = pr == null ? List.of() : pr.tokenStream().getTokens();

        List<SelectionRange> out = new ArrayList<>();
        for (Position p : positions) {
            int cp = text.cpOffset(p);
            List<int[]> spans = spansAt(cp, tokens, terminals);
            spans.add(new int[] {0, text.cpLength()});
            out.add(chain(text, spans, cp));
        }
        return out;
    }

    /** Innermost-first [start, end) code-point spans enclosing {@code cp}. */
    private static List<int[]> spansAt(int cp, List<Token> tokens, Map<Integer, TerminalNode> terminals) {
        List<int[]> spans = new ArrayList<>();
        Token tok = tokenAt(cp, tokens);
        if (tok == null) return spans;
        if (tok.getType() == IrijLexer.STRING && tok.getStopIndex() - tok.getStartIndex() >= 2) {
            spans.add(new int[] {tok.getStartIndex() + 1, tok.getStopIndex()});
        }
        spans.add(new int[] {tok.getStartIndex(), tok.getStopIndex() + 1});

        TerminalNode leaf = terminals.get(tok.getTokenIndex());
        ParseTree node = leaf == null ? null : leaf.getParent();
        while (node instanceof ParserRuleContext ctx) {
            Token first = realToken(tokens, ctx.getStart().getTokenIndex(), +1, ctx);
            Token last = ctx.getStop() == null ? null
                    : realToken(tokens, ctx.getStop().getTokenIndex(), -1, ctx);
            if (first != null && last != null && first.getTokenIndex() <= last.getTokenIndex()) {
                if (enclosesAll(tokens, first, last)) {
                    int in0 = first.getStopIndex() + 1, in1 = last.getStartIndex();
                    if (in0 < in1) spans.add(new int[] {in0, in1});
                }
                spans.add(new int[] {first.getStartIndex(), last.getStopIndex() + 1});
            }
            node = ctx.getParent();
        }
        return spans;
    }

    /** The real token covering {@code cp}; at a token's end (cursor just
     *  after a word) that token, unless another one starts there. */
    private static Token tokenAt(int cp, List<Token> tokens) {
        Token endingHere = null;
        for (Token t : tokens) {
            if (!isReal(t)) continue;
            if (t.getStartIndex() <= cp && cp <= t.getStopIndex()) return t;
            if (t.getStopIndex() + 1 == cp) endingHere = t;
            if (t.getStartIndex() > cp) break;
        }
        return endingHere;
    }

    /** First real token from {@code idx} stepping by {@code dir}, staying
     *  inside {@code ctx}'s token interval. */
    private static Token realToken(List<Token> tokens, int idx, int dir, ParserRuleContext ctx) {
        int lo = ctx.getStart().getTokenIndex();
        int hi = ctx.getStop() == null ? lo : ctx.getStop().getTokenIndex();
        for (int i = idx; i >= lo && i <= hi && i < tokens.size(); i += dir) {
            if (isReal(tokens.get(i))) return tokens.get(i);
        }
        return null;
    }

    private static boolean isReal(Token t) {
        if (t.getChannel() != Token.DEFAULT_CHANNEL) return false;
        int ty = t.getType();
        return ty != Token.EOF && ty != IrijLexer.INDENT && ty != IrijLexer.DEDENT
                && ty != IrijLexer.NEWLINE && ty != IrijLexer.NL
                && t.getStartIndex() <= t.getStopIndex();
    }

    /** {@code first} is an opening bracket whose match is {@code last}. */
    private static boolean enclosesAll(List<Token> tokens, Token first, Token last) {
        if (!isOpener(first) || !isCloser(last) || first == last) return false;
        int depth = 0;
        for (int i = first.getTokenIndex(); i <= last.getTokenIndex(); i++) {
            Token t = tokens.get(i);
            if (isOpener(t)) depth++;
            else if (isCloser(t) && --depth == 0) return i == last.getTokenIndex();
        }
        return false;
    }

    private static boolean isOpener(Token t) {
        int ty = t.getType();
        return ty == IrijLexer.LPAREN || ty == IrijLexer.LBRACKET || ty == IrijLexer.LBRACE
                || ty == IrijLexer.VEC_OPEN || ty == IrijLexer.SET_OPEN || ty == IrijLexer.TUPLE_OPEN;
    }

    private static boolean isCloser(Token t) {
        int ty = t.getType();
        return ty == IrijLexer.RPAREN || ty == IrijLexer.RBRACKET || ty == IrijLexer.RBRACE;
    }

    private static void collectTerminals(ParseTree t, Map<Integer, TerminalNode> out) {
        if (t instanceof TerminalNode tn) {
            out.put(tn.getSymbol().getTokenIndex(), tn);
            return;
        }
        for (int i = 0; i < t.getChildCount(); i++) collectTerminals(t.getChild(i), out);
    }

    /** Link innermost-first spans into an LSP parent chain, dropping
     *  spans that repeat or don't strictly contain the previous one. */
    private static SelectionRange chain(Text text, List<int[]> spans, int cp) {
        List<int[]> kept = new ArrayList<>();
        for (int[] s : spans) {
            if (s[0] > cp || s[1] < cp) continue;
            if (!kept.isEmpty()) {
                int[] prev = kept.get(kept.size() - 1);
                boolean contains = s[0] <= prev[0] && prev[1] <= s[1];
                boolean same = s[0] == prev[0] && s[1] == prev[1];
                if (!contains || same) continue;
            }
            kept.add(s);
        }
        if (kept.isEmpty()) kept.add(new int[] {cp, cp});
        SelectionRange parent = null;
        for (int i = kept.size() - 1; i >= 0; i--) {
            int[] s = kept.get(i);
            parent = new SelectionRange(new Range(text.position(s[0]), text.position(s[1])), parent);
        }
        return parent;
    }

    /** Code-point offsets ↔ LSP (line, UTF-16 column) positions. */
    static final class Text {
        private final int[] cps;
        private final int[] lines;
        private final int[] cols;

        Text(String source) {
            cps = source.codePoints().toArray();
            lines = new int[cps.length + 1];
            cols = new int[cps.length + 1];
            int line = 0, col = 0;
            for (int i = 0; i < cps.length; i++) {
                lines[i] = line;
                cols[i] = col;
                if (cps[i] == '\n') { line++; col = 0; }
                else col += Character.charCount(cps[i]);
            }
            lines[cps.length] = line;
            cols[cps.length] = col;
        }

        int cpLength() { return cps.length; }

        Position position(int cp) {
            int i = Math.max(0, Math.min(cp, cps.length));
            return new Position(lines[i], cols[i]);
        }

        /** Clamps past-the-end columns to the line end. */
        int cpOffset(Position p) {
            int i = 0;
            while (i < cps.length && lines[i] < p.getLine()) i++;
            while (i < cps.length && lines[i] == p.getLine() && cps[i] != '\n'
                    && cols[i] + Character.charCount(cps[i]) <= p.getCharacter()) i++;
            return i;
        }
    }
}
