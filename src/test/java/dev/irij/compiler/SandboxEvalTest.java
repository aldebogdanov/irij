package dev.irij.compiler;

import dev.irij.runtime.Values;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Playground's sandboxed eval ({@link RuntimeSessions}): a runaway
 * eval must neither outlive its timeout nor take the evaluator, the
 * process stdout, or the heap down with it.
 */
class SandboxEvalTest {

    private static Map<String, Object> eval(String code, long timeoutMs) {
        return ((Values.IrijMap) RuntimeSessions.rawNreplEvalSandboxed(code, timeoutMs)).entries();
    }

    private static final String SPIN = "fn spin\n  (n -> spin (n + 1))\nspin 0";

    @Test void timedOutLoopIsStoppedAndLaterEvalsStillRun() throws Exception {
        long before = cpuBusyEvalThreads();
        var r = eval(SPIN, 300);
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).contains("timed out"), "error: " + r.get("error"));
        // Several more runaways must not starve the evaluator: each is
        // interrupted at its loop's back-edge.
        for (int i = 0; i < 4; i++) eval(SPIN, 200);
        var ok = eval("1 + 2", 2000);
        assertEquals(true, ok.get("ok"), "error: " + ok.get("error"));
        assertEquals("3", ok.get("value"));
        Thread.sleep(300);
        assertEquals(before, cpuBusyEvalThreads(), "a timed-out eval thread is still running");
    }

    private static long cpuBusyEvalThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("irij-eval-") && t.isAlive())
                .count();
    }

    @Test void printFloodIsCappedNotBuffered() {
        var r = eval("fn flood ::: Console\n  => n\n  println \"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"\n  flood (n + 1)\nflood 0", 1500);
        String out = String.valueOf(r.get("stdout"));
        assertTrue(out.length() <= RuntimeSessions.MAX_OUTPUT_BYTES + 100, "stdout length " + out.length());
        assertTrue(out.contains("[output truncated"), "no truncation notice");
    }

    @Test void evalDoesNotHijackProcessStdout() throws Exception {
        PrintStream orig = System.out;
        ByteArrayOutputStream process = new ByteArrayOutputStream();
        System.setOut(new PrintStream(process, true));
        try {
            // A runaway eval in flight while this thread prints.
            Thread runaway = Thread.ofPlatform().start(() -> eval(SPIN, 600));
            Thread.sleep(150);
            System.out.println("from-the-host");
            var r = eval("println \"from-the-sandbox\"", 2000);
            runaway.join();
            assertTrue(process.toString().contains("from-the-host"),
                    "host output was captured by a sandbox: " + process);
            assertFalse(process.toString().contains("from-the-sandbox"),
                    "sandbox output leaked to the host");
            assertTrue(String.valueOf(r.get("stdout")).contains("from-the-sandbox"));
        } finally {
            System.setOut(orig);
        }
    }

    @Test void deepRecursionReportsStackOverflow() {
        var r = eval("fn deep\n  (n -> 1 + (deep (n + 1)))\ndeep 0", 5000);
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).toLowerCase().contains("stack overflow"),
                "error: " + r.get("error"));
    }

    @Test void sessionEvalsRunOneAtATime() throws Exception {
        String id = (String) RuntimeSessions.rawSessionCreate();
        try {
            Thread slow = Thread.ofPlatform().start(() ->
                    RuntimeSessions.rawSessionEval(id, "sleep 400\nx := 1", 2000L));
            Thread.sleep(100);
            var r = ((Values.IrijMap) RuntimeSessions.rawSessionEval(id, "x + 1", 2000L)).entries();
            slow.join();
            assertEquals(true, r.get("ok"), "error: " + r.get("error"));
            assertEquals("2", r.get("value"));
        } finally {
            RuntimeSessions.rawSessionDestroy(id);
        }
    }
}
