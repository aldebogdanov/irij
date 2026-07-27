package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.IrijVector;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Capability provider for the {@code Term} effect — raw-mode terminal
 * I/O for full-screen apps.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap term-jline :: Term = "dev.irij.runtime.TermCapability"
 * </pre>
 *
 * <p>Where {@code Console} is line-oriented (echoing, cooked,
 * {@code read-line} blocks until Enter), {@code Term} is
 * cell-oriented: raw mode, decoded key events, terminal size, resize
 * notifications, and buffered writes flushed one frame at a time.
 *
 * <p>JLine supplies the terminal handle, raw-mode attributes, the
 * non-blocking reader and {@code wcwidth}. Everything above that —
 * escape-sequence decoding ({@link TermDecoder}), alt-screen, cursor
 * and mouse toggles — is done here in plain ANSI, so behaviour is
 * identical under any handler and reproducible in tests.
 *
 * <h2>State</h2>
 * One process, one terminal: the state below is static, because the
 * controlling terminal is. {@code enter} is idempotent-hostile — call
 * it once, pair it with {@code leave}.
 *
 * <h2>Restoring on crash</h2>
 * A terminal left in raw mode with the alt screen up makes the user's
 * shell unusable, and an Irij error unwinding past {@code term-leave}
 * would do exactly that. Two guards: the shutdown hook below (covers
 * uncaught errors and Ctrl-C), and {@code run-app}'s {@code try} on
 * the Irij side (covers the recoverable case, where the app wants to
 * print a stack trace to a sane terminal).
 */
public final class TermCapability {

    private TermCapability() {}

    private static Terminal terminal;
    private static Attributes savedAttrs;
    private static Thread restoreHook;
    private static boolean altScreen;
    private static boolean mouseTracking;
    private static boolean cursorHidden;

    /**
     * Events that don't come from stdin: resize notifications and
     * {@code term-post} injections from other fibers. Polled by
     * {@link #read} alongside the input stream.
     */
    private static final BlockingQueue<Object> INBOX = new LinkedBlockingQueue<>();

    /** Frame buffer. One {@code write()} per {@code flush} keeps a
     *  redraw from tearing across many small syscalls. */
    private static final StringBuilder OUT = new StringBuilder();

    /**
     * Longest a single {@link #read} will sit in the input stream
     * before coming up for air to check {@link #INBOX}. Bounds the
     * latency of a {@code term-post} from a background fiber; also
     * what makes an infinite ({@code timeout < 0}) read interruptible.
     */
    private static final long SLICE_MS = 50;

    // ── ANSI control strings ────────────────────────────────────────

    private static final String ALT_SCREEN_ON  = "\033[?1049h";
    private static final String ALT_SCREEN_OFF = "\033[?1049l";
    private static final String CURSOR_HIDE    = "\033[?25l";
    private static final String CURSOR_SHOW    = "\033[?25h";
    /** 1000 = button events, 1002 = drag tracking, 1006 = SGR encoding. */
    private static final String MOUSE_ON       = "\033[?1000;1002;1006h";
    private static final String MOUSE_OFF      = "\033[?1000;1002;1006l";

    // ── Lifecycle ───────────────────────────────────────────────────

    /**
     * {@code enter opts} — raw mode on. Options (all optional, shown
     * with their defaults): {@code {alt-screen= true cursor= false
     * mouse= false}}.
     */
    public static Object enter(Object optsArg) {
        if (terminal != null) throw new IrijRuntimeError("term-jline.enter: already entered");
        Map<String, Object> opts = optsArg instanceof IrijMap m ? m.entries() : Map.of();
        boolean wantAlt    = flag(opts, "alt-screen", true);
        boolean wantCursor = flag(opts, "cursor", false);
        boolean wantMouse  = flag(opts, "mouse", false);

        try {
            // dumb(false): refuse the silent downgrade to a dumb
            // terminal. Left on, JLine logs a WARNING and hands back a
            // handle whose raw mode and size are fiction — the app
            // would draw into nothing. Failing here, loudly, is the
            // honest outcome.
            terminal = TerminalBuilder.builder().system(true).dumb(false).build();
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException is what dumb(false) raises when
            // there's no system terminal to attach to.
            throw new IrijRuntimeError(
                    "term-jline.enter: no terminal available (" + e.getMessage() + "). "
                    + "Full-screen apps need a tty — if stdin/stdout is redirected, or TERM is "
                    + "unset/dumb, use the Console effect for pipe-friendly output instead.");
        }

        savedAttrs = terminal.getAttributes();
        terminal.enterRawMode();

        restoreHook = new Thread(TermCapability::restore, "irij-term-restore");
        Runtime.getRuntime().addShutdownHook(restoreHook);

        terminal.handle(Terminal.Signal.WINCH, sig -> {
            var ev = new LinkedHashMap<String, Object>();
            ev.put("kind", "resize");
            ev.putAll(sizeMap());
            INBOX.offer(toIrij(ev));
        });

        if (wantAlt) { raw(ALT_SCREEN_ON); altScreen = true; }
        if (!wantCursor) { raw(CURSOR_HIDE); cursorHidden = true; }
        if (wantMouse) { raw(MOUSE_ON); mouseTracking = true; }
        terminal.flush();
        return Values.UNIT;
    }

    /** {@code leave} — cooked mode, main screen, cursor back. */
    public static Object leave() {
        restore();
        if (restoreHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(restoreHook); }
            catch (IllegalStateException ignored) { /* already shutting down */ }
            restoreHook = null;
        }
        return Values.UNIT;
    }

    /** Idempotent teardown. Safe from a shutdown hook (never throws). */
    private static synchronized void restore() {
        if (terminal == null) return;
        try {
            if (mouseTracking) { raw(MOUSE_OFF); mouseTracking = false; }
            if (cursorHidden)  { raw(CURSOR_SHOW); cursorHidden = false; }
            if (altScreen)     { raw(ALT_SCREEN_OFF); altScreen = false; }
            terminal.flush();
            if (savedAttrs != null) terminal.setAttributes(savedAttrs);
        } catch (RuntimeException ignored) {
            // Best effort — a failure here must not mask the error that
            // brought us down.
        } finally {
            closeQuietly();
            savedAttrs = null;
            OUT.setLength(0);
            INBOX.clear();
        }
    }

    private static void closeQuietly() {
        try { if (terminal != null) terminal.close(); }
        catch (IOException ignored) { }
        terminal = null;
    }

    // ── Query ───────────────────────────────────────────────────────

    /** {@code size} → {@code {cols= rows=}}. */
    public static Object size() {
        active("size");
        return new IrijMap(sizeMap());
    }

    private static Map<String, Object> sizeMap() {
        var s = terminal.getSize();
        var m = new LinkedHashMap<String, Object>();
        m.put("cols", (long) fallbackDim(s.getColumns(), "COLUMNS", 80));
        m.put("rows", (long) fallbackDim(s.getRows(), "LINES", 24));
        return m;
    }

    /**
     * A pty with no window size set (CI harnesses, {@code script},
     * some ssh setups) reports 0×0. Handing that to a layout engine
     * divides by zero or draws nothing at all, so fall back the way
     * every curses app does: the environment first, then the classic
     * 80×24.
     */
    private static int fallbackDim(int reported, String envVar, int dflt) {
        if (reported > 0) return reported;
        String env = System.getenv(envVar);
        if (env != null) {
            try {
                int v = Integer.parseInt(env.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    /**
     * {@code strWidth s} — display width in cells, not chars. A CJK
     * ideograph occupies two columns and a combining mark zero; laying
     * out by {@code count} would drift on both.
     */
    public static Object strWidth(Object sArg) {
        String s = asStr(sArg, "strWidth");
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int cw = WCWidth.wcwidth(cp);
            if (cw > 0) w += cw;            // -1 = non-printable; count as 0
        }
        return (long) w;
    }

    // ── Input ───────────────────────────────────────────────────────

    /**
     * {@code read timeout-ms} → one event. A negative timeout blocks
     * until something arrives; zero polls.
     *
     * <p>Inbox first, then the input stream: a resize or a posted event
     * that's already waiting shouldn't sit behind an idle keyboard.
     */
    public static Object read(Object timeoutArg) {
        active("read");
        long timeout = asInt(timeoutArg, "read");
        long deadline = timeout < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeout;

        for (;;) {
            Object queued = INBOX.poll();
            if (queued != null) return queued;

            long remaining = deadline - System.currentTimeMillis();
            if (timeout == 0) remaining = 0;
            if (remaining <= 0) return toIrij(Map.of("kind", "none"));

            var ev = TermDecoder.next(SRC, Math.min(remaining, SLICE_MS));
            if (!"none".equals(ev.get("kind"))) return toIrij(ev);
            // "none" here is just the slice expiring — loop to re-check
            // the inbox and the real deadline.
        }
    }

    /** {@code postEvent ev} — queue an event from any thread. (Not
     *  {@code post}: that's a reserved word in Irij — contract
     *  postconditions — so it can't appear in a dot-access member
     *  position.) */
    public static Object postEvent(Object ev) {
        INBOX.offer(ev);
        return Values.UNIT;
    }

    private static final TermDecoder.Source SRC = timeoutMs -> {
        var t = terminal;
        if (t == null) return TermDecoder.EOF;
        try {
            // JLine treats a non-positive timeout as "block forever";
            // slices are already bounded above, so clamp to 1 ms.
            return t.reader().read(Math.max(1, timeoutMs));
        } catch (IOException e) {
            throw new IrijRuntimeError("term-jline.read: " + e.getMessage());
        }
    };

    // ── Output ──────────────────────────────────────────────────────

    /** {@code write s} — append to the frame buffer. */
    public static Object write(Object sArg) {
        active("write");
        OUT.append(asStr(sArg, "write"));
        return Values.UNIT;
    }

    /** {@code flush} — one write to the terminal, buffer cleared. */
    public static Object flush() {
        var t = active("flush");
        if (OUT.length() > 0) {
            t.writer().write(OUT.toString());
            OUT.setLength(0);
        }
        t.flush();
        return Values.UNIT;
    }

    private static void raw(String ansi) {
        terminal.writer().write(ansi);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Terminal active(String op) {
        var t = terminal;
        if (t == null) {
            throw new IrijRuntimeError(
                    "term-jline." + op + ": terminal not entered — call term-enter first");
        }
        return t;
    }

    private static boolean flag(Map<String, Object> opts, String key, boolean dflt) {
        Object v = opts.get(key);
        return v == null ? dflt : Values.isTruthy(v);
    }

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError("term-jline." + op + ": expected Str, got " + v);
    }

    private static long asInt(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i;
        throw new IrijRuntimeError("term-jline." + op + ": expected Int, got " + v);
    }

    /** Java collections → Irij values, one level of nesting deep (all
     *  the decoder produces). */
    @SuppressWarnings("unchecked")
    private static Object toIrij(Object v) {
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : ((Map<String, Object>) m).entrySet()) {
                out.put(e.getKey(), toIrij(e.getValue()));
            }
            return new IrijMap(out);
        }
        if (v instanceof List<?> l) {
            var out = new ArrayList<Object>(l.size());
            for (Object o : l) out.add(toIrij(o));
            return new IrijVector(out);
        }
        return v;
    }
}
