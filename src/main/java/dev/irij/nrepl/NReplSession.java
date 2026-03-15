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
    private volatile boolean closed;

    public NReplSession() {
        this.id = UUID.randomUUID().toString();
        this.indirectOut = new IndirectOutputStream(System.out);
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

        // Capture stdout
        var capture = new ByteArrayOutputStream();
        var oldTarget = indirectOut.setTarget(capture);
        try {
            // Parse
            var parseResult = IrijParseDriver.parse(code);
            if (parseResult.hasErrors()) {
                var sb = new StringBuilder();
                for (var err : parseResult.errors()) {
                    sb.append(err).append("\n");
                }
                return errorResponseWithOut(capture, "Parse error: " + sb.toString().strip());
            }

            // Build AST and interpret
            var ast = new AstBuilder().build(parseResult.tree());
            var value = interpreter.run(ast);

            // Build response
            var resp = new LinkedHashMap<String, Object>();
            String stdout = capture.toString();
            if (!stdout.isEmpty()) {
                resp.put("out", stdout);
            }
            if (value != Values.UNIT) {
                resp.put("value", Values.toIrijString(value));
            }
            resp.put("status", List.of("done"));
            return resp;

        } catch (IrijRuntimeError e) {
            return errorResponseWithOut(capture, "Runtime error: " + e.getMessage());
        } catch (Exception e) {
            return errorResponseWithOut(capture, "Error: " + e.getMessage());
        } finally {
            indirectOut.setTarget(oldTarget);
        }
    }

    // ── describe ────────────────────────────────────────────────────────

    private Map<String, Object> describeOp() {
        var resp = new LinkedHashMap<String, Object>();
        resp.put("ops", Map.of(
                "eval", Map.of(),
                "describe", Map.of(),
                "clone", Map.of(),
                "close", Map.of()
        ));
        resp.put("versions", Map.of("irij", "0.1.0-SNAPSHOT"));
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

    private Map<String, Object> errorResponseWithOut(ByteArrayOutputStream capture, String message) {
        var resp = new LinkedHashMap<String, Object>();
        String stdout = capture.toString();
        if (!stdout.isEmpty()) {
            resp.put("out", stdout);
        }
        resp.put("err", message);
        resp.put("status", List.of("done", "error"));
        return resp;
    }
}
