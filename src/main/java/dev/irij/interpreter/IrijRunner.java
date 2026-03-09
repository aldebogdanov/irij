package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point to run Irij programs.
 *
 * <p>Usage:
 * <pre>
 *   java dev.irij.interpreter.IrijRunner path/to/file.irj
 *   java dev.irij.interpreter.IrijRunner --warn-impure path/to/file.irj
 *   java dev.irij.interpreter.IrijRunner --allow-impure -e 'fn main :: ...'
 *   java dev.irij.interpreter.IrijRunner         (no args → REPL)
 * </pre>
 *
 * <p>Purity flags:
 * <ul>
 *   <li>{@code --strict} — direct IO outside handlers is an error (default)
 *   <li>{@code --warn-impure} — direct IO produces a warning but still runs
 *   <li>{@code --allow-impure} — no purity checks
 * </ul>
 */
public final class IrijRunner {

    public static void main(String[] args) throws IOException {
        // Separate flags from positional args
        PurityMode purityMode = PurityMode.STRICT;
        List<String> positional = new ArrayList<>();

        for (String arg : args) {
            switch (arg) {
                case "--strict", "--warn-impure", "--allow-impure" ->
                        purityMode = PurityMode.fromFlag(arg);
                default -> positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            new IrijRepl().run();
            return;
        }

        String source;
        if ("-e".equals(positional.get(0)) && positional.size() > 1) {
            source = positional.get(1);
        } else {
            source = Files.readString(Path.of(positional.get(0)));
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

        // Type check + purity check
        var checker = new TypeChecker();
        checker.setPurityMode(purityMode);
        var warnings = checker.check(cu);
        for (var warning : warnings) {
            System.err.println(warning);
        }

        // Purity errors are fatal in strict mode
        var purityErrors = checker.getErrors();
        if (!purityErrors.isEmpty()) {
            for (var error : purityErrors) {
                System.err.println(error);
            }
            System.err.printf("%d purity error(s) — use --warn-impure or --allow-impure to bypass%n",
                    purityErrors.size());
            System.exit(3);
        }

        // Interpret
        try {
            var interpreter = new IrijInterpreter();
            if ("-e".equals(positional.get(0))) {
                interpreter.executeScript(cu);
            } else {
                interpreter.execute(cu);
            }
        } catch (IrijRuntimeError e) {
            System.err.println("Runtime error: " + e.getMessage());
            System.exit(2);
        }
    }
}
