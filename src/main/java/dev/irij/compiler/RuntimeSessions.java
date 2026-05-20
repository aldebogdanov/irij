package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.Values;
import dev.irij.parser.IrijParseDriver;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sandboxed-interpreter sessions for the Playground.
 *
 * <p>Pulls the session manager out of {@link Interpreter} so both
 * interpreter-mode and bytecode-mode reach the same in-process store.
 * Each session owns a private {@link Interpreter} with its own
 * stdout buffer and an optional SSE subscriber that streams output
 * line-by-line.
 *
 * <p>Holds a process-wide {@link ConcurrentHashMap}; auto-evicts
 * sessions idle for more than {@code irij.session.ttl.ms} (default
 * 30 minutes) on a daemon sweeper thread. The sweeper starts lazily
 * on first session creation so non-Playground programs pay nothing.
 *
 * <p>The {@link Interpreter} type is referenced by name only —
 * bytecode-emitted code resolves to the static helpers here; the
 * interpreter class is loaded only when the Playground feature is
 * actually used.
 */
public final class RuntimeSessions {

    private RuntimeSessions() {}

    // ── Session entry ───────────────────────────────────────────────

    public static final class Session {
        public final Interpreter interp;
        public final ByteArrayOutputStream stdoutBuf;
        public volatile long lastAccessMs;
        public volatile Values.SseWriter sse;

        Session(Interpreter interp, ByteArrayOutputStream stdoutBuf) {
            this.interp = interp;
            this.stdoutBuf = stdoutBuf;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS =
            Long.getLong("irij.session.ttl.ms", 30L * 60_000L);
    private static final long SESSION_SWEEP_MS =
            Long.getLong("irij.session.sweep.ms", 60_000L);

    private static volatile boolean sweeperStarted = false;

    private static synchronized void ensureSweeper() {
        if (sweeperStarted) return;
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "irij-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            SESSIONS.entrySet().removeIf(e ->
                    now - e.getValue().lastAccessMs > SESSION_TTL_MS);
        }, SESSION_SWEEP_MS, SESSION_SWEEP_MS, TimeUnit.MILLISECONDS);
        sweeperStarted = true;
    }

    /** Lookup for tests / inspection. */
    public static Session lookup(String id) { return SESSIONS.get(id); }

    // ── raw-session-create ──────────────────────────────────────────

    public static Object rawSessionCreate() {
        ensureSweeper();
        String id = UUID.randomUUID().toString();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Session[] holder = new Session[1];

        PrintStream sessionOut = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {
                baos.write(b);
            }
            @Override public void write(byte[] buf, int off, int len) {
                baos.write(buf, off, len);
                // Forward to SSE if subscribed.
                Session s = holder[0];
                if (s == null) return;
                Values.SseWriter sse = s.sse;
                if (sse != null && !sse.isClosed()) {
                    String text = new String(buf, off, len, StandardCharsets.UTF_8);
                    try {
                        sse.send("message",
                                "{\"type\":\"stdout\",\"text\":" + jsonEscape(text) + "}");
                    } catch (Exception ignored) {
                        s.sse = null;
                    }
                }
            }
            @Override public void flush() {
                // baos has no buffering; nothing to flush.
            }
        }, true);

        Interpreter interp = new Interpreter(sessionOut, true);
        interp.setSpecLintEnabled(false);
        Session s = new Session(interp, baos);
        holder[0] = s;
        SESSIONS.put(id, s);
        return id;
    }

    // ── raw-session-eval ────────────────────────────────────────────

    public static Object rawSessionEval(Object idArg, Object codeArg, Object timeoutArg) {
        String id = asStr(idArg, "raw-session-eval");
        String code = asStr(codeArg, "raw-session-eval");
        long timeoutMs = asLong(timeoutArg, "raw-session-eval");
        Session s = SESSIONS.get(id);
        if (s == null) {
            throw new IrijRuntimeError("raw-session-eval: no session with id " + id);
        }
        s.lastAccessMs = System.currentTimeMillis();
        s.stdoutBuf.reset();
        return runEval(s.interp, s.stdoutBuf, code, timeoutMs);
    }

    // ── raw-session-destroy ─────────────────────────────────────────

    public static Object rawSessionDestroy(Object idArg) {
        SESSIONS.remove(asStr(idArg, "raw-session-destroy"));
        return Values.UNIT;
    }

    // ── raw-session-subscribe ───────────────────────────────────────

    public static Object rawSessionSubscribe(Object idArg, Object sseArg) {
        String id = asStr(idArg, "raw-session-subscribe");
        if (!(sseArg instanceof Values.SseWriter sse)) {
            throw new IrijRuntimeError(
                    "raw-session-subscribe: second arg must be SseWriter");
        }
        Session s = SESSIONS.get(id);
        if (s == null) {
            throw new IrijRuntimeError(
                    "raw-session-subscribe: no session with id " + id);
        }
        s.sse = sse;
        return Values.UNIT;
    }

    // ── raw-session-unsubscribe ─────────────────────────────────────

    public static Object rawSessionUnsubscribe(Object idArg) {
        Session s = SESSIONS.get(asStr(idArg, "raw-session-unsubscribe"));
        if (s != null) s.sse = null;
        return Values.UNIT;
    }

    // ── raw-session-cleanup ─────────────────────────────────────────

    public static Object rawSessionCleanup(Object maxIdleArg) {
        long maxIdleMs = asLong(maxIdleArg, "raw-session-cleanup");
        long now = System.currentTimeMillis();
        AtomicLong removed = new AtomicLong(0);
        SESSIONS.entrySet().removeIf(e -> {
            if (now - e.getValue().lastAccessMs > maxIdleMs) {
                removed.incrementAndGet();
                return true;
            }
            return false;
        });
        return removed.get();
    }

    // ── raw-nrepl-eval-sandboxed — one-shot sandboxed eval ──────────

    public static Object rawNreplEvalSandboxed(Object codeArg, Object timeoutArg) {
        String code = asStr(codeArg, "raw-nrepl-eval-sandboxed");
        long timeoutMs = asLong(timeoutArg, "raw-nrepl-eval-sandboxed");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream evalOut = new PrintStream(baos);
        Interpreter interp = new Interpreter(evalOut, true);
        interp.setSpecLintEnabled(false);
        return runEval(interp, baos, code, timeoutMs);
    }

    // ── Shared eval driver ──────────────────────────────────────────

    private static Object runEval(Interpreter interp,
                                   ByteArrayOutputStream baos,
                                   String code,
                                   long timeoutMs) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            var parsed = IrijParseDriver.parse(code + "\n");
            if (parsed.hasErrors()) {
                throw new IrijRuntimeError("Parse error: " + parsed.errors());
            }
            return interp.run(new AstBuilder().build(parsed.tree()));
        });
        try {
            Object value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            result.put("value", Values.toIrijString(value));
            result.put("stdout", baos.toString());
            result.put("error", Values.UNIT);
            result.put("ok", Boolean.TRUE);
        } catch (TimeoutException e) {
            future.cancel(true);
            result.put("value", Values.UNIT);
            result.put("stdout", baos.toString());
            result.put("error", "Evaluation timed out (" + timeoutMs + "ms)");
            result.put("ok", Boolean.FALSE);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            result.put("value", Values.UNIT);
            result.put("stdout", baos.toString());
            result.put("error", msg != null ? msg : "Unknown error");
            result.put("ok", Boolean.FALSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.put("value", Values.UNIT);
            result.put("stdout", baos.toString());
            result.put("error", "Evaluation interrupted");
            result.put("ok", Boolean.FALSE);
        }
        return new Values.IrijMap(result);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError(op + " expects a String, got " + RuntimeSupport.typeTag(v));
    }

    private static long asLong(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        throw new IrijRuntimeError(op + " expects an Int, got " + RuntimeSupport.typeTag(v));
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
