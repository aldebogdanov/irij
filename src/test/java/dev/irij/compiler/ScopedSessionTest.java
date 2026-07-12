package dev.irij.compiler;

import dev.irij.nrepl.NReplSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the ScopedValue session model (PR3 2026-07).
 *
 * NS and SESSION_OUT are ScopedValues bound lexically around each
 * session eval; fibers spawned during an eval must keep working after
 * the eval's binding ends, because they re-bind from ParentSnapshot.
 */
class ScopedSessionTest {

    private Map<String, Object> eval(NReplSession session, String code) {
        return session.handleOp(Map.of("op", "eval", "code", code));
    }

    @Test void namespacePersistsAcrossEvals() {
        var session = new NReplSession();
        eval(session, "x := 41");
        var resp = eval(session, "x + 1");
        assertEquals("42", resp.get("value"));
    }

    @Test void sessionStateDoesNotLeakBetweenSessions() {
        var s1 = new NReplSession();
        var s2 = new NReplSession();
        eval(s1, "secret := 7");
        var resp = eval(s2, "secret");
        // s2 must NOT see s1's binding
        assertNotEquals("7", resp.get("value"));
        assertEquals(List.of("done", "error"), resp.get("status"));
    }

    @Test void spawnedFiberKeepsSessionOutputAfterEvalReturns() throws Exception {
        var session = new NReplSession();
        // The fiber sleeps past the synchronous eval, then prints.
        // Its output must land in the session buffer (ParentSnapshot
        // re-bind), not the process stdout.
        eval(session, "spawn (-> sleep 80 |> (_ -> println \"late-fiber\"))");
        Thread.sleep(400);
        var resp = eval(session, "1");
        String out = String.valueOf(resp.get("out"));
        assertTrue(out.contains("late-fiber"),
                "expected fiber output in session buffer, got: " + out);
    }

    @Test void nsGetOutsideAnySessionFallsBackToGlobal() {
        RuntimeSupport.nsPut("scoped-test-global", 99L);
        assertEquals(99L, RuntimeSupport.nsGet("scoped-test-global"));
    }
}
