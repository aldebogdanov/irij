package dev.irij.parser;

import org.antlr.v4.runtime.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;

/**
 * Base class for the Irij lexer that handles Python-style INDENT/DEDENT token emission.
 *
 * <p>Strategy: buffer all tokens from the underlying lexer, then post-process the
 * stream to inject INDENT/DEDENT tokens at appropriate NEWLINE boundaries.
 * This avoids the fragility of trying to emit synthetic tokens mid-lex.
 */
public abstract class IrijLexerBase extends Lexer {

    private final LinkedList<Token> tokenQueue = new LinkedList<>();
    private boolean initialized = false;

    public IrijLexerBase(CharStream input) {
        super(input);
    }

    @Override
    public Token nextToken() {
        if (!initialized) {
            initialize();
        }
        if (tokenQueue.isEmpty()) {
            return new CommonToken(Token.EOF, "<EOF>");
        }
        return tokenQueue.poll();
    }

    /**
     * Collect all raw tokens, then inject INDENT/DEDENT.
     */
    private void initialize() {
        initialized = true;

        // 1. Collect all raw tokens from the underlying lexer
        LinkedList<Token> raw = new LinkedList<>();
        Token t;
        do {
            t = super.nextToken();
            raw.add(t);
        } while (t.getType() != Token.EOF);

        // 2. Post-process: inject INDENT/DEDENT after NEWLINE tokens
        Deque<Integer> indentStack = new ArrayDeque<>();
        indentStack.push(0);

        for (int i = 0; i < raw.size(); i++) {
            Token tok = raw.get(i);

            if (tok.getType() == Token.EOF) {
                // Emit a final NEWLINE if the last real token wasn't one
                if (!tokenQueue.isEmpty()) {
                    Token last = tokenQueue.peekLast();
                    if (last.getType() != IrijLexer.NEWLINE) {
                        tokenQueue.add(makeToken(IrijLexer.NEWLINE, "\n", tok));
                    }
                }
                // Close all open indentation levels
                while (indentStack.size() > 1) {
                    indentStack.pop();
                    tokenQueue.add(makeToken(IrijLexer.DEDENT, "", tok));
                }
                tokenQueue.add(tok);
                break;
            }

            if (tok.getType() == IrijLexer.NEWLINE) {
                // Compute indent of the next non-blank line
                int indent = computeIndentFromNewline(tok.getText());

                // Look ahead: skip blank lines (newlines followed by more newlines)
                Token nextReal = peekNextDefault(raw, i + 1);
                if (nextReal != null && nextReal.getType() == IrijLexer.NEWLINE) {
                    // This newline precedes a blank line — emit NEWLINE, no indent change
                    tokenQueue.add(tok);
                    continue;
                }
                if (nextReal != null && nextReal.getType() == Token.EOF) {
                    // End of file — just emit newline
                    tokenQueue.add(tok);
                    continue;
                }

                // Emit the NEWLINE token
                tokenQueue.add(tok);

                // Compare indent to current level
                int currentIndent = indentStack.peek();
                if (indent > currentIndent) {
                    indentStack.push(indent);
                    tokenQueue.add(makeToken(IrijLexer.INDENT, "", tok));
                } else if (indent < currentIndent) {
                    while (indentStack.size() > 1 && indentStack.peek() > indent) {
                        indentStack.pop();
                        tokenQueue.add(makeToken(IrijLexer.DEDENT, "", tok));
                    }
                    // Emit a synthetic NEWLINE after DEDENT so subsequent
                    // statements at the same indent level are properly separated.
                    tokenQueue.add(makeToken(IrijLexer.NEWLINE, "\n", tok));
                }
                // indent == currentIndent: no action (same-level statement)
            } else {
                tokenQueue.add(tok);
            }
        }
    }

    /**
     * Look ahead for the next token on the default channel.
     */
    private Token peekNextDefault(LinkedList<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL || t.getType() == Token.EOF) {
                return t;
            }
        }
        return null;
    }

    /**
     * Count trailing spaces in a NEWLINE token text.
     * NEWLINE text is something like "\n  " or "\r\n    ".
     */
    private int computeIndentFromNewline(String text) {
        int spaces = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == ' ') {
                spaces++;
            } else {
                break; // hit \n or \r
            }
        }
        return spaces;
    }

    private Token makeToken(int type, String text, Token reference) {
        CommonToken token = new CommonToken(type, text);
        token.setLine(reference.getLine());
        token.setCharPositionInLine(reference.getCharPositionInLine());
        token.setStartIndex(reference.getStartIndex());
        token.setStopIndex(reference.getStopIndex());
        token.setTokenIndex(-1);
        return token;
    }

    /**
     * Called by lexer action on NEWLINE rule — no-op now since we post-process.
     */
    protected void onNewLine() {
        // Handled in post-processing (initialize method)
    }

    /**
     * Reports an error for tab characters.
     */
    protected void reportTabError() {
        notifyListeners(new LexerNoViableAltException(this, _input, _tokenStartCharIndex, null));
    }
}
