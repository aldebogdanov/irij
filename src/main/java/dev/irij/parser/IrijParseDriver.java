package dev.irij.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires up the Irij lexer and parser.
 * Provides convenient methods for parsing strings and files,
 * collecting errors, and inspecting token streams.
 */
public class IrijParseDriver {

    /** Bracket nesting deeper than this is refused before parsing. */
    static final int MAX_NESTING = Integer.getInteger("irij.parse.max.nesting", 512);

    /** Parse result: tree + errors. */
    public record ParseResult(
        IrijParser.CompilationUnitContext tree,
        List<String> errors,
        CommonTokenStream tokenStream
    ) {
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /**
     * Parse Irij source code from a string.
     */
    public static ParseResult parse(String source) {
        return parse(CharStreams.fromString(source));
    }

    /**
     * Parse Irij source code from a file.
     */
    public static ParseResult parseFile(Path path) throws IOException {
        return parse(CharStreams.fromString(Files.readString(path), path.toString()));
    }

    /**
     * Core parse method.
     */
    private static ParseResult parse(CharStream input) {
        List<String> errors = new ArrayList<>();

        ANTLRErrorListener errorCollector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                errors.add(line + ":" + charPositionInLine + " " + msg);
            }
        };

        IrijLexer lexer = new IrijLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorCollector);

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // ANTLR's prediction recurses — and allocates — per nesting level:
        // a few thousand nested brackets overflow the stack, and ~20 000
        // (a 40 KB file) exhaust a 256 MB heap before that. Real code
        // nests a few dozen deep, so refuse absurd nesting up front, from
        // a linear pass over the tokens.
        tokens.fill();
        int depth = 0;
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            String text = t.getText();
            if (text == null || text.isEmpty() || t.getType() == IrijLexer.STRING) continue;
            char last = text.charAt(text.length() - 1);
            if (last == '(' || last == '[' || last == '{') {
                if (++depth > MAX_NESTING) {
                    errors.add(t.getLine() + ":" + t.getCharPositionInLine()
                            + " expression nested too deeply to parse (more than "
                            + MAX_NESTING + " levels)");
                    return new ParseResult(null, errors, tokens);
                }
            } else if (text.length() == 1 && (last == ')' || last == ']' || last == '}')) {
                depth = Math.max(0, depth - 1);
            }
        }

        IrijParser parser = new IrijParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorCollector);

        IrijParser.CompilationUnitContext tree;
        try {
            tree = parser.compilationUnit();
        } catch (StackOverflowError e) {
            // ANTLR's prediction recurses per nesting level; thousands of
            // nested parens exhaust the stack. Report it as a parse error
            // rather than let the Error escape (it ended the LSP request
            // and dumped a JVM trace from the CLI).
            errors.add("1:0 expression nested too deeply to parse");
            tree = null;
        }

        return new ParseResult(tree, errors, tokens);
    }

    /**
     * Lex only — returns all tokens (for debugging / smoke tests).
     */
    public static List<Token> tokenize(String source) {
        IrijLexer lexer = new IrijLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens.getTokens();
    }

    /**
     * Format a token for debugging: TYPE_NAME(text).
     */
    public static String tokenToString(Token token, IrijLexer lexer) {
        String typeName = lexer.getVocabulary().getSymbolicName(token.getType());
        if (typeName == null) typeName = String.valueOf(token.getType());
        if (token.getType() == Token.EOF) return "<EOF>";
        return typeName + "(" + token.getText().replace("\n", "\\n") + ")";
    }
}
