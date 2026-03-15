package dev.irij.repl;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Environment;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values;
import dev.irij.interpreter.Values.BuiltinFn;
import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParseDriver;
import org.antlr.v4.runtime.Token;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Irij interactive REPL backed by JLine3.
 *
 * Usage:
 *   new IrijRepl().run();
 *
 * Supported REPL commands (prefix ':'):
 *   :quit / :q     — exit
 *   :reset         — clear the environment (fresh interpreter)
 *   :load <path>   — load and evaluate a source file
 *   :env           — show user-defined bindings
 *   :env all       — show all bindings (including builtins)
 *   :help          — show command list
 *   :type <expr>   — (stub) show inferred type
 */
public class IrijRepl {

    private static final String PROMPT_PRIMARY    = "ℑ> ";
    private static final String PROMPT_CONTINUE   = "  | ";
    private static final String HISTORY_FILE      = System.getProperty("user.home") + "/.irij_history";

    private Interpreter interpreter;
    private final Terminal terminal;
    private final PrintWriter out;

    public IrijRepl() throws IOException {
        this.interpreter = new Interpreter();
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(false)
                .build();
        this.out = terminal.writer();
    }

    // ── Entry point ─────────────────────────────────────────────────────

    public void run() {
        var reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, HISTORY_FILE)
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, PROMPT_CONTINUE)
                .build();

        out.println("Irij ℑ  — :help for commands, :quit to exit");
        out.flush();

        var pending = new StringBuilder();

        while (true) {
            String prompt = pending.isEmpty() ? PROMPT_PRIMARY : PROMPT_CONTINUE;
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // Ctrl-C: abandon current input
                pending.setLength(0);
                continue;
            } catch (EndOfFileException e) {
                // Ctrl-D: exit
                break;
            }

            if (line == null) break;

            // REPL commands are only recognised on a fresh (non-continuation) line
            if (pending.isEmpty() && line.startsWith(":")) {
                if (!handleCommand(line.trim(), reader)) break;
                continue;
            }

            pending.append(line).append("\n");

            // Wait for more input if the block is still open
            if (isBlockOpen(pending.toString())) {
                continue;
            }

            String input = pending.toString().trim();
            pending.setLength(0);

            if (!input.isEmpty()) {
                evalAndPrint(input);
            }
        }

        out.println("Bye!");
        out.flush();
        try { terminal.close(); } catch (IOException ignored) {}
    }

    // ── REPL commands ────────────────────────────────────────────────────

    /**
     * @return false if the REPL should exit
     */
    private boolean handleCommand(String cmd, LineReader reader) {
        if (cmd.equals(":quit") || cmd.equals(":q")) {
            return false;
        }
        if (cmd.equals(":help")) {
            out.println("""
                Commands:
                  :quit, :q        — exit
                  :reset           — clear environment (fresh interpreter)
                  :load <file>     — load and run a source file
                  :env             — show user-defined bindings
                  :env all         — show all bindings (including builtins)
                  :type <expr>     — show inferred type (not yet implemented)
                  :help            — this message""");
        } else if (cmd.equals(":reset")) {
            interpreter = new Interpreter(System.out);
            out.println("Environment cleared.");
        } else if (cmd.startsWith(":load ")) {
            loadFile(cmd.substring(6).trim());
        } else if (cmd.equals(":env") || cmd.equals(":env all")) {
            showEnv(cmd.equals(":env all"));
        } else if (cmd.startsWith(":type ")) {
            out.println(":type — type inference not yet implemented");
        } else {
            out.println("Unknown command: " + cmd + "  (try :help)");
        }
        out.flush();
        return true;
    }

    private void loadFile(String path) {
        try {
            var source = Files.readString(Path.of(path));
            evalAndPrint(source);
            out.println("Loaded: " + path);
        } catch (IOException e) {
            out.println("Cannot read file: " + e.getMessage());
        }
        out.flush();
    }

    // ── :env — show bindings ──────────────────────────────────────────────

    private void showEnv(boolean showAll) {
        var bindings = interpreter.getGlobalEnv().getBindings();
        var entries = bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        int count = 0;
        for (var entry : entries) {
            var cell = entry.getValue();
            Object value = switch (cell) {
                case Environment.ImmutableCell(var v) -> v;
                case Environment.MutableCell mc -> mc.get();
                case Environment.VarCell vc -> vc.get();
            };

            // Skip builtins unless :env all
            if (!showAll && value instanceof BuiltinFn) continue;
            // Skip boolean constants
            if (!showAll && (entry.getKey().equals("true") || entry.getKey().equals("false"))) continue;
            // Skip math constants
            if (!showAll && (entry.getKey().equals("pi") || entry.getKey().equals("e"))) continue;

            String typeName = Values.typeName(value);
            String cellKind = switch (cell) {
                case Environment.VarCell ignored -> "var";
                case Environment.MutableCell ignored -> "mut";
                case Environment.ImmutableCell ignored -> "";
            };
            String preview = Values.toIrijString(value);
            if (preview.length() > 60) preview = preview.substring(0, 57) + "...";

            String kindSuffix = cellKind.isEmpty() ? "" : " [" + cellKind + "]";
            out.printf("  %-20s : %-12s = %s%s%n", entry.getKey(), typeName, preview, kindSuffix);
            count++;
        }

        if (count == 0) {
            out.println("  (no bindings)");
        }
    }

    // ── Incomplete-input detection ────────────────────────────────────────

    /**
     * Returns true when the accumulated input ends mid-block (INDENT > DEDENT),
     * meaning the user should keep typing.
     */
    private boolean isBlockOpen(String input) {
        try {
            List<Token> tokens = IrijParseDriver.tokenize(input);
            int depth = 0;
            for (var tok : tokens) {
                String name = tokenName(tok);
                if ("INDENT".equals(name)) depth++;
                else if ("DEDENT".equals(name)) depth--;
            }
            return depth > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String tokenName(Token tok) {
        int type = tok.getType();
        if (type == Token.EOF) return "<EOF>";
        try {
            var vocab = IrijLexer.VOCABULARY;
            String name = vocab.getSymbolicName(type);
            return name != null ? name : String.valueOf(type);
        } catch (Exception e) {
            return String.valueOf(type);
        }
    }

    // ── Evaluate & print ─────────────────────────────────────────────────

    private void evalAndPrint(String input) {
        try {
            var result = IrijParseDriver.parse(input);
            if (result.hasErrors()) {
                for (var err : result.errors()) {
                    out.println("Parse error: " + err);
                }
                out.flush();
                return;
            }
            var ast = new AstBuilder().build(result.tree());
            var value = interpreter.run(ast);
            if (value != Values.UNIT) {
                out.println("=> " + Values.toIrijString(value));
            }
        } catch (IrijRuntimeError e) {
            out.println("Runtime error: " + e.getMessage());
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
        }
        out.flush();
    }
}
