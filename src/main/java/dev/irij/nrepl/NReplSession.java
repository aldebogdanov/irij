package dev.irij.nrepl;

import dev.irij.compiler.BytecodeSession;
import dev.irij.compiler.IrijCompiler;
import dev.irij.IrijRuntimeError;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * An nREPL session: wraps a {@link BytecodeSession} with per-eval
 * output capture via {@link IndirectOutputStream}.
 *
 * <p>v0.6.13: bytecode-only. Each session has its own namespace map +
 * classloader; top-level binds persist across evaluations within the
 * session via {@link BytecodeSession}.
 */
public final class NReplSession {

    private final String id;
    private final BytecodeSession session;
    private final IndirectOutputStream indirectOut;
    private final BackgroundOutputStream backgroundOut;
    private volatile boolean closed;

    public NReplSession() {
        this(null);
    }

    public NReplSession(java.nio.file.Path projectRoot) {
        this.id = UUID.randomUUID().toString();
        this.backgroundOut = new BackgroundOutputStream();
        this.indirectOut = new IndirectOutputStream(backgroundOut);
        this.session = new BytecodeSession("irij.NReplEval");
        this.closed = false;
        // projectRoot is currently informational only; module
        // resolution happens via the compile-time inliner reading
        // seeds from `~/.irij/seeds/`. Bytecode mode doesn't have
        // a runtime "set source path" affordance.
        // TODO: wire seed-root resolution into BytecodeSession.eval.
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
            // v0.6.13: only one execution model — bytecode. `eval` and
            // `eval-bytecode` are both routed through BytecodeSession.
            // `eval-interp` was removed with the interpreter.
            case "eval", "eval-bytecode" -> evalBytecodeOp(msg);
            case "background-out" -> backgroundOutOp();
            case "describe" -> describeOp();
            case "close" -> closeOp();
            default -> unknownOp(op);
        };
    }

    // ── eval-bytecode ──────────────────────────────────────────────────
    //
    // Compile the snippet via BytecodeSession (state-machine mode +
    // namespace mode on) and run it. Top-level binds persist across
    // evals through the session's shared namespace map.

    private Map<String, Object> evalBytecodeOp(Map<String, Object> msg) {
        String code = (String) msg.get("code");
        if (code == null) {
            return errorResponse("Missing 'code' in eval-bytecode message");
        }

        String bgPrefix = backgroundOut.drain();
        var capture = new ByteArrayOutputStream();
        indirectOut.setTarget(capture);
        // Use the indirectOut wrapper as the session PrintStream, NOT a
        // direct PrintStream over `capture`. That way spawned threads
        // (which inherit SESSION_OUT via ParentSnapshot) keep writing
        // through the indirection: while the eval runs, writes hit
        // `capture`; after the eval returns and the finally swaps the
        // indirection back to `backgroundOut`, spawned-thread writes
        // accumulate there for the next `background-out` op.
        var captureStream = new PrintStream(indirectOut, true);
        // Pre-eval sentinel so we can distinguish "user ended with an
        // expression that legitimately evaluated to ()" from "user ran
        // only fns/binds (no trailing expression)".
        Object sentinel = new Object();
        session.namespace().put(dev.irij.compiler.BytecodeSession.LAST_VALUE_KEY, sentinel);
        try {
            session.eval(code, "nrepl", captureStream);
            var resp = new LinkedHashMap<String, Object>();
            String stdout = bgPrefix + capture.toString();
            if (!stdout.isEmpty()) resp.put("out", stdout);
            Object last = session.namespace().get(dev.irij.compiler.BytecodeSession.LAST_VALUE_KEY);
            Object surfaced = (last == sentinel) ? dev.irij.runtime.Values.UNIT : last;
            resp.put("value", dev.irij.runtime.Values.toIrijString(surfaced));
            resp.put("status", List.of("done"));
            return resp;
        } catch (IrijCompiler.CompileException e) {
            return errorResponseWithOut(bgPrefix, capture, "Compile error: " + e.getMessage());
        } catch (IrijRuntimeError e) {
            return errorResponseWithOut(bgPrefix, capture, "Runtime error: " + e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return errorResponseWithOut(bgPrefix, capture, "Runtime error: " + cause.getMessage());
        } finally {
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
                "eval", Map.of(),               // bytecode + namespace
                "eval-bytecode", Map.of(),      // explicit alias
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
