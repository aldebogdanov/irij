package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Values.*;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Datastar SDK: SSE builtins, session manager, url-encode/decode.
 */
class DatastarTest {

    private String run(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        interp.run(ast);
        return baos.toString().strip();
    }

    private Object eval(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        return interp.run(ast);
    }

    // ═══════════════════════════════════════════════════════════════════
    // URL encode/decode builtins
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void urlEncodeBasic() {
        assertEquals("hello+world", run("println (url-encode \"hello world\")"));
    }

    @Test
    void urlDecodeBasic() {
        assertEquals("hello world", run("println (url-decode \"hello+world\")"));
    }

    @Test
    void urlEncodeDecodeRoundtrip() {
        assertEquals("a=1&b=2", run("println (url-decode (url-encode \"a=1&b=2\"))"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Session manager builtins
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Sessions {

        @Test
        void createSessionReturnsUUID() {
            var result = eval("raw-session-create ()");
            assertInstanceOf(String.class, result);
            // UUID format: 8-4-4-4-12 hex chars
            assertTrue(((String) result).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }

        @Test
        void sessionEvalSimple() {
            var result = eval("""
                id := raw-session-create ()
                raw-session-eval id "1 + 2" 5000
                """);
            assertInstanceOf(IrijMap.class, result);
            var map = ((IrijMap) result).entries();
            assertEquals("3", map.get("value"));
            assertEquals(Boolean.TRUE, map.get("ok"));
        }

        @Test
        void sessionPersistsState() {
            // First eval defines x, second eval uses x
            var result = eval("""
                id := raw-session-create ()
                raw-session-eval id "x := 42" 5000
                raw-session-eval id "x + 8" 5000
                """);
            assertInstanceOf(IrijMap.class, result);
            var map = ((IrijMap) result).entries();
            assertEquals("50", map.get("value"));
            assertEquals(Boolean.TRUE, map.get("ok"));
        }

        @Test
        void sessionPersistsFunctions() {
            // Use string concat to embed newlines in the eval code
            var result = eval(
                "id := raw-session-create ()\n" +
                "code := \"fn double\" ++ \"\\n\" ++ \"  (x -> x * 2)\"\n" +
                "raw-session-eval id code 5000\n" +
                "raw-session-eval id \"double 21\" 5000\n");
            var map = ((IrijMap) result).entries();
            assertEquals("42", map.get("value"));
        }

        @Test
        void sessionCapturesStdout() {
            var result = eval("""
                id := raw-session-create ()
                raw-session-eval id "println 42" 5000
                """);
            var map = ((IrijMap) result).entries();
            assertEquals("42\n", map.get("stdout"));
        }

        @Test
        void sessionIsolation() {
            // Two sessions should not share state
            var result = eval("""
                id1 := raw-session-create ()
                id2 := raw-session-create ()
                raw-session-eval id1 "x := 100" 5000
                r := raw-session-eval id2 "x" 5000
                get "ok" r
                """);
            // x is not defined in session 2, so eval fails
            assertEquals(Boolean.FALSE, result);
        }

        @Test
        void sessionDestroy() {
            var result = run("""
                id := raw-session-create ()
                raw-session-destroy id
                r := try (-> raw-session-eval id "1" 5000)
                match r
                  Err msg => println msg
                  Ok _ => println "should not reach"
                """);
            assertTrue(result.contains("no session"));
        }

        @Test
        void sessionSandboxed() {
            // Sessions should be sandboxed — no file I/O
            // Use a command that doesn't need quotes in the eval string
            var result = eval("raw-session-eval (raw-session-create ()) \"read-file (to-str 1)\" 5000\n");
            assertInstanceOf(IrijMap.class, result);
            var map = ((IrijMap) result).entries();
            assertEquals(Boolean.FALSE, map.get("ok"));
            assertTrue(map.get("error").toString().contains("not available in sandbox"));
        }

        @Test
        void sessionTimeout() {
            // Use sleep to trigger timeout without complex fn definitions
            var result = eval("""
                id := raw-session-create ()
                raw-session-eval id "sleep 5000" 100
                """);
            var map = ((IrijMap) result).entries();
            assertEquals(Boolean.FALSE, map.get("ok"));
            assertTrue(map.get("error").toString().contains("timed out"));
        }

        @Test
        void sessionCleanup() {
            var result = run("""
                id := raw-session-create ()
                sleep 10
                ;; cleanup sessions idle for > 1 ms
                removed := raw-session-cleanup 1
                println removed
                """);
            assertEquals("1", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SSE builtins (unit tests without HTTP)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class SseBuiltins {

        @Test
        void sseBlockedInSandbox() {
            var baos = new ByteArrayOutputStream();
            var out = new PrintStream(baos);
            var interp = new Interpreter(out, true);
            interp.setSpecLintEnabled(false);

            var parseResult = IrijParseDriver.parse("raw-sse-response {}\n");
            var ast = new AstBuilder().build(parseResult.tree());
            assertThrows(IrijRuntimeError.class, () -> interp.run(ast));
        }

        @Test
        void sseWriterDirectConstruction() {
            // Test SseWriter with a ByteArrayOutputStream (simulates HTTP exchange)
            var baos = new ByteArrayOutputStream();
            var writer = new SseWriter(null, baos);
            assertFalse(writer.isClosed());

            try {
                writer.send("test-event", "hello world");
            } catch (java.io.IOException e) {
                fail("Should not throw: " + e);
            }

            var output = baos.toString();
            assertTrue(output.contains("event: test-event"));
            assertTrue(output.contains("data: hello world"));
            assertTrue(output.endsWith("\n\n"));
        }

        @Test
        void sseWriterMultilineData() throws java.io.IOException {
            var baos = new ByteArrayOutputStream();
            var writer = new SseWriter(null, baos);
            writer.send("datastar-patch-elements", "selector #out\nmode outer\nelements <div>hi</div>");

            var output = baos.toString();
            assertTrue(output.contains("data: selector #out\n"));
            assertTrue(output.contains("data: mode outer\n"));
            assertTrue(output.contains("data: elements <div>hi</div>\n"));
        }

        @Test
        void sseWriterClose() {
            var baos = new ByteArrayOutputStream();
            var writer = new SseWriter(null, baos);
            assertFalse(writer.isClosed());
            writer.close();
            assertTrue(writer.isClosed());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // std.datastar module loading
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void datastarModuleLoads() {
        // Verify the module loads without errors
        assertDoesNotThrow(() -> run("use std.datastar :open"));
    }
}
