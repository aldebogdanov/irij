package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SM-lowering coverage matrix (PR7): effect ops embedded in Pipe,
 * Compose, SeqOp, MapLit (incl. dynamic keys), RecordUpdate,
 * StringInterp, Range, and DoExpr positions inside `with` bodies.
 *
 * These tests were written BEFORE extending
 * {@code SmClassifier.containsOpCallExpr} and pass either way: first
 * via the SM_STACK runtime fallback, then via native SM lowering once
 * the classifier + ANormalizer know the positions. The handler-state
 * order assertions pin left-to-right evaluation through
 * A-normalization lifting.
 */
class SmLoweringCoverageTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(SmLoweringCoverageTest.class.getClassLoader()); }
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

    /** Shared preamble: `tick s` logs s into handler state, resumes 1. */
    private static final String FX = """
            effect T
              tick :: Str -> Int

            handler logger :: T
              state :! #[]
              tick s =>
                state <- conj state s
                resume 1

            """;

    @Test void opInPipeLeft() throws Exception {
        assertEquals("2\n#[a]", run(FX + """
                r := with logger
                  x := (tick "a") |> (v -> v + 1)
                  x
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void twoOpsInMapValuesKeepOrder() throws Exception {
        assertEquals("1\n#[a b]", run(FX + """
                r := with logger
                  m := {x= (tick "a") y= (tick "b")}
                  m.x
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opInDynamicMapKey() throws Exception {
        assertEquals("2\n#[k]", run(FX + """
                r := with logger
                  m := {("v" ++ (to-str (tick "k")))= 2}
                  m.v1
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opInRecordUpdateValue() throws Exception {
        assertEquals("1\n#[c]", run(FX + """
                base := {a= 0}
                r := with logger
                  m := {...base v= (tick "c")}
                  m.v
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opsInStringInterpKeepOrder() throws Exception {
        assertEquals("v=1-1\n#[a b]", run(FX + """
                r := with logger
                  s := "v=${tick "a"}-${tick "b"}"
                  s
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opInRangeBound() throws Exception {
        assertEquals("1\n#[n]", run(FX + """
                r := with logger
                  rg := 1..(tick "n")
                  length rg
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opsInComposeOperandsKeepOrder() throws Exception {
        assertEquals("2\n#[a b]", run(FX + """
                inc := (n -> x -> x + n)
                r := with logger
                  f := (inc (tick "a")) >> (inc (tick "b"))
                  f 0
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opsInDoExprKeepOrder() throws Exception {
        assertEquals("2\n#[a b]", run(FX + """
                r := with logger
                  v := do (tick "a") ((tick "b") + 1)
                  v
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void mixedSequenceKeepsOrder() throws Exception {
        assertEquals("3\n#[1 2 3]", run(FX + """
                r := with logger
                  a := tick "1"
                  m := {k= (tick "2")}
                  b := tick "3"
                  a + m.k + b
                println r
                println logger.state
                """).replace("\r", ""));
    }

    @Test void opInPipeInsideBranch() throws Exception {
        assertEquals("2\n#[pre t]", run(FX + """
                r := with logger
                  x := tick "pre"
                  if x == 1
                    y := (tick "t") |> (v -> v + 1)
                    y
                  else
                    0
                println r
                println logger.state
                """).replace("\r", ""));
    }
}
