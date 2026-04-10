package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Values.*;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 12b: sandboxed evaluation and playground builtins.
 */
class PlaygroundTest {

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
    // Sandboxed interpreter
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void sandboxedInterpreterBlocksFileOps() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var interp = new Interpreter(out, true);
        interp.setSpecLintEnabled(false);

        var parseResult = IrijParseDriver.parse("read-file \"test.txt\"\n");
        var ast = new AstBuilder().build(parseResult.tree());

        assertThrows(IrijRuntimeError.class, () -> interp.run(ast),
            "read-file should be blocked in sandbox");
    }

    @Test
    void sandboxedInterpreterBlocksDbOps() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var interp = new Interpreter(out, true);
        interp.setSpecLintEnabled(false);

        var parseResult = IrijParseDriver.parse("raw-db-open \":memory:\"\n");
        var ast = new AstBuilder().build(parseResult.tree());

        assertThrows(IrijRuntimeError.class, () -> interp.run(ast),
            "raw-db-open should be blocked in sandbox");
    }

    @Test
    void sandboxedInterpreterAllowsPureComputation() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var interp = new Interpreter(out, true);
        interp.setSpecLintEnabled(false);

        var parseResult = IrijParseDriver.parse("1 + 2\n");
        var ast = new AstBuilder().build(parseResult.tree());

        assertEquals(3L, interp.run(ast));
    }

    @Test
    void sandboxedInterpreterAllowsPrintln() {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var interp = new Interpreter(out, true);
        interp.setSpecLintEnabled(false);

        var parseResult = IrijParseDriver.parse("println \"hello sandbox\"\n");
        var ast = new AstBuilder().build(parseResult.tree());
        interp.run(ast);

        assertEquals("hello sandbox", baos.toString().strip());
    }

    // ═══════════════════════════════════════════════════════════════════
    // raw-nrepl-eval-sandboxed
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void evalSandboxedSimpleExpression() {
        var result = eval("raw-nrepl-eval-sandboxed \"1 + 2\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.TRUE, map.entries().get("ok"));
        assertEquals("3", map.entries().get("value"));
    }

    @Test
    void evalSandboxedWithStdout() {
        var result = eval("raw-nrepl-eval-sandboxed \"println 42\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.TRUE, map.entries().get("ok"));
        assertTrue(((String) map.entries().get("stdout")).contains("42"));
    }

    @Test
    void evalSandboxedParseError() {
        // "if" alone is a parse error (keyword without condition/branches)
        var result = eval("raw-nrepl-eval-sandboxed \"if\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        // Parse errors return ok=false with error message
        assertEquals(Boolean.FALSE, map.entries().get("ok"),
            () -> "Expected ok=false, got: " + map);
    }

    @Test
    void evalSandboxedRuntimeError() {
        var result = eval("raw-nrepl-eval-sandboxed \"error \\\"boom\\\"\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.FALSE, map.entries().get("ok"));
        assertTrue(((String) map.entries().get("error")).contains("boom"));
    }

    @Test
    void evalSandboxedBlocksFileIO() {
        var result = eval("raw-nrepl-eval-sandboxed \"read-file \\\"test.txt\\\"\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.FALSE, map.entries().get("ok"));
        assertTrue(((String) map.entries().get("error")).contains("sandbox"));
    }

    @Test
    void evalSandboxedBlocksDbOpen() {
        var result = eval("raw-nrepl-eval-sandboxed \"raw-db-open \\\":memory:\\\"\" 10000");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.FALSE, map.entries().get("ok"));
        assertTrue(((String) map.entries().get("error")).contains("sandbox"));
    }

    @Test
    void evalSandboxedTimeout() {
        // Infinite loop should timeout
        var result = eval("""
            fn infinite-loop
              (x -> infinite-loop (x + 1))
            raw-nrepl-eval-sandboxed "infinite-loop 0" 500
            """);
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        // Should either timeout or be cancelled
        assertEquals(Boolean.FALSE, map.entries().get("ok"));
    }

    @Test
    void evalSandboxedMultipleExpressions() {
        var output = run("""
            r1 := raw-nrepl-eval-sandboxed "1 + 1" 10000
            r2 := raw-nrepl-eval-sandboxed "2 * 3" 10000
            println r1.value
            println r2.value
            """);
        assertEquals("2\n6", output);
    }

    @Test
    void evalSandboxedFunctionDefinition() {
        // fn body on next line (indented) — Irij requires this
        var result = eval(
            "raw-nrepl-eval-sandboxed \"fn double\\n  (x -> x * 2)\\ndouble 21\" 10000\n");
        assertInstanceOf(IrijMap.class, result);
        var map = (IrijMap) result;
        assertEquals(Boolean.TRUE, map.entries().get("ok"),
            () -> "Expected ok=true, got: " + map);
        assertEquals("42", map.entries().get("value"));
    }
}
