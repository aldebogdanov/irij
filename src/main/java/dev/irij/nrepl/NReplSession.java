package dev.irij.nrepl;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values;
import dev.irij.parser.IrijParseDriver;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * An nREPL session: wraps an {@link Interpreter} instance with
 * per-evaluation output capture via {@link IndirectOutputStream}.
 *
 * <p>Each session maintains its own environment — bindings persist
 * across evaluations within the session.
 */
public final class NReplSession {

    private final String id;
    private final Interpreter interpreter;
    private final IndirectOutputStream indirectOut;
    private final BackgroundOutputStream backgroundOut;
    private volatile boolean closed;

    public NReplSession() {
        this.id = UUID.randomUUID().toString();
        // Background output stream captures println from spawned threads
        // between evaluations (when the per-eval capture is not active).
        this.backgroundOut = new BackgroundOutputStream();
        this.indirectOut = new IndirectOutputStream(backgroundOut);
        var printStream = new PrintStream(indirectOut, true);
        this.interpreter = new Interpreter(printStream);
        this.closed = false;
    }

    public String id() {
        return id;
    }

    public boolean isClosed() {
        return closed;
    }

    // ── Op dispatch ─────────────────────────────────────────────────────

    /**
     * Handle an nREPL operation.
     *
     * @param msg the decoded bencode message (must contain "op" key)
     * @return response map (mutable — caller may add "id" and "session")
     */
    public Map<String, Object> handleOp(Map<String, Object> msg) {
        String op = (String) msg.get("op");
        if (op == null) {
            return errorResponse("Missing 'op' in message");
        }
        return switch (op) {
            case "eval" -> evalOp(msg);
            case "background-out" -> backgroundOutOp();
            case "describe" -> describeOp();
            case "close" -> closeOp();
            default -> unknownOp(op);
        };
    }

    // ── eval ────────────────────────────────────────────────────────────

    private Map<String, Object> evalOp(Map<String, Object> msg) {
        String code = (String) msg.get("code");
        if (code == null) {
            return errorResponse("Missing 'code' in eval message");
        }

        // Drain any background output that accumulated since the last eval
        // (e.g. from spawned threads) and prepend it to this eval's output.
        String bgPrefix = backgroundOut.drain();

        // Capture stdout for this eval
        var capture = new ByteArrayOutputStream();
        indirectOut.setTarget(capture);
        try {
            // Parse
            var parseResult = IrijParseDriver.parse(code);
            if (parseResult.hasErrors()) {
                var sb = new StringBuilder();
                for (var err : parseResult.errors()) {
                    sb.append(err).append("\n");
                }
                return errorResponseWithOut(bgPrefix, capture,
                        "Parse error: " + sb.toString().strip());
            }

            // Build AST and interpret
            var ast = new AstBuilder().build(parseResult.tree());
            var value = interpreter.run(ast);

            // Build response
            var resp = new LinkedHashMap<String, Object>();
            String stdout = bgPrefix + capture.toString();
            if (!stdout.isEmpty()) {
                resp.put("out", stdout);
            }
            resp.put("value", Values.toIrijString(value));
            resp.put("status", List.of("done"));
            return resp;

        } catch (IrijRuntimeError e) {
            return errorResponseWithOut(bgPrefix, capture, "Runtime error: " + e.getMessage());
        } catch (Exception e) {
            return errorResponseWithOut(bgPrefix, capture, "Error: " + e.getMessage());
        } finally {
            // Restore target to the background buffer so spawned threads
            // continue writing there (not to System.out).
            indirectOut.setTarget(backgroundOut);
        }
    }

    // ── background-out ────────────────────────────────────────────────

    /**
     * Drain buffered output from spawned background threads.
     * The Emacs client polls this op on a timer.
     */
    private Map<String, Object> backgroundOutOp() {
        var resp = new LinkedHashMap<String, Object>();
        String output = backgroundOut.drain();
        if (!output.isEmpty()) {
            resp.put("out", output);
        }
        resp.put("status", List.of("done"));
        return resp;
    }

    // ── describe ────────────────────────────────────────────────────────

    private Map<String, Object> describeOp() {
        var resp = new LinkedHashMap<String, Object>();
        resp.put("ops", Map.of(
                "eval", Map.of(),
                "background-out", Map.of(),
                "describe", Map.of(),
                "clone", Map.of(),
                "close", Map.of()
        ));
        resp.put("versions", Map.of("irij", "0.1.1"));
        resp.put("status", List.of("done"));
        return resp;
    }

    // ── close ───────────────────────────────────────────────────────────

    private Map<String, Object> closeOp() {
        closed = true;
        var resp = new LinkedHashMap<String, Object>();
        resp.put("status", List.of("done", "session-closed"));
        return resp;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> unknownOp(String op) {
        return errorResponse("Unknown op: " + op);
    }

    private Map<String, Object> errorResponse(String message) {
        var resp = new LinkedHashMap<String, Object>();
        resp.put("err", message);
        resp.put("status", List.of("done", "error"));
        return resp;
    }

    private Map<String, Object> errorResponseWithOut(
            String bgPrefix, ByteArrayOutputStream capture, String message) {
        var resp = new LinkedHashMap<String, Object>();
        String stdout = bgPrefix + capture.toString();
        if (!stdout.isEmpty()) {
            resp.put("out", stdout);
        }
        resp.put("err", message);
        resp.put("status", List.of("done", "error"));
        return resp;
    }
}
