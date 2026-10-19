package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Self-tail-call optimization (compiler).
 *
 * <p>Self-recursive calls in tail position lower to GOTO + arg rebind
 * instead of INVOKESTATIC, so deep recursion runs in O(1) JVM frames.
 * Without this, 1M deep `sum-to` would blow the stack at ~10K.
 */
class TcoTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(TcoTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source, CompileOptions opts) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program", null, opts);
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

    @Test void selfTailCall1MDeep() throws Exception {
        // sum-to(0, 1_000_000) = 500000500000. Recursion depth is 1M;
        // without TCO this blows the JVM stack (typical limit ~10K).
        String src = """
            fn sum-to
              (acc n -> if (n == 0) acc else (sum-to (acc + n) (n - 1)))

            println (sum-to 0 1000000)
            """;
        assertEquals("500000500000", run(src, CompileOptions.defaults()));
        assertEquals("500000500000", run(src, CompileOptions.defaults()));
    }

    @Test void selfTailCallInIfThenBranch() throws Exception {
        // Tail position in only the then branch. else is a plain value.
        String src = """
            fn count-down
              (n -> if (n == 0) "done" else (count-down (n - 1)))

            println (count-down 50000)
            """;
        assertEquals("done", run(src, CompileOptions.defaults()));
    }

    @Test void selfTailCallArgsUseOldParamValues() throws Exception {
        // Both new-arg expressions reference original param values; the
        // emitter must evaluate all args BEFORE rebinding any slot.
        // sum-to(0, 100) = 5050.
        String src = """
            fn sum-to
              (acc n -> if (n == 0) acc else (sum-to (acc + n) (n - 1)))

            println (sum-to 0 100)
            """;
        assertEquals("5050", run(src, CompileOptions.defaults()));
    }

    @Test void nonTailCallIsNotRewritten() throws Exception {
        // 1 + (recursive call): the recursive call is NOT in tail position
        // (the +1 happens after). Must compile to a normal INVOKESTATIC,
        // not a GOTO. Small N to keep stack usage bounded.
        String src = """
            fn f
              (n -> if (n == 0) 0 else (1 + (f (n - 1))))

            println (f 100)
            """;
        assertEquals("100", run(src, CompileOptions.defaults()));
    }

    // ── Match arms ──────────────────────────────────────────────────────
    // A match-arms body (and a match in tail position) used to emit an
    // ordinary match and return its value, so `n => loop (n - 1)` was a
    // JVM call and 1M deep overflowed the stack.

    @Test void selfTailCallInMatchArm1MDeep() throws Exception {
        String src = """
            fn down
              0 => "done"
              n => down (n - 1)

            println (down 1000000)
            """;
        assertEquals("done", run(src, CompileOptions.defaults()));
    }

    @Test void selfTailCallInGuardedArm() throws Exception {
        String src = """
            fn count-to
              #(n limit) | n >= limit => n
              #(n limit) => count-to #((n + 1) limit)

            println (count-to #(0 1000000))
            """;
        assertEquals("1000000", run(src, CompileOptions.defaults()));
    }

    @Test void selfTailCallInTailMatchStatement() throws Exception {
        String src = """
            fn sum-list
              => acc xs
              match xs
                #[] => acc
                #[x ...rest] => sum-list (acc + x) rest

            println (sum-list 0 (to-vec (1..200000)))
            """;
        assertEquals("20000100000", run(src, CompileOptions.defaults()));
    }

    @Test void tailMatchStillChecksOutputSpecAndPost() throws Exception {
        String src = """
            fn check :: Int Str
              post (r -> r == "zero")
              0 => "zero"
              n => check (n - 1)

            println (check 300000)
            """;
        assertEquals("zero", run(src, CompileOptions.defaults()));
    }

    @Test void nonTailArmCallIsNotRewritten() throws Exception {
        String src = """
            fn fact
              0 => 1
              n => n * fact (n - 1)

            println (fact 20)
            """;
        assertEquals("2432902008176640000", run(src, CompileOptions.defaults()));
    }
}
