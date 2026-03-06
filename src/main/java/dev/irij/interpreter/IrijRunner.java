package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point to run Irij programs.
 *
 * <p>Usage:
 * <pre>
 *   java dev.irij.interpreter.IrijRunner path/to/file.irj
 *   java dev.irij.interpreter.IrijRunner -e 'fn main :: () -[IO]-> () ...'
 * </pre>
 */
public final class IrijRunner {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            new IrijRepl().run();
            return;
        }

        String source;
        if ("-e".equals(args[0]) && args.length > 1) {
            source = args[1];
        } else {
            source = Files.readString(Path.of(args[0]));
        }

        // Parse
        var lexer = new IrijLexer(CharStreams.fromString(source));
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);

        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                System.err.printf("Parse error at line %d:%d — %s%n", line, charPositionInLine, msg);
            }
        });

        var cu = parser.compilationUnit();

        if (parser.getNumberOfSyntaxErrors() > 0) {
            System.err.printf("%d parse error(s)%n", parser.getNumberOfSyntaxErrors());
            System.exit(1);
        }

        // Type check (warnings only)
        var checker = new TypeChecker();
        var warnings = checker.check(cu);
        for (var warning : warnings) {
            System.err.println(warning);
        }

        // Interpret
        try {
            var interpreter = new IrijInterpreter();
            interpreter.execute(cu);
        } catch (IrijRuntimeError e) {
            System.err.println("Runtime error: " + e.getMessage());
            System.exit(2);
        }
    }
}
