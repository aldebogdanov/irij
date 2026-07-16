package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code do} expression emission (PR7). Spec §1.3.2: `do` sequences
 * effects; the whole expression's value is the last sub-expression.
 * Previously the emitter rejected it: "MVP: unsupported expression:
 * DoExpr".
 */
class DoExprTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(DoExprTest.class.getClassLoader()); }
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

    @Test void doValueIsLastExpression() throws Exception {
        assertEquals("3", run("""
                println (do (1) (2) (3))
                """));
    }

    @Test void doSequencesEffectsInOrder() throws Exception {
        assertEquals("a\nb\nc", run("""
                do (println "a") (println "b") (println "c")
                """).replace("\r", ""));
    }

    @Test void doInBindPosition() throws Exception {
        assertEquals("done\n2", run("""
                x := do (println "done") (1 + 1)
                println x
                """).replace("\r", ""));
    }

    @Test void doInsideLambdaCapturesAndSequences() throws Exception {
        assertEquals("hi\n7", run("""
                f := (v -> do (println "hi") (v))
                println (f 7)
                """).replace("\r", ""));
    }

    @Test void doInTailPositionKeepsTco() throws Exception {
        // 200k self-calls through a do-tail: only O(1) stack if the
        // last do-expression is compiled as a tail call.
        assertEquals("0", run("""
                fn down :: Int Int
                  (n -> if (n == 0) 0 else (do (n) (down (n - 1))))

                println (down 200000)
                """));
    }

    @Test void doWithEffectOpInsideWith() throws Exception {
        assertEquals("2", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 1

                with acc
                  r := do (tick ()) ((tick ()) + 1)
                  println r
                """));
    }
}
