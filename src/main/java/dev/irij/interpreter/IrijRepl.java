package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.*;

import java.io.IOException;
import java.util.*;

/**
 * Interactive REPL for Irij.
 *
 * <p>Features:
 * <ul>
 *   <li>Parse and evaluate expressions directly (no main function required)
 *   <li>Accumulate bindings across inputs
 *   <li>Print results with show()
 *   <li>Multi-line input (trailing backslash or incomplete expressions)
 *   <li>:help, :quit, :type commands
 * </ul>
 */
public final class IrijRepl {

    private final IrijInterpreter interpreter;

    public IrijRepl() {
        this.interpreter = new IrijInterpreter();
        // Push a persistent scope for REPL bindings
        interpreter.pushScope();
    }

    public void run() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new DefaultParser())
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "  ... ")
                .build();

        System.out.println("Irij " + version() + " REPL");
        System.out.println("Type :help for help, :quit to exit.");
        System.out.println();

        while (true) {
            String line;
            try {
                line = reader.readLine("irij> ");
            } catch (UserInterruptException e) {
                continue; // Ctrl-C clears current input
            } catch (EndOfFileException e) {
                break; // Ctrl-D exits
            }

            if (line == null || line.isBlank()) continue;
            line = line.strip();

            // REPL commands
            if (line.startsWith(":")) {
                if (handleCommand(line)) continue;
                else break; // :quit
            }

            // Accumulate multi-line input
            var input = new StringBuilder(line);
            while (isIncomplete(input.toString())) {
                try {
                    String continuation = reader.readLine("  ... ");
                    if (continuation == null) break;
                    input.append('\n').append(continuation);
                } catch (UserInterruptException e) {
                    input.setLength(0);
                    break;
                } catch (EndOfFileException e) {
                    break;
                }
            }

            if (input.isEmpty()) continue;
            eval(input.toString());
        }

        System.out.println("Goodbye!");
    }

    /**
     * Handle a REPL command. Returns true to continue, false to quit.
     */
    private boolean handleCommand(String cmd) {
        return switch (cmd.split("\\s+", 2)[0]) {
            case ":quit", ":q", ":exit" -> false;
            case ":help", ":h" -> {
                printHelp();
                yield true;
            }
            case ":type", ":t" -> {
                String expr = cmd.length() > cmd.indexOf(' ') + 1
                        ? cmd.substring(cmd.indexOf(' ') + 1).strip() : "";
                if (!expr.isEmpty()) {
                    typeOf(expr);
                } else {
                    System.out.println("Usage: :type <expr>");
                }
                yield true;
            }
            case ":clear" -> {
                interpreter.popScope();
                interpreter.pushScope();
                System.out.println("Scope cleared.");
                yield true;
            }
            default -> {
                System.out.println("Unknown command: " + cmd + " (try :help)");
                yield true;
            }
        };
    }

    private void printHelp() {
        System.out.println("""
            Irij REPL Commands:
              :help, :h       Show this help
              :quit, :q       Exit the REPL
              :type, :t <e>   Show the inferred type of an expression
              :clear          Clear all bindings

            Examples:
              x := 42
              x + 8
              #[1 2 3] |> /+
              (x -> x * 2) 5
            """);
    }

    /**
     * Evaluate input in the REPL context.
     */
    private void eval(String input) {
        try {
            // First try as a binding (x := expr)
            if (input.contains(":=") || input.contains(":!")) {
                evalBinding(input);
                return;
            }

            // Try as a top-level declaration (fn, type, effect, handler)
            if (input.startsWith("fn ") || input.startsWith("type ")
                    || input.startsWith("effect ") || input.startsWith("handler ")
                    || input.startsWith("use ")) {
                evalDeclaration(input);
                return;
            }

            // Otherwise evaluate as an expression
            evalExpression(input);
        } catch (IrijRuntimeError e) {
            System.err.println("Error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void evalBinding(String input) {
        // Wrap in a function body context for parsing
        String source = wrapInFunction(input);
        var cu = parse(source);
        if (cu == null) return;

        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                var body = decl.fnDecl().fnBody();
                if (body != null) {
                    for (var stmt : body.statement()) {
                        if (stmt.binding() != null) {
                            interpreter.evalBinding(stmt.binding());
                        }
                    }
                }
            }
        }
    }

    private void evalDeclaration(String input) {
        var cu = parse(input);
        if (cu == null) return;

        for (var decl : cu.topLevelDecl()) {
            interpreter.collectDeclaration(decl);
        }
        System.out.println("Defined.");
    }

    private void evalExpression(String input) {
        // Wrap in a function body for parsing
        String source = wrapInFunction(input);
        var cu = parse(source);
        if (cu == null) return;

        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                var body = decl.fnDecl().fnBody();
                if (body != null) {
                    Object result = null;
                    for (var stmt : body.statement()) {
                        result = interpreter.evalStatement(stmt);
                    }
                    if (result != null && result != IrijInterpreter.UNIT) {
                        System.out.println(Stdlib.show(result));
                    }
                }
            }
        }
    }

    private void typeOf(String exprStr) {
        String source = wrapInFunction(exprStr);
        var cu = parse(source);
        if (cu == null) return;

        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                var body = decl.fnDecl().fnBody();
                if (body != null && !body.statement().isEmpty()) {
                    var stmt = body.statement(0);
                    if (stmt.expr() != null) {
                        var checker = new TypeChecker();
                        var type = checker.inferType(stmt.expr());
                        System.out.println(type);
                    }
                }
            }
        }
    }

    private IrijParser.CompilationUnitContext parse(String source) {
        var lexer = new IrijLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);
        parser.removeErrorListeners();

        var errors = new StringBuilder();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym,
                                    int line, int col, String msg, RecognitionException e) {
                errors.append(String.format("Parse error at %d:%d — %s%n", line, col, msg));
            }
        });

        var cu = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() > 0) {
            System.err.println(errors.toString().stripTrailing());
            return null;
        }
        return cu;
    }

    /**
     * Check if input looks incomplete (needs more lines).
     */
    private boolean isIncomplete(String input) {
        // Trailing backslash
        if (input.endsWith("\\")) return true;
        // Unmatched brackets/parens
        int parens = 0, brackets = 0, braces = 0;
        boolean inString = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' && (i == 0 || input.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                switch (c) {
                    case '(' -> parens++;
                    case ')' -> parens--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                    case '{' -> braces++;
                    case '}' -> braces--;
                }
            }
        }
        return parens > 0 || brackets > 0 || braces > 0;
    }

    /**
     * Wrap REPL input as a function body for parsing.
     * Indents the input by 2 spaces to form a valid function body.
     */
    private String wrapInFunction(String input) {
        String indented = "  " + input.replace("\n", "\n  ");
        return "fn repl-eval :: () -> ()\n" + indented;
    }

    private String version() {
        return "0.1.0-SNAPSHOT";
    }
}
