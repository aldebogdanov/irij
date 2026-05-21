package dev.irij.repl;

import dev.irij.compiler.BytecodeSession;
import dev.irij.compiler.IrijCompiler;
import dev.irij.IrijRuntimeError;
import dev.irij.interpreter.Values;
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
 * <p>v0.6.13: bytecode-only. Each input line compiles to a fresh
 * class via {@link BytecodeSession}; top-level binds persist across
 * inputs through the session's shared namespace map.
 *
 * Supported REPL commands (prefix ':'):
 *   :quit / :q     — exit
 *   :reset         — drop all session bindings
 *   :load <path>   — load and evaluate a source file
 *   :env           — show user-defined bindings
 *   :help          — show command list
 */
public class IrijRepl {

    private static final String PROMPT_PRIMARY    = "ℑ> ";
    private static final String PROMPT_CONTINUE   = " | ";
    private static final String HISTORY_FILE      = System.getProperty("user.home") + "/.irij_history";

    private BytecodeSession session;
    private final Terminal terminal;
    private final PrintWriter out;

    public IrijRepl() throws IOException {
        this.session = new BytecodeSession("irij.Repl");
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(false)
                .build();
        this.out = terminal.writer();
    }

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
                pending.setLength(0);
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;

            if (pending.isEmpty() && line.startsWith(":")) {
                if (!handleCommand(line.trim(), reader)) break;
                continue;
            }

            pending.append(line).append("\n");

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

    private boolean handleCommand(String cmd, LineReader reader) {
        if (cmd.equals(":quit") || cmd.equals(":q")) {
            return false;
        }
        if (cmd.equals(":help")) {
            out.println("""
                Commands:
                  :quit, :q        — exit
                  :reset           — drop all session bindings
                  :load <file>     — load and run a source file
                  :env             — show user-defined bindings
                  :help            — this message""");
        } else if (cmd.equals(":reset")) {
            this.session = new BytecodeSession("irij.Repl");
            out.println("Session reset.");
        } else if (cmd.startsWith(":load ")) {
            loadFile(cmd.substring(6).trim());
        } else if (cmd.equals(":env")) {
            showEnv();
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

    private void showEnv() {
        var ns = session.namespace();
        if (ns.isEmpty()) {
            out.println("  (no bindings)");
            return;
        }
        var entries = ns.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (var entry : entries) {
            Object value = entry.getValue();
            String typeName = Values.typeName(value);
            String preview = Values.toIrijString(value);
            if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
            out.printf("  %-20s : %-12s = %s%n", entry.getKey(), typeName, preview);
        }
    }

    // ── Incomplete-input detection ────────────────────────────────────────

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
            session.eval(input, "repl", null);
        } catch (IrijCompiler.CompileException e) {
            out.println("Compile error: " + e.getMessage());
        } catch (IrijRuntimeError e) {
            out.println("Runtime error: " + e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            out.println("Error: " + cause.getMessage());
        }
        out.flush();
    }
}
