package dev.irij.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end test for capability dispatch (phase 2).
 *
 * <p>Compiles an Irij program that declares an effect, binds a
 * capability to {@link TestCounterCapability}, writes a handler that
 * routes the effect op through the cap's static method, and runs
 * the program. Verifies that the cap call actually reaches the Java
 * method, that state mutations on the provider survive across calls,
 * and that the result flows back through the handler's resume.
 *
 * <p>The provider is a top-level class (sibling of this test) rather
 * than a nested {@code static class} so its FQN is a clean dotted
 * name — exercises the documented "fully-qualified provider class"
 * shape rather than the {@code Outer$Inner} corner case.
 */
class CapDispatchTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(CapDispatchTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static Class<?> compileAndLoad(String source, String className) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, className,
                null, CompileOptions.defaults());
        return new BytesLoader().define(className, bytes);
    }

    private static String runMain(Class<?> cls) throws Exception {
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    @BeforeEach
    void resetProviderState() {
        TestCounterCapability.reset();
    }

    @Test
    void capCallDispatchesToProviderStaticMethod() throws Exception {
        // The clause body invokes `test-counter.bump ()` — phase-2 emit
        // rewrites this to the JavaRef-equivalent call against the
        // bound provider class, which increments the static counter
        // and returns the new value boxed as Long.
        String src = """
            effect Counter
              bump :: () -> Int
            cap test-counter :: Counter = "dev.irij.compiler.TestCounterCapability"
            handler default-counter :: Counter ::: JVM
              bump () => resume (test-counter.bump ())
            fn run ::: Counter
              _ =>
                with default-counter
                  a := bump ()
                  b := bump ()
                  c := bump ()
                  to-str a ++ "," ++ to-str b ++ "," ++ to-str c
            println (run ())
            """;
        Class<?> cls = compileAndLoad(src, "irij.CapDispatch1");
        assertEquals("1,2,3", runMain(cls));
        // Provider should reflect the three calls.
        assertEquals(3, TestCounterCapability.currentValue());
    }

    @Test
    void capCallPassesArgsThroughToProvider() throws Exception {
        // Two-arg provider method: `test-counter.add(x, y)`. Verifies
        // arg evaluation order + boxing on the Irij→Java boundary.
        String src = """
            effect Counter
              bump :: () -> Int
            cap test-counter :: Counter = "dev.irij.compiler.TestCounterCapability"
            handler default-counter :: Counter ::: JVM
              bump () => resume (test-counter.add 10 32)
            fn run ::: Counter
              _ =>
                with default-counter
                  bump ()
            println (run ())
            """;
        Class<?> cls = compileAndLoad(src, "irij.CapDispatch2");
        assertEquals("42", runMain(cls));
    }

    @Test
    void multipleCapsPerEffectPickSeparateProviders() throws Exception {
        // Two caps for the same effect with two different handlers;
        // each handler picks one cap. Demonstrates the design point
        // that mock handlers can swap providers without touching the
        // user-facing op surface.
        String src = """
            effect Counter
              bump :: () -> Int
            cap real-counter :: Counter = "dev.irij.compiler.TestCounterCapability"
            cap mock-counter :: Counter = "dev.irij.compiler.MockCounterCapability"
            handler real-h :: Counter ::: JVM
              bump () => resume (real-counter.bump ())
            handler mock-h :: Counter ::: JVM
              bump () => resume (mock-counter.bump ())
            fn run-real ::: Counter
              _ =>
                with real-h
                  bump ()
            fn run-mock ::: Counter
              _ =>
                with mock-h
                  bump ()
            println (to-str (run-real ()))
            println (to-str (run-mock ()))
            """;
        Class<?> cls = compileAndLoad(src, "irij.CapDispatch3");
        String out = runMain(cls);
        assertEquals("1\n9999".replace("\n", System.lineSeparator()), out);
    }
}
