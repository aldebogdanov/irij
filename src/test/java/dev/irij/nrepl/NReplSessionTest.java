package dev.irij.nrepl;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for nREPL session — evaluation, state, errors.
 *
 * v0.6.13: bytecode-only. Top-level {@code println} side-effects
 * land in the {@code out} field; the value of the last top-level
 * expression (if any) lands in the {@code value} field via
 * {@link dev.irij.compiler.BytecodeSession}'s last-value capture
 * (v0.6.18).
 */
class NReplSessionTest {

    private Map<String, Object> eval(NReplSession session, String code) {
        return session.handleOp(Map.of("op", "eval", "code", code));
    }

    @Test void evalSimpleExpressionPrintsToOut() {
        var session = new NReplSession();
        var resp = eval(session, "println (1 + 2)");
        assertEquals("3\n", resp.get("out"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void evalReturnsLastExpressionValue() {
        var session = new NReplSession();
        var resp = eval(session, "1 + 2");
        assertEquals("3", resp.get("value"));
    }

    @Test void evalReturnsStringConcatValue() {
        var session = new NReplSession();
        var resp = eval(session, "\"1\" ++ \"2\"");
        assertEquals("12", resp.get("value"));
    }

    @Test void evalReturnsUnitWhenNoTrailingExpression() {
        var session = new NReplSession();
        var resp = eval(session, "x := 42");
        assertEquals("()", resp.get("value"));
    }

    @Test void evalReturnsLastExpressionAfterDef() {
        var session = new NReplSession();
        var resp = eval(session,
                "fn double :: Int Int\n  (x -> x * 2)\n\ndouble 21");
        assertEquals("42", resp.get("value"));
    }

    @Test void evalWithStdout() {
        var session = new NReplSession();
        var resp = eval(session, "println 42");
        assertTrue(resp.get("out").toString().contains("42"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void statePreservedAcrossEvals() {
        var session = new NReplSession();
        eval(session, "x := 10");
        var resp = eval(session, "println (x + 5)");
        assertEquals("15\n", resp.get("out"));
    }

    @Test void runtimeError() {
        var session = new NReplSession();
        var resp = eval(session, "undefined-var");
        assertNotNull(resp.get("err"));
        var status = (List<?>) resp.get("status");
        assertTrue(status.contains("error"));
    }

    @Test void parseError() {
        var session = new NReplSession();
        var resp = eval(session, "if if if");
        assertNotNull(resp.get("err"));
        var status = (List<?>) resp.get("status");
        assertTrue(status.contains("error"));
    }

    @Test void describeOp() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "describe"));
        assertNotNull(resp.get("ops"));
        assertNotNull(resp.get("versions"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void closeOp() {
        var session = new NReplSession();
        assertFalse(session.isClosed());
        var resp = session.handleOp(Map.of("op", "close"));
        assertTrue(session.isClosed());
        var status = (List<?>) resp.get("status");
        assertTrue(status.contains("session-closed"));
    }

    @Test void unknownOp() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "nonexistent"));
        assertNotNull(resp.get("err"));
    }

    @Test void missingOp() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("code", "1 + 2"));
        assertNotNull(resp.get("err"));
    }

    @Test void fnRedefinition() {
        var session = new NReplSession();
        eval(session, "fn f ::: Console\n  (x -> x + 1)");
        eval(session, "fn g ::: Console\n  (x -> f x)");
        // Redefine f
        eval(session, "fn f ::: Console\n  (x -> x + 100)");
        // g should see the new f
        var resp = eval(session, "println (g 5)");
        assertEquals("105\n", resp.get("out"));
    }

    @Test void sessionIdIsUnique() {
        var s1 = new NReplSession();
        var s2 = new NReplSession();
        assertNotEquals(s1.id(), s2.id());
    }

    @Test void evalMissingCode() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "eval"));
        assertNotNull(resp.get("err"));
    }

    @Test void backgroundOutOpEmpty() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "background-out"));
        assertNull(resp.get("out"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void backgroundOutCapturesSpawnedOutput() throws Exception {
        var session = new NReplSession();
        eval(session, "spawn (-> sleep 50 ; println \"from-thread\")");
        Thread.sleep(200);
        var resp = session.handleOp(Map.of("op", "background-out"));
        assertNotNull(resp.get("out"), "Expected background output from spawned thread");
        assertTrue(resp.get("out").toString().contains("from-thread"));
    }

    @Test void backgroundOutDrainsBuffer() throws Exception {
        var session = new NReplSession();
        eval(session, "spawn (-> sleep 50 ; println \"once\")");
        Thread.sleep(200);
        var resp1 = session.handleOp(Map.of("op", "background-out"));
        assertTrue(resp1.get("out").toString().contains("once"));
        var resp2 = session.handleOp(Map.of("op", "background-out"));
        assertNull(resp2.get("out"));
    }

    @Test void describeIncludesBackgroundOutOp() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "describe"));
        @SuppressWarnings("unchecked")
        var ops = (Map<String, ?>) resp.get("ops");
        assertTrue(ops.containsKey("background-out"));
    }
}
