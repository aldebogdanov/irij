package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR4 language additions: curried lambda chains `(a -> b -> body)`
 * and dynamic map keys `{(expr)= val}`.
 */
class CurriedAndDynKeysTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(CurriedAndDynKeysTest.class.getClassLoader()); }
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

    // ── curried lambdas ──────────────────────────────────────────────

    @Test void curriedTwoLevels() throws Exception {
        assertEquals("3", run("""
                add := (a -> b -> a + b)
                println ((add 1) 2)
                """));
    }

    @Test void curriedThreeLevels() throws Exception {
        assertEquals("6", run("""
                f := (a -> b -> c -> a + b + c)
                println (((f 1) 2) 3)
                """));
    }

    @Test void curriedInnerMultiParam() throws Exception {
        assertEquals("6", run("""
                g := (a -> b c -> a + b + c)
                println ((g 1) 2 3)
                """));
    }

    @Test void plainLambdaUnaffected() throws Exception {
        assertEquals("5", run("""
                inc := (x -> x + 1)
                println (inc 4)
                """));
    }

    @Test void curriedPartialApplicationAsValue() throws Exception {
        assertEquals("#[11 12 13]", run("""
                add := (a -> b -> a + b)
                println (#[1 2 3] |> @ (add 10))
                """));
    }

    // ── dynamic map keys ─────────────────────────────────────────────

    @Test void dynamicKeyFromVar() throws Exception {
        assertEquals("jo", run("""
                k := "nm"
                m := {(k)= "jo" age= 5}
                println m.nm
                """));
    }

    @Test void dynamicKeyFromExpr() throws Exception {
        assertEquals("1", run("""
                m := {("a" ++ "b")= 1}
                println m.ab
                """));
    }

    @Test void dynamicKeyInRecordUpdate() throws Exception {
        assertEquals("2", run("""
                base := {a= 1}
                k := "b"
                m := {...base (k)= 2}
                println m.b
                """));
    }

    @Test void dynamicKeyRejectsNonString() {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> run("""
                        m := {(42)= 1}
                        println m
                        """));
        assertTrue(String.valueOf(ite.getCause().getMessage()).contains("map key"),
                "got: " + ite.getCause().getMessage());
    }
}
