package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Free-variable capture regressions (PR4 2026-07). The lambda
 * free-var collector silently skipped several Expr variants
 * (MapLit, RecordUpdate, Pipe, SeqOp, StringInterp, Range), so a
 * nested lambda whose body used an outer var inside one of those
 * positions failed at runtime with "Unbound variable".
 */
class LambdaCaptureTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(LambdaCaptureTest.class.getClassLoader()); }
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

    @Test void mapLiteralValueCapturesOuterVar() throws Exception {
        assertEquals("7", run("""
                mk := (v -> (_ -> {val= v}))
                println (((mk 7) 0).val)
                """));
    }

    @Test void recordUpdateCapturesOuterVarAndBase() throws Exception {
        assertEquals("5", run("""
                base := {a= 1}
                mk := (v -> (_ -> {...base b= v}))
                println (((mk 5) 0).b)
                """));
    }

    @Test void pipeAndSeqOpCaptureOuterVar() throws Exception {
        assertEquals("#[11 12 13]", run("""
                addn := (n -> (xs -> xs |> @ (x -> x + n)))
                println ((addn 10) #[1 2 3])
                """));
    }

    @Test void stringInterpCapturesOuterVar() throws Exception {
        assertEquals("hi jo", run("""
                s := (nm -> (_ -> "hi ${nm}"))
                println ((s "jo") 0)
                """));
    }

    @Test void rangeBoundsCaptureOuterVar() throws Exception {
        assertEquals("3", run("""
                mk := (n -> (_ -> 1..n))
                println (length ((mk 3) 0))
                """));
    }

    @Test void performInsidePipeWithinWithBody() throws Exception {
        assertEquals("2", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 1

                with acc
                  r := (tick ()) |> (x -> x + 1)
                  println r
                """));
    }

    @Test void performInsideMapValueWithinWithBody() throws Exception {
        assertEquals("1", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 1

                with acc
                  m := {v= tick ()}
                  println m.v
                """));
    }
}
