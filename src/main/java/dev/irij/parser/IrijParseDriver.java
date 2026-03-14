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

        IrijParser parser = new IrijParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorCollector);

        IrijParser.CompilationUnitContext tree = parser.compilationUnit();

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
