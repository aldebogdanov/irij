package dev.irij.nrepl;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for nREPL session — evaluation, state, errors.
 */
class NReplSessionTest {

    private Map<String, Object> eval(NReplSession session, String code) {
        return session.handleOp(Map.of("op", "eval", "code", code));
    }

    @Test void evalSimpleExpression() {
        var session = new NReplSession();
        var resp = eval(session, "1 + 2");
        assertEquals("3", resp.get("value"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void evalWithStdout() {
        var session = new NReplSession();
        var resp = eval(session, "println 42");
        assertTrue(resp.get("out").toString().contains("42"));
        // println returns Unit, which is now always included as "()"
        assertEquals("()", resp.get("value"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void statePreservedAcrossEvals() {
        var session = new NReplSession();
        eval(session, "x := 10");
        var resp = eval(session, "x + 5");
        assertEquals("15", resp.get("value"));
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
        eval(session, "fn f\n  (x -> x + 1)");
        eval(session, "fn g\n  (x -> f x)");
        // Redefine f
        eval(session, "fn f\n  (x -> x + 100)");
        // g should see the new f
        var resp = eval(session, "g 5");
        assertEquals("105", resp.get("value"));
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

    @Test void evalAlwaysIncludesValue() {
        var session = new NReplSession();
        // Even Unit is returned as "()"
        var resp = eval(session, "println \"hi\"");
        assertEquals("()", resp.get("value"));
    }

    @Test void bindingReturnsValue() {
        var session = new NReplSession();
        var resp = eval(session, "x := 42");
        assertEquals("42", resp.get("value"));
    }

    @Test void backgroundOutOpEmpty() {
        var session = new NReplSession();
        var resp = session.handleOp(Map.of("op", "background-out"));
        assertNull(resp.get("out"));
        assertEquals(List.of("done"), resp.get("status"));
    }

    @Test void backgroundOutCapturesSpawnedOutput() throws Exception {
        var session = new NReplSession();
        // Spawn a thread that prints after a short delay
        eval(session, "spawn (-> sleep 50 ; println \"from-thread\")");
        // Wait for the spawned thread to run
        Thread.sleep(200);
        // Poll background output
        var resp = session.handleOp(Map.of("op", "background-out"));
        assertNotNull(resp.get("out"), "Expected background output from spawned thread");
        assertTrue(resp.get("out").toString().contains("from-thread"));
    }

    @Test void backgroundOutDrainsBuffer() throws Exception {
        var session = new NReplSession();
        eval(session, "spawn (-> sleep 50 ; println \"once\")");
        Thread.sleep(200);
        // First drain gets the output
        var resp1 = session.handleOp(Map.of("op", "background-out"));
        assertTrue(resp1.get("out").toString().contains("once"));
        // Second drain is empty
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
