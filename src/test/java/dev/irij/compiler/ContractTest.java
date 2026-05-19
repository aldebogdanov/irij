package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-mode contract tests — pre/post conditions and in/out
 * module-boundary contracts. Mirrors the interpreter's blame text
 * so existing irij test files (which match on "Pre-condition" /
 * "Post-condition" / "Input contract" / "Output contract") work
 * identically whether interpreted or compiled.
 */
class ContractTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(ContractTest.class.getClassLoader()); }
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

    private static void expectFailure(String source, String fragment) {
        InvocationTargetException ite = assertThrows(
                InvocationTargetException.class, () -> run(source));
        Throwable cause = ite.getTargetException();
        String raw = cause.getMessage();
        if (raw == null && cause.getCause() != null) raw = cause.getCause().getMessage();
        final String msg = raw;
        assertTrue(msg != null && msg.contains(fragment),
                () -> "expected '" + fragment + "' in error, got: " + msg);
    }

    // ── Pre-condition ───────────────────────────────────────────────────

    @Test
    void preConditionPasses() throws Exception {
        String src = """
            fn safe-div :: Int Int Int
              pre (a b -> b > 0)
              (a b -> a / b)
            println (safe-div 10 2)
            """;
        assertEquals("5", run(src));
    }

    @Test
    void preConditionFails() {
        expectFailure("""
            fn safe-div :: Int Int Int
              pre (a b -> b > 0)
              (a b -> a / b)
            println (safe-div 10 0)
            """, "Pre-condition violated in 'safe-div'");
    }

    // ── Post-condition ──────────────────────────────────────────────────

    @Test
    void postConditionPasses() throws Exception {
        String src = """
            fn abs-val :: Int Int
              post (r -> r >= 0)
              (n -> if (n < 0) (0 - n) else n)
            println (abs-val (0 - 7))
            println (abs-val 3)
            """;
        assertEquals("7\n3", run(src).replace("\r\n", "\n"));
    }

    @Test
    void postConditionFails() {
        expectFailure("""
            fn bad-abs :: Int Int
              post (r -> r >= 0)
              (n -> 0 - n)
            println (bad-abs 5)
            """, "Post-condition violated in 'bad-abs'");
    }

    // ── In-contract ─────────────────────────────────────────────────────

    @Test
    void inContractPasses() throws Exception {
        String src = """
            fn add-positive :: Int Int Int
              in (a b -> a > 0)
              in (a b -> b > 0)
              (a b -> a + b)
            println (add-positive 3 4)
            """;
        assertEquals("7", run(src));
    }

    @Test
    void inContractFails() {
        expectFailure("""
            fn add-positive :: Int Int Int
              in (a b -> a > 0)
              (a b -> a + b)
            println (add-positive (0 - 1) 4)
            """, "Input contract violated in 'add-positive'");
    }

    // ── Out-contract ────────────────────────────────────────────────────

    @Test
    void outContractFails() {
        expectFailure("""
            fn double-it :: Int Int
              out (r -> r > 10)
              (n -> n * 2)
            println (double-it 1)
            """, "Output contract violated in 'double-it'");
    }

    // ── Multiple pre-conditions: AND semantics ──────────────────────────

    @Test
    void multiplePreConditionsAnd() {
        expectFailure("""
            fn safe-div :: Int Int Int
              pre (a b -> b > 0)
              pre (a b -> a >= 0)
              (a b -> a / b)
            println (safe-div (0 - 1) 5)
            """, "Pre-condition violated in 'safe-div'");
    }

    // ── Pre + Post combo ────────────────────────────────────────────────

    @Test
    void preAndPostBothChecked() throws Exception {
        String src = """
            fn safe-half :: Int Int
              pre (n -> n > 0)
              post (r -> r >= 0)
              (n -> n / 2)
            println (safe-half 10)
            """;
        assertEquals("5", run(src));
    }
}
