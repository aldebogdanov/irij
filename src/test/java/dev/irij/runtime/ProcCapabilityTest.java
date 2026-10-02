package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.IrijVector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Proc capability against real child processes (POSIX sh). */
class ProcCapabilityTest {

    private static IrijMap opts(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new IrijMap(m);
    }

    private static IrijVector cmd(String... words) {
        return new IrijVector(List.of((Object[]) words));
    }

    private static Object get(Object map, String key) {
        return ((IrijMap) map).entries().get(key);
    }

    @Test void run_returns_exit_code_and_both_streams() {
        var r = ProcCapability.run(opts("cmd", cmd("sh", "-c", "echo out; echo err 1>&2; exit 3")));
        assertEquals(3L, get(r, "exit"));
        assertEquals("out\n", get(r, "stdout"));
        assertEquals("err\n", get(r, "stderr"));
        assertEquals(false, get(r, "timed-out?"));
    }

    @Test void run_feeds_stdin_and_honours_dir_and_env() {
        var r = ProcCapability.run(opts("cmd", cmd("sh", "-c", "cat; pwd; echo $GREETING"),
                "stdin", "fed\n", "dir", "/", "env", opts("GREETING", "hi")));
        assertEquals("fed\n/\nhi\n", get(r, "stdout"));
    }

    @Test void run_kills_a_command_past_its_timeout() {
        long t0 = System.nanoTime();
        var r = ProcCapability.run(opts("cmd", cmd("sleep", "30"), "timeout-ms", 200L));
        assertEquals(true, get(r, "timed-out?"));
        assertEquals(-1L, get(r, "exit"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "returned long after the timeout");
    }

    @Test void started_process_streams_lines_both_ways() {
        var h = ProcCapability.start(opts("cmd", cmd("sh", "-c", "echo one; read x; echo got $x; echo bye 1>&2")));
        assertEquals("one", get(ProcCapability.line(h, 5000L), "text"));
        ProcCapability.send(h, "two\n");
        assertEquals("got two", get(ProcCapability.line(h, 5000L), "text"));
        assertEquals("eof", get(ProcCapability.line(h, 5000L), "kind"));
        assertEquals("eof", get(ProcCapability.line(h, 0L), "kind"), "eof stays at the end");
        var w = ProcCapability.await(h, 5000L);
        assertEquals(0L, get(w, "exit"));
        assertEquals("bye\n", get(w, "stderr"));
    }

    @Test void line_times_out_when_nothing_arrives_and_kill_ends_it() {
        var h = ProcCapability.start(opts("cmd", cmd("sleep", "30")));
        assertEquals("timeout", get(ProcCapability.line(h, 50L), "kind"));
        ProcCapability.kill(h);
        assertThrows(IrijRuntimeError.class, () -> ProcCapability.line(h, 0L), "a killed handle is gone");
    }

    @Test void a_missing_command_is_an_error_naming_it() {
        var e = assertThrows(IrijRuntimeError.class,
                () -> ProcCapability.run(opts("cmd", cmd("no-such-binary-irij"))));
        assertTrue(e.getMessage().contains("no-such-binary-irij"), e.getMessage());
        assertThrows(IrijRuntimeError.class, () -> ProcCapability.run(opts("cmd", cmd())));
    }
}
