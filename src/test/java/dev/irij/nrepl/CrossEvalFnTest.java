package dev.irij.nrepl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 + R2: cross-eval fn defs in bytecode-mode nREPL. With the
 * default `eval` op now routing to bytecode (R1), a fn defined in
 * one eval must be callable from the next (R2). The clinit of
 * each emitted class registers each fn as an IrijFn in the
 * session's namespace map via {@code RT.nsPut}; subsequent
 * compilations resolve unknown user-fn names through
 * {@code RT.nsGet}.
 */
class CrossEvalFnTest {

    private Map<String, Object> eval(NReplSession session, String code) {
        return session.handleOp(Map.of("op", "eval", "code", code));
    }

    @Test void fnDefinedInPriorEvalIsCallable() {
        var session = new NReplSession();

        // Eval 1: define a fn.
        var def = eval(session, "fn double\n  (x -> x * 2)");
        assertEquals(List.of("done"), def.get("status"),
                () -> "expected eval to succeed; got " + def);

        // Eval 2: call the fn from a fresh compilation unit.
        var call = eval(session, "println (double 21)");
        assertEquals(List.of("done"), call.get("status"),
                () -> "expected cross-eval call to succeed; got " + call);
        assertNotNull(call.get("out"));
        assertTrue(call.get("out").toString().contains("42"),
                () -> "expected 42 in stdout, got: " + call.get("out"));
    }

    @Test void chainOfThreeEvals() {
        var session = new NReplSession();
        eval(session, "fn inc\n  (x -> x + 1)");
        eval(session, "fn dbl\n  (x -> x * 2)");
        var out = eval(session, "println (dbl (inc 20))");
        assertTrue(out.get("out").toString().contains("42"));
    }

    @Test void bindThenCallUserFn() {
        var session = new NReplSession();
        eval(session, "fn add-three\n  (x -> x + 3)");
        eval(session, "y := 10");
        var out = eval(session, "println (add-three y)");
        assertTrue(out.get("out").toString().contains("13"),
                () -> "expected 13, got: " + out.get("out"));
    }

    @Test void redefiningFnUpdatesSubsequentCalls() {
        var session = new NReplSession();
        eval(session, "fn pick\n  (-> 1)");
        var first = eval(session, "println (pick ())");
        assertTrue(first.get("out").toString().contains("1"),
                () -> "expected 1, got: " + first.get("out"));

        // Redefine
        eval(session, "fn pick\n  (-> 99)");
        var second = eval(session, "println (pick ())");
        assertTrue(second.get("out").toString().contains("99"),
                () -> "expected 99 after redef, got: " + second.get("out"));
    }
}
