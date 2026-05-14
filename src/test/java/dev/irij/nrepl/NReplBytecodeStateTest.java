package dev.irij.nrepl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-eval state for `eval-bytecode`: top-level `:=` binds in one
 * eval are visible in the next eval, all running through the
 * bytecode-compiled path.
 *
 * <p>Implementation: {@code RuntimeSupport.NS} thread-local holds the
 * session's shared namespace; the emitter (with
 * {@code CompileOptions.namespaceMode = true}) writes top-level binds
 * via {@code nsPut} and falls back to {@code nsGet} on unresolved
 * Var loads.
 */
class NReplBytecodeStateTest {

    private static Map<String, Object> evalBC(NReplSession s, String code) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("op", "eval-bytecode");
        msg.put("code", code);
        return s.handleOp(msg);
    }

    @Test void cross_eval_simple_bind() {
        var s = new NReplSession();
        Map<String, Object> r1 = evalBC(s, "x := 42");
        assertEquals(List.of("done"), r1.get("status"));

        Map<String, Object> r2 = evalBC(s, "println x");
        assertEquals(List.of("done"), r2.get("status"));
        assertEquals("42\n", r2.get("out"));
    }

    @Test void redefine_bind_in_next_eval() {
        var s = new NReplSession();
        evalBC(s, "x := 1");
        evalBC(s, "x := 100");
        var r = evalBC(s, "println x");
        assertEquals("100\n", r.get("out"));
    }

    @Test void compute_using_prior_binds() {
        var s = new NReplSession();
        evalBC(s, "a := 10");
        evalBC(s, "b := 20");
        var r = evalBC(s, "println (a + b)");
        assertEquals("30\n", r.get("out"));
    }

    @Test void unbound_throws() {
        var s = new NReplSession();
        var r = evalBC(s, "println undefined-thing");
        assertEquals(List.of("done", "error"), r.get("status"));
        assertTrue(((String) r.get("err")).contains("Unbound"),
                "expected Unbound-variable error, got: " + r.get("err"));
    }

    @Test void state_isolated_between_sessions() {
        var s1 = new NReplSession();
        var s2 = new NReplSession();
        evalBC(s1, "leaked := \"only-s1\"");
        var r = evalBC(s2, "println leaked");
        assertEquals(List.of("done", "error"), r.get("status"));
    }
}
