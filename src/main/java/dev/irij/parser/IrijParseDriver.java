package dev.irij.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI driver to parse Irij source and print the parse tree.
 *
 * <p>Usage:
 * <pre>
 *   # Parse a file
 *   java dev.irij.parser.IrijParseDriver path/to/file.irj
 *
 *   # Parse inline code
 *   java dev.irij.parser.IrijParseDriver -e 'x := 42'
 *
 *   # Dump tokens only (no parse)
 *   java dev.irij.parser.IrijParseDriver -t 'x := 42'
 * </pre>
 */
public final class IrijParseDriver {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: IrijParseDriver [-e <code> | -t <code> | <file>]");
            System.exit(1);
        }

        String source;
        String mode = "parse";

        if ("-e".equals(args[0]) && args.length > 1) {
            source = args[1];
        } else if ("-t".equals(args[0]) && args.length > 1) {
            source = args[1];
            mode = "tokens";
        } else {
            source = Files.readString(Path.of(args[0]));
        }

        var lexer = new IrijLexer(CharStreams.fromString(source));

        if ("tokens".equals(mode)) {
            dumpTokens(lexer);
            return;
        }

        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);

        // Use descriptive error messages
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                System.err.printf("line %d:%d  %s%n", line, charPositionInLine, msg);
            }
        });

        ParseTree tree = parser.compilationUnit();

        // Print LISP-style tree
        System.out.println(Trees.toStringTree(tree, parser));

        // Print indented tree for readability
        System.out.println("\n─── Indented Parse Tree ───");
        printTree(tree, parser, 0);

        int errors = parser.getNumberOfSyntaxErrors();
        if (errors > 0) {
            System.err.printf("%n%d syntax error(s)%n", errors);
            System.exit(1);
        }
    }

    private static void dumpTokens(IrijLexer lexer) {
        var vocab = lexer.getVocabulary();
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
            String name = vocab.getSymbolicName(t.getType());
            if (name == null) name = vocab.getDisplayName(t.getType());
            String text = t.getText().replace("\n", "\\n").replace("\r", "\\r");
            String ch = t.getChannel() == Token.HIDDEN_CHANNEL ? " (hidden)" : "";
            System.out.printf("%-4d %-20s %-20s%s%n", t.getLine(), name, "'" + text + "'", ch);
        }
    }

    private static void printTree(ParseTree tree, Parser parser, int indent) {
        String nodeText = Trees.getNodeText(tree, parser);
        if (tree.getChildCount() == 0) {
            System.out.println("  ".repeat(indent) + nodeText);
        } else {
            System.out.println("  ".repeat(indent) + nodeText);
            for (int i = 0; i < tree.getChildCount(); i++) {
                printTree(tree.getChild(i), parser, indent + 1);
            }
        }
    }
}
