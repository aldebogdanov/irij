package dev.irij.parser;

import org.antlr.v4.runtime.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Lexer base class that post-processes tokens to inject synthetic
 * INDENT, DEDENT, and NEWLINE tokens based on leading whitespace.
 *
 * <p>Irij uses strict 2-space indentation:
 * <ul>
 *   <li>Each indent level is exactly 2 spaces.</li>
 *   <li>Odd indentation or non-2-space increments cause a hard parse error.</li>
 *   <li>Tabs are forbidden in leading whitespace.</li>
 * </ul>
 *
 * <p>Algorithm (Python-style):
 * <ol>
 *   <li>Track indent stack (starts at [0]).</li>
 *   <li>On each newline, measure leading spaces of the next line.</li>
 *   <li>If indent &gt; top of stack → emit INDENT, push new level.</li>
 *   <li>If indent == top of stack → emit NEWLINE.</li>
 *   <li>If indent &lt; top of stack → emit DEDENT(s) + NEWLINE until stack matches.</li>
 *   <li>At EOF, emit remaining DEDENTs.</li>
 * </ol>
 *
 * <p>Line continuation: a trailing {@code \} before newline suppresses
 * the newline/indent processing — the next line is joined to the current.
 */
public abstract class IrijLexerBase extends Lexer {

    /** Indent stack: always has at least [0]. */
    private final Deque<Integer> indentStack = new ArrayDeque<>();

    /** Pending synthetic tokens to emit before the next real token. */
    private final Queue<Token> pendingTokens = new LinkedList<>();

    /** Whether we are at the start of the file (no NEWLINE before first line). */
    private boolean atStartOfInput = true;

    /** Track previous token type for line-continuation detection. */
    private int lastRealTokenType = Token.INVALID_TYPE;

    /** Track paren/bracket nesting depth — suppress INDENT/DEDENT inside. */
    private int parenDepth = 0;

    public IrijLexerBase(CharStream input) {
        super(input);
        indentStack.push(0);
    }

    @Override
    public Token nextToken() {
        // Drain pending tokens first.
        if (!pendingTokens.isEmpty()) {
            return pendingTokens.poll();
        }

        Token token = super.nextToken();

        // Track paren/bracket nesting — inside parens, no INDENT/DEDENT.
        if (token.getType() == IrijLexer.LPAREN || token.getType() == IrijLexer.LBRACKET
            || token.getType() == IrijLexer.LBRACE || token.getType() == IrijLexer.VEC_OPEN
            || token.getType() == IrijLexer.SET_OPEN || token.getType() == IrijLexer.TUPLE_OPEN) {
            parenDepth++;
            lastRealTokenType = token.getType();
            return token;
        }
        if (token.getType() == IrijLexer.RPAREN || token.getType() == IrijLexer.RBRACKET
            || token.getType() == IrijLexer.RBRACE) {
            parenDepth = Math.max(0, parenDepth - 1);
            lastRealTokenType = token.getType();
            return token;
        }

        // Handle newlines.
        if (token.getType() == IrijLexer.NL) {
            return handleNewline(token);
        }

        // Handle EOF — emit remaining DEDENTs.
        if (token.getType() == Token.EOF) {
            return handleEof(token);
        }

        // Skip WS and COMMENT (they're on HIDDEN channel already, but just in case).
        if (token.getChannel() == Token.HIDDEN_CHANNEL) {
            lastRealTokenType = token.getType();
            return token;
        }

        // Any other real token.
        atStartOfInput = false;
        lastRealTokenType = token.getType();
        return token;
    }

    private Token handleNewline(Token nlToken) {
        // Inside parentheses/brackets — suppress newlines entirely.
        if (parenDepth > 0) {
            ((CommonToken) nlToken).setChannel(Token.HIDDEN_CHANNEL);
            return nextToken();
        }

        // Line continuation: if previous real token was BACKSLASH, suppress newline.
        if (lastRealTokenType == IrijLexer.BACKSLASH) {
            ((CommonToken) nlToken).setChannel(Token.HIDDEN_CHANNEL);
            return nextToken();
        }

        // Consume all blank lines and find the indentation of the next non-empty line.
        // We peek ahead at the input to measure leading whitespace.
        int indent = measureNextIndent();

        // If indent is -1, we hit EOF — let next nextToken() call handle it.
        if (indent == -1) {
            return nextToken();
        }

        // At start of input, just set the base indent level (no NEWLINE).
        if (atStartOfInput) {
            atStartOfInput = false;
            if (indent > 0) {
                // File starts indented — unusual but handle it.
                validateIndent(indent, nlToken);
                indentStack.push(indent);
                return makeToken(IrijLexer.INDENT, nlToken);
            }
            return nextToken();
        }

        int currentIndent = indentStack.peek();

        if (indent > currentIndent) {
            // Implicit continuation: if the more-indented line starts with
            // a binary operator, suppress NEWLINE+INDENT (join to previous line).
            if (nextLineStartsWithBinaryOp()) {
                ((CommonToken) nlToken).setChannel(Token.HIDDEN_CHANNEL);
                return nextToken();
            }
            // Deeper indent — emit NEWLINE + INDENT.
            validateIndent(indent, nlToken);
            indentStack.push(indent);
            pendingTokens.add(makeToken(IrijLexer.INDENT, nlToken));
            return makeToken(IrijLexer.NEWLINE, nlToken);
        } else if (indent == currentIndent) {
            // Same level — emit NEWLINE.
            return makeToken(IrijLexer.NEWLINE, nlToken);
        } else {
            // Dedent — emit DEDENT(s) + NEWLINE.
            // Pop indent levels until we match.
            while (indentStack.peek() > indent) {
                indentStack.pop();
                pendingTokens.add(makeToken(IrijLexer.DEDENT, nlToken));
            }
            if (indentStack.peek() != indent) {
                // Indentation doesn't match any outer level — error.
                notifyListeners(new LexerNoViableAltException(this, _input, nlToken.getStartIndex(), null));
            }
            pendingTokens.add(makeToken(IrijLexer.NEWLINE, nlToken));
            return pendingTokens.poll();
        }
    }

    private Token handleEof(Token eofToken) {
        // First emit a trailing NEWLINE if we haven't already.
        if (!atStartOfInput && lastRealTokenType != IrijLexer.NL
            && lastRealTokenType != IrijLexer.NEWLINE) {
            pendingTokens.add(makeToken(IrijLexer.NEWLINE, eofToken));
        }

        // Emit DEDENTs for all remaining indent levels.
        while (indentStack.size() > 1) {
            indentStack.pop();
            pendingTokens.add(makeToken(IrijLexer.DEDENT, eofToken));
        }

        // Finally, the EOF itself.
        pendingTokens.add(eofToken);
        return pendingTokens.poll();
    }

    /**
     * Check whether the next non-whitespace characters on the upcoming line
     * form the start of a binary operator. Called after measureNextIndent()
     * has consumed leading whitespace, so _input.LA(1) is the first real char.
     *
     * <p>Operators that trigger implicit continuation:
     * {@code |> || <| << <= < >> >= > == ++ + * ** % && .. ..<}
     *
     * <p>NOT included (ambiguous as unary/prefix):
     * {@code -} (unary negation), {@code /} (seq ops like /? /+ /!)
     */
    private boolean nextLineStartsWithBinaryOp() {
        int ch1 = _input.LA(1);
        int ch2 = _input.LA(2);
        return switch (ch1) {
            case '|' -> true;           // |>  ||
            case '&' -> ch2 == '&';     // &&
            case '+' -> true;           // +  ++
            case '*' -> true;           // *  **
            case '%' -> true;           // %
            case '<' -> true;           // <  <=  <<  <|
            case '>' -> true;           // >  >=  >>
            case '=' -> ch2 == '=';     // ==
            case '.' -> ch2 == '.';     // ..  ..<
            default  -> false;
        };
    }

    /**
     * Measure leading whitespace of the next non-empty line by peeking
     * at the char stream. Consumes blank lines and whitespace-only lines.
     * Returns -1 if EOF is reached.
     */
    private int measureNextIndent() {
        CharStream input = _input;

        while (true) {
            int spaces = 0;
            boolean seenNonWs = false;

            while (true) {
                int ch = input.LA(1);
                if (ch == ' ') {
                    spaces++;
                    input.consume();
                } else if (ch == '\t') {
                    // Tabs are forbidden in leading whitespace.
                    Token dummy = _factory.create(
                        _tokenFactorySourcePair, IrijLexer.WS,
                        "\t", Token.DEFAULT_CHANNEL,
                        input.index(), input.index(),
                        getLine(), getCharPositionInLine()
                    );
                    notifyListeners(new LexerNoViableAltException(this, input, input.index(), null));
                    input.consume();
                } else if (ch == '\r' || ch == '\n') {
                    // Blank line — skip and retry.
                    if (ch == '\r') { input.consume(); if (input.LA(1) == '\n') input.consume(); }
                    else input.consume();
                    break; // restart indent measurement for the next line
                } else if (ch == IntStream.EOF) {
                    return -1;
                } else if (ch == ';' && input.LA(2) == ';') {
                    // Comment — consume until end of line, then treat as blank.
                    while (input.LA(1) != '\n' && input.LA(1) != '\r' && input.LA(1) != IntStream.EOF) {
                        input.consume();
                    }
                    continue; // will hit the \n check above next iteration
                } else {
                    seenNonWs = true;
                    break;
                }
            }

            if (seenNonWs) {
                return spaces;
            }
            // If we looped (blank line), continue to next line.
            if (input.LA(1) == IntStream.EOF) return -1;
        }
    }

    /**
     * Validate that indentation is a multiple of 2.
     * Irij enforces strict 2-space indentation.
     */
    private void validateIndent(int indent, Token refToken) {
        if (indent % 2 != 0) {
            // Hard error: odd indentation.
            String msg = "Indentation must be a multiple of 2 spaces, got " + indent;
            ANTLRErrorListener[] listeners = getErrorListeners().toArray(new ANTLRErrorListener[0]);
            for (ANTLRErrorListener listener : listeners) {
                listener.syntaxError(this, null,
                    refToken.getLine(), refToken.getCharPositionInLine(), msg, null);
            }
        }
    }

    /**
     * Create a synthetic token (INDENT, DEDENT, or NEWLINE) at the
     * position of a reference token.
     */
    private Token makeToken(int type, Token ref) {
        String text = switch (type) {
            case IrijLexer.INDENT  -> "<INDENT>";
            case IrijLexer.DEDENT  -> "<DEDENT>";
            case IrijLexer.NEWLINE -> "<NEWLINE>";
            default -> "<SYNTHETIC>";
        };

        CommonToken token = new CommonToken(
            _tokenFactorySourcePair, type, Token.DEFAULT_CHANNEL,
            ref.getStartIndex(), ref.getStopIndex()
        );
        token.setText(text);
        token.setLine(ref.getLine());
        token.setCharPositionInLine(ref.getCharPositionInLine());
        return token;
    }
}
