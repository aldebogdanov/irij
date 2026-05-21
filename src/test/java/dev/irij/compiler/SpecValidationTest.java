package dev.irij.compiler;

import dev.irij.IrijRuntimeError;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-mode spec validation tests. Compiles snippets with
 * non-trivial spec annotations and verifies that mismatched inputs /
 * outputs throw the expected {@link IrijRuntimeError} at runtime —
 * proving the bytecode emitter's spec checks are at full parity with
 * the interpreter for all the SpecExpr variants we support.
 */
class SpecValidationTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(SpecValidationTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    /** Run a snippet and expect a runtime spec failure with the given fragment. */
    private static void expectFailure(String source, String expectedFragment) {
        InvocationTargetException ite = assertThrows(
                InvocationTargetException.class, () -> run(source));
        Throwable cause = ite.getTargetException();
        // Unwrap nested errors — the bytecode often wraps in
        // IrijRuntimeError directly, but tools may surface as a
        // RuntimeException with the cause set.
        String raw = cause.getMessage();
        if (raw == null && cause.getCause() != null) raw = cause.getCause().getMessage();
        final String msg = raw;
        assertTrue(msg != null && msg.contains(expectedFragment),
                () -> "expected '" + expectedFragment + "' in error, got: " + msg);
    }

    // ── Primitive input ─────────────────────────────────────────────────

    @Test
    void primitiveInputOk() throws Exception {
        String src = """
            fn dbl :: Int Int
              (n -> n * 2)
            println (dbl 21)
            """;
        assertEquals("42", run(src));
    }

    @Test
    void primitiveInputFailure() {
        expectFailure("""
            fn dbl :: Int Int
              (n -> n * 2)
            println (dbl "oops")
            """, "expected Int");
    }

    // ── Output spec ─────────────────────────────────────────────────────

    @Test
    void outputSpecOk() throws Exception {
        String src = """
            fn label :: Int Str
              (n -> "n is " ++ to-str n)
            println (label 7)
            """;
        assertEquals("n is 7", run(src));
    }

    @Test
    void outputSpecFailure() {
        // Body returns an Int but annotation says Str → output check fails.
        expectFailure("""
            fn lies :: Int Str
              (n -> n + 1)
            println (lies 5)
            """, "output of lies");
    }

    @Test
    void outputSpecMatchArmsOk() throws Exception {
        String src = """
            fn classify :: Int Str
              0 => "zero"
              n => "many"
            println (classify 0)
            println (classify 3)
            """;
        assertEquals("zero\nmany", run(src).replace("\r\n", "\n"));
    }

    @Test
    void outputSpecAllArmsValidated() {
        // Match arm returns an Int when annotation says Str.
        expectFailure("""
            fn bad :: Int Str
              0 => "zero"
              n => n
            println (bad 5)
            """, "output of bad");
    }

    // ── Composite input: Vec[Int] ──────────────────────────────────────

    @Test
    void vecIntInputOk() throws Exception {
        String src = """
            fn sum-vec :: #[Int] Int
              (v -> fold (+) 0 v)
            println (sum-vec #[1 2 3 4])
            """;
        assertEquals("10", run(src));
    }

    @Test
    void vecIntInputRejectsStrElement() {
        expectFailure("""
            fn sum-vec :: #[Int] Int
              (v -> fold (+) 0 v)
            println (sum-vec #[1 "two" 3])
            """, "expected Int");
    }

    @Test
    void vecIntInputRejectsNonVec() {
        expectFailure("""
            fn sum-vec :: #[Int] Int
              (v -> fold (+) 0 v)
            println (sum-vec 42)
            """, "expected Vec");
    }

    // ── Wildcard / Any pass-through ─────────────────────────────────────

    @Test
    void wildcardInputAcceptsAnything() throws Exception {
        String src = """
            fn id-ish :: _ Int
              (x -> 1)
            println (id-ish "ok")
            println (id-ish 99)
            """;
        assertEquals("1\n1", run(src).replace("\r\n", "\n"));
    }

    // ── Encode/decode round-trip sanity ─────────────────────────────────

    @Test
    void encodeDecodeRoundTrips() {
        // Sanity: SpecValidator parses what it serialised.
        var spec = new dev.irij.ast.SpecExpr.App("Map", java.util.List.of(
                new dev.irij.ast.SpecExpr.Name("Str"),
                new dev.irij.ast.SpecExpr.VecSpec(new dev.irij.ast.SpecExpr.Name("Int"))));
        String encoded = SpecValidator.encode(spec);
        var decoded = SpecValidator.decode(encoded);
        // VecSpec round-trips into VecSpec (normalised form).
        assertEquals("Map[Str,Vec[Int]]", encoded);
        assertTrue(decoded instanceof dev.irij.ast.SpecExpr.App);
    }

    @Test
    void encodeDecodeArrow() {
        var spec = new dev.irij.ast.SpecExpr.Arrow(
                java.util.List.of(
                        new dev.irij.ast.SpecExpr.Name("Int"),
                        new dev.irij.ast.SpecExpr.Name("Int")),
                new dev.irij.ast.SpecExpr.Name("Bool"));
        String encoded = SpecValidator.encode(spec);
        assertEquals("(Int,Int->Bool)", encoded);
        var decoded = SpecValidator.decode(encoded);
        assertTrue(decoded instanceof dev.irij.ast.SpecExpr.Arrow);
    }

    @Test
    void encodeDecodeEnum() {
        var spec = new dev.irij.ast.SpecExpr.Enum(java.util.List.of("ok", "error"));
        String encoded = SpecValidator.encode(spec);
        assertEquals(":ok|:error", encoded);
        var decoded = SpecValidator.decode(encoded);
        assertTrue(decoded instanceof dev.irij.ast.SpecExpr.Enum);
    }
}
