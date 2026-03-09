package dev.irij.cli;

import dev.irij.interpreter.*;
import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;

/**
 * Reusable parse → check → interpret pipeline.
 */
public final class Pipeline {

    private Pipeline() {}

    /**
     * Parse source string into a compilation unit.
     * Returns null and prints errors to stderr on failure.
     */
    public static IrijParser.CompilationUnitContext parse(String source) {
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
            return null;
        }
        return cu;
    }

    /**
     * Run type and purity checks on a parsed compilation unit.
     * Returns 0 on success, 3 on purity errors.
     */
    public static int check(IrijParser.CompilationUnitContext cu, PurityMode purityMode) {
        var checker = new TypeChecker();
        checker.setPurityMode(purityMode);
        var warnings = checker.check(cu);
        for (var warning : warnings) {
            System.err.println(warning);
        }

        var purityErrors = checker.getErrors();
        if (!purityErrors.isEmpty()) {
            for (var error : purityErrors) {
                System.err.println(error);
            }
            System.err.printf("%d purity error(s) — use --warn-impure or --allow-impure to bypass%n",
                    purityErrors.size());
            return 3;
        }
        return 0;
    }

    /**
     * Script pipeline: parse → check → interpret in script mode (no main required).
     * Top-level bindings are evaluated directly; main() is called if it exists.
     * Returns exit code: 0=success, 1=parse error, 2=runtime error, 3=purity error.
     */
    public static int interpretScript(String source, PurityMode purityMode) {
        var cu = parse(source);
        if (cu == null) return 1;

        int checkResult = check(cu, purityMode);
        if (checkResult != 0) return checkResult;

        try {
            var interpreter = new IrijInterpreter();
            interpreter.executeScript(cu);
            return 0;
        } catch (IrijRuntimeError e) {
            System.err.println("Runtime error: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Full pipeline: parse → check → interpret.
     * Returns exit code: 0=success, 1=parse error, 2=runtime error, 3=purity error.
     */
    public static int interpret(String source, PurityMode purityMode) {
        var cu = parse(source);
        if (cu == null) return 1;

        int checkResult = check(cu, purityMode);
        if (checkResult != 0) return checkResult;

        try {
            var interpreter = new IrijInterpreter();
            interpreter.execute(cu);
            return 0;
        } catch (IrijRuntimeError e) {
            System.err.println("Runtime error: " + e.getMessage());
            return 2;
        }
    }
}
