package dev.irij.compiler;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values;

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
 * Sandboxed-bytecode sessions for the Playground.
 *
 * <p>v0.6.13: Migrated off the interpreter. Each session owns its
 * own {@link BytecodeSession} (per-session namespace map +
 * classloader) and a stdout buffer. The session classloader's parent
 * is the Irij runtime classloader so user code reaches builtins via
 * {@link RuntimeSupport}, but classes loaded inside a session are
 * isolated from other sessions.
 *
 * <p>Isolation today is namespace-level + classloader-level. Hard
 * isolation (SecurityManager / native-access deny / resource limits)
 * is future work — for now the Playground deploys assume trusted
 * code or a separate JVM per untrusted tenant.
 *
 * <p>Holds a process-wide {@link ConcurrentHashMap}; auto-evicts
 * sessions idle for more than {@code irij.session.ttl.ms} (default
 * 30 minutes) on a daemon sweeper thread.
 */
public final class RuntimeSessions {

    private RuntimeSessions() {}

    // ── Session entry ───────────────────────────────────────────────

    public static final class Session {
        public final BytecodeSession bytecode;
        public final CappedBuffer stdoutBuf;
        public final PrintStream stdout;
        public volatile long lastAccessMs;
        public volatile Values.SseWriter sse;
        /** One eval at a time: a session's namespace and classloader are
         *  not built for concurrent evals (a double-click, two tabs). */
        final java.util.concurrent.locks.ReentrantLock evalLock =
                new java.util.concurrent.locks.ReentrantLock();

        Session(BytecodeSession bytecode, CappedBuffer stdoutBuf,
                PrintStream stdout) {
            this.bytecode = bytecode;
            this.stdoutBuf = stdoutBuf;
            this.stdout = stdout;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS =
            Long.getLong("irij.session.ttl.ms", 30L * 60_000L);
    private static final long SESSION_SWEEP_MS =
            Long.getLong("irij.session.sweep.ms", 60_000L);
    /** Live sessions at most; creating one more evicts the idlest. Each
     *  holds a classloader and namespace, so an unbounded map lets anyone
     *  who can create sessions exhaust the heap within one TTL. */
    private static final int MAX_SESSIONS =
            Integer.getInteger("irij.session.max", 500);
    /** Output kept per eval; past it the rest is dropped with a notice. A
     *  print loop would otherwise fill the heap before the timeout. */
    static final int MAX_OUTPUT_BYTES =
            Integer.getInteger("irij.session.output.max", 1 << 20);
    /** After a timeout, how long to wait for the interrupted eval to
     *  reach a cancellation point before reporting anyway. */
    private static final long CANCEL_GRACE_MS = 1_000;
    private static final AtomicLong EVAL_THREADS = new AtomicLong();

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

    public static Session lookup(String id) { return SESSIONS.get(id); }

    // ── raw-session-create ──────────────────────────────────────────

    public static Object rawSessionCreate() {
        ensureSweeper();
        evictIdlestBeyond(MAX_SESSIONS - 1);
        String id = UUID.randomUUID().toString();
        CappedBuffer baos = new CappedBuffer(MAX_OUTPUT_BYTES);
        Session[] holder = new Session[1];

        PrintStream sessionOut = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }
            @Override public void write(byte[] buf, int off, int len) {
                // Past the cap nothing is kept, and nothing is streamed.
                if (!baos.append(buf, off, len)) return;
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
            @Override public void flush() {}
        }, true);

        BytecodeSession bs = new BytecodeSession("irij.Sandbox$" + id.substring(0, 8));
        Session s = new Session(bs, baos, sessionOut);
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
        boolean locked = false;
        try {
            locked = s.evalLock.tryLock(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) return failure("", "Session is busy with an earlier evaluation");
        try {
            s.stdoutBuf.reset();
            return runEval(s.bytecode, s.stdoutBuf, s.stdout, code, timeoutMs);
        } finally {
            s.evalLock.unlock();
        }
    }

    /** Drop the least recently used sessions until at most {@code keep} remain. */
    private static void evictIdlestBeyond(int keep) {
        while (SESSIONS.size() > keep) {
            String idlest = null;
            long oldest = Long.MAX_VALUE;
            for (var e : SESSIONS.entrySet()) {
                if (e.getValue().lastAccessMs < oldest) {
                    oldest = e.getValue().lastAccessMs;
                    idlest = e.getKey();
                }
            }
            if (idlest == null) return;
            SESSIONS.remove(idlest);
        }
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
            // Playground race: client opens SSE before /api/session/create
            // returned, or after the session was destroyed/evicted.
            // Treat as soft no-op so the SSE handler closes the stream
            // cleanly instead of bubbling a 500. The client will
            // reconnect once it has a valid id.
            return Values.UNIT;
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
        CappedBuffer baos = new CappedBuffer(MAX_OUTPUT_BYTES);
        PrintStream evalOut = new PrintStream(baos, true);
        BytecodeSession bs = new BytecodeSession("irij.NReplSandbox");
        return runEval(bs, baos, evalOut, code, timeoutMs);
    }

    // ── Shared eval driver ──────────────────────────────────────────

    private static Object runEval(BytecodeSession bs,
                                   CappedBuffer baos,
                                   PrintStream captureOut,
                                   String code,
                                   long timeoutMs) {
        // Mark with a fresh sentinel; if the eval doesn't overwrite it,
        // we know the program had no trailing expression.
        Object sentinel = new Object();
        bs.namespace().put(BytecodeSession.LAST_VALUE_KEY, sentinel);
        CompletableFuture<Object> future = new CompletableFuture<>();
        // Each eval gets its own platform thread. A shared pool (the
        // common ForkJoinPool, as before) is held for good by one
        // runaway eval — on a 2-CPU host its single worker — so every
        // later eval just times out. A virtual thread would be no
        // better: one that spins never yields its carrier.
        Thread worker = Thread.ofPlatform().daemon()
                .name("irij-eval-" + EVAL_THREADS.incrementAndGet())
                .unstarted(() -> {
                    try {
                        bs.eval(code, "sandbox", captureOut);
                        future.complete(Values.UNIT);
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        worker.start();
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            Object last = bs.namespace().get(BytecodeSession.LAST_VALUE_KEY);
            Object surfaced = (last == sentinel) ? Values.UNIT : last;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("value", Values.toIrijString(surfaced));
            result.put("stdout", baos.toString());
            result.put("error", Values.UNIT);
            result.put("ok", Boolean.TRUE);
            return new Values.IrijMap(result);
        } catch (TimeoutException e) {
            // Interrupt it: compiled loops poll for this
            // (RtConcurrency.checkCancelled), as do sleep and blocking IO.
            worker.interrupt();
            try { worker.join(CANCEL_GRACE_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return failure(baos.toString(), "Evaluation timed out (" + timeoutMs + "ms)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            if (cause instanceof StackOverflowError) msg = "Stack overflow (recursion too deep)";
            return failure(baos.toString(), msg != null ? msg : "Unknown error");
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return failure(baos.toString(), "Evaluation interrupted");
        }
    }

    private static Values.IrijMap failure(String stdout, String error) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("value", Values.UNIT);
        result.put("stdout", stdout);
        result.put("error", error);
        result.put("ok", Boolean.FALSE);
        return new Values.IrijMap(result);
    }

    /** An eval's output buffer: keeps the first {@code max} bytes, then a
     *  one-line notice, then nothing. */
    public static final class CappedBuffer extends java.io.OutputStream {
        private final int max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean truncated;

        CappedBuffer(int max) { this.max = max; }

        /** Append; false once the cap has been reached (bytes dropped). */
        public synchronized boolean append(byte[] b, int off, int len) {
            if (truncated) return false;
            int room = max - buf.size();
            if (len <= room) {
                buf.write(b, off, len);
                return true;
            }
            buf.write(b, off, Math.max(0, room));
            byte[] note = ("\n[output truncated at " + max + " bytes]\n")
                    .getBytes(StandardCharsets.UTF_8);
            buf.write(note, 0, note.length);
            truncated = true;
            return false;
        }

        @Override public void write(int b) { append(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) { append(b, off, len); }

        public synchronized void reset() {
            buf.reset();
            truncated = false;
        }

        @Override public synchronized String toString() {
            return buf.toString(StandardCharsets.UTF_8);
        }
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
