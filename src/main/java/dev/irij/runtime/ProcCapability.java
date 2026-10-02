package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.IrijVector;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Capability provider for the {@code Proc} effect: child processes.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap proc-os :: Proc = "dev.irij.runtime.ProcCapability"
 * </pre>
 *
 * <p>Two shapes. {@link #run} starts a command, feeds it stdin, waits
 * (with a timeout) and returns both streams. {@link #start} returns a
 * handle for a process that is talked to while it runs: stdout is read a
 * line at a time ({@link #line}), stdin is written ({@link #send},
 * {@link #close}), and {@link #await} collects the exit code and stderr.
 * Both streams are always drained on their own virtual threads, since a
 * pipe that fills blocks the child.
 *
 * <p>Options, a map: {@code cmd} (a Vec of Str, required), {@code dir},
 * {@code env} (a map merged over the inherited environment),
 * {@code stdin} (a Str, {@code run} only) and {@code timeout-ms}
 * ({@code run} only; 0 waits forever). A timed-out command is killed with
 * its descendants and reported with {@code timed-out? true}.
 */
public final class ProcCapability {

    private ProcCapability() {}

    /** Stderr kept per process; past this the oldest output is dropped. */
    static final int STDERR_LIMIT = 1 << 20;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<Long, Running> RUNNING = new ConcurrentHashMap<>();

    /** A started process: its stdout as lines, its stderr as a tail. */
    private record Running(Process process, BlockingQueue<Object> lines, Tail stderr) {}

    /** End of stdout, queued after the last line. */
    private static final Object EOF = new Object();

    // ── run ─────────────────────────────────────────────────────────

    public static Object run(Object optsArg) {
        IrijMap opts = asMap(optsArg, "proc-run");
        Process p = launch(opts, "proc-run");
        CompletableFuture<String> out = drain(p.getInputStream());
        Tail err = new Tail();
        CompletableFuture<Void> errDone = CompletableFuture.runAsync(() -> err.fill(p.getErrorStream()), Thread::startVirtualThread);
        String stdin = str(opts, "stdin");
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The child closed stdin early; what it wrote is still read below.
        }
        long timeout = num(opts, "timeout-ms", 0);
        boolean timedOut = !waitFor(p, timeout);
        if (timedOut) kill(p);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("exit", timedOut ? -1L : (long) p.exitValue());
        r.put("stdout", out.join());
        errDone.join();
        r.put("stderr", err.text());
        r.put("timed-out?", timedOut);
        return new IrijMap(r);
    }

    // ── start / line / send / close / await / kill ──────────────────

    public static Object start(Object optsArg) {
        IrijMap opts = asMap(optsArg, "proc-start");
        Process p = launch(opts, "proc-start");
        BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        Tail err = new Tail();
        Thread.startVirtualThread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // The stream closed under us (killed); EOF follows.
            } finally {
                lines.add(EOF);
            }
        });
        Thread.startVirtualThread(() -> err.fill(p.getErrorStream()));
        long id = NEXT_ID.incrementAndGet();
        RUNNING.put(id, new Running(p, lines, err));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("pid", p.pid());
        return new IrijMap(h);
    }

    /**
     * The next line of stdout, as {@code {kind= "line" text= …}}, or
     * {@code {kind= "eof"}} once stdout is closed, or {@code {kind= "timeout"}}
     * when nothing arrived within {@code timeout-ms} (-1 blocks, 0 polls).
     */
    public static Object line(Object handle, Object timeoutArg) {
        Running r = running(handle, "proc-line");
        long ms = asLong(timeoutArg, "proc-line");
        Object next;
        try {
            if (ms < 0) next = r.lines().take();
            else next = r.lines().poll(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IrijRuntimeError("proc-line: interrupted");
        }
        if (next == null) return kind("timeout");
        if (next == EOF) {
            r.lines().add(EOF);             // stays at end for the next reader
            return kind("eof");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "line");
        m.put("text", next);
        return new IrijMap(m);
    }

    public static Object send(Object handle, Object textArg) {
        Running r = running(handle, "proc-send");
        String text = asStr(textArg, "proc-send");
        try {
            OutputStream in = r.process().getOutputStream();
            in.write(text.getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException e) {
            throw new IrijRuntimeError("proc-send: " + e.getMessage());
        }
        return Values.UNIT;
    }

    public static Object close(Object handle) {
        Running r = running(handle, "proc-close");
        try {
            r.process().getOutputStream().close();
        } catch (IOException ignored) {
            // Already closed by the child.
        }
        return Values.UNIT;
    }

    /**
     * Wait for exit, up to {@code timeout-ms} (-1 or 0 waits forever).
     * Returns {@code {exit= stderr= timed-out?=}}; a process that is still
     * running at the deadline is left running and keeps its handle.
     */
    public static Object await(Object handle, Object timeoutArg) {
        Running r = running(handle, "proc-wait");
        long ms = asLong(timeoutArg, "proc-wait");
        boolean done = waitFor(r.process(), ms < 0 ? 0 : ms);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exit", done ? (long) r.process().exitValue() : -1L);
        m.put("stderr", r.stderr().text());
        m.put("timed-out?", !done);
        if (done) RUNNING.remove(idOf(handle, "proc-wait"));
        return new IrijMap(m);
    }

    public static Object kill(Object handle) {
        Running r = RUNNING.remove(idOf(handle, "proc-kill"));
        if (r != null) kill(r.process());
        return Values.UNIT;
    }

    // ── plumbing ────────────────────────────────────────────────────

    private static Process launch(IrijMap opts, String op) {
        Object cmdArg = opts.entries().get("cmd");
        if (!(cmdArg instanceof IrijVector cv) || cv.elements().isEmpty()) {
            throw new IrijRuntimeError(op + ": cmd must be a non-empty Vec of Str, as #[\"git\" \"status\"]");
        }
        List<String> cmd = new ArrayList<>();
        for (Object o : cv.elements()) cmd.add(asStr(o, op + " cmd"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        String dir = str(opts, "dir");
        if (dir != null) pb.directory(new File(dir));
        Object env = opts.entries().get("env");
        if (env instanceof IrijMap em) {
            for (var e : em.entries().entrySet()) pb.environment().put(e.getKey(), String.valueOf(e.getValue()));
        }
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IrijRuntimeError(op + ": cannot start " + cmd.get(0) + ": " + e.getMessage());
        }
    }

    private static boolean waitFor(Process p, long ms) {
        try {
            if (ms <= 0) {
                p.waitFor();
                return true;
            }
            return p.waitFor(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(p);
            throw new IrijRuntimeError("proc: interrupted while waiting");
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, Thread::startVirtualThread);
    }

    /** The last {@link #STDERR_LIMIT} bytes of a stream. */
    private static final class Tail {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean truncated;

        void fill(InputStream in) {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    synchronized (this) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > STDERR_LIMIT) {
                            byte[] all = buf.toByteArray();
                            buf.reset();
                            buf.write(all, all.length - STDERR_LIMIT, STDERR_LIMIT);
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Closed by a kill; keep what arrived.
            }
        }

        synchronized String text() {
            String s = buf.toString(StandardCharsets.UTF_8);
            return truncated ? "…" + s : s;
        }
    }

    private static Running running(Object handle, String op) {
        Running r = RUNNING.get(idOf(handle, op));
        if (r == null) throw new IrijRuntimeError(op + ": no running process for " + handle);
        return r;
    }

    private static long idOf(Object handle, String op) {
        if (handle instanceof IrijMap m && m.entries().get("id") instanceof Long id) return id;
        throw new IrijRuntimeError(op + ": expected a handle from proc-start, got " + handle);
    }

    private static IrijMap kind(String k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", k);
        return new IrijMap(m);
    }

    private static IrijMap asMap(Object v, String op) {
        if (v instanceof IrijMap m) return m;
        throw new IrijRuntimeError(op + ": expected an options map, got " + v);
    }

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError(op + ": expected a Str, got " + v);
    }

    private static long asLong(Object v, String op) {
        if (v instanceof Long l) return l;
        throw new IrijRuntimeError(op + ": expected an Int, got " + v);
    }

    private static String str(IrijMap m, String key) {
        Object v = m.entries().get(key);
        return v instanceof String s ? s : null;
    }

    private static long num(IrijMap m, String key, long dflt) {
        Object v = m.entries().get(key);
        return v instanceof Long l ? l : dflt;
    }
}
