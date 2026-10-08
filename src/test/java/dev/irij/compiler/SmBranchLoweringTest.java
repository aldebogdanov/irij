package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A `with` body is lowered to a state machine, which performs ops only at
 * the top of the body or in `if` branches. Every other place an op runs
 * conditionally — a `match` arm, an `if` expression, the right of `&&` /
 * `||`, a value block — was refused ("body shape not supported"), and so
 * was any `match` statement at all once the body performed. `&&` / `||`
 * did compile, but performed their right operand unconditionally.
 * The A-normalizer now rewrites those places into `if` chains.
 */
class SmBranchLoweringTest {

    /** `tick` counts: it resumes 1, 2, 3, … */
    private static final String COUNTER = """
            effect Counter
              tick :: () -> Int

            handler counting :: Counter
              n :! 0
              tick () =>
                n <- n + 1
                resume n

            """;

    @Test void matchArmsPerform() throws Exception {
        assertEquals("11\n2", run(COUNTER + """
                with counting
                  match 2
                    1 => println (tick ())
                    2 => println ((tick ()) + 10)
                    _ => println 0
                  println (tick ())
                """));
    }

    @Test void matchExpressionArmsPerform() throws Exception {
        assertEquals("3\n3", run(COUNTER + """
                with counting
                  y := match "b"
                    "a" => tick ()
                    "b" => (tick ()) + (tick ())
                    _ => 0
                  println y
                  println (tick ())
                """));
    }

    @Test void patternVariablesAndGuardsReachTheArm() throws Exception {
        assertEquals("8\n0", run(COUNTER + """
                with counting
                  match #[3 4]
                    #[a b] | a > 5 => println "big"
                    #[a b] => println (a + b + (tick ()))
                  println 0
                """));
    }

    @Test void tailMatchIsTheWithsValue() throws Exception {
        assertEquals("one2", run(COUNTER + """
                r := with counting
                  t := tick ()
                  match t
                    1 => "one" ++ (to-str (tick ()))
                    _ => "other"
                println r
                """));
    }

    @Test void pureMatchAfterAnOp() throws Exception {
        assertEquals("one\n1", run(COUNTER + """
                with counting
                  t := tick ()
                  match t
                    1 => println "one"
                    _ => println "other"
                  println t
                """));
    }

    @Test void armBindingEndsWithTheArm() throws Exception {
        assertEquals("1\n100", run(COUNTER + """
                with counting
                  x := 100
                  match 1
                    1 =>
                      x := tick ()
                      println x
                    _ => println 0
                  println x
                """));
    }

    @Test void nestedMatchesPerform() throws Exception {
        assertEquals("20", run(COUNTER + """
                with counting
                  match 1
                    1 =>
                      match (tick ())
                        1 => println ((tick ()) * 10)
                        _ => println 0
                    _ => println 0
                """));
    }

    @Test void ifExpressionBranchesPerform() throws Exception {
        assertEquals("#[1 100 2]", run(COUNTER + """
                with counting
                  y := if true (tick ()) else 0
                  z := if false
                    tick ()
                  else
                    100
                  println #[y z (tick ())]
                """));
    }

    @Test void andOrPerformTheirRightOnlyWhenItDecides() throws Exception {
        assertEquals("false\ntrue\ntrue\n2", run(COUNTER + """
                with counting
                  println (false && ((tick ()) > 0))
                  println (true || ((tick ()) > 0))
                  println (true && ((tick ()) > 0))
                  println (tick ())
                """));
    }

    @Test void lambdaThatPerformsIsBuiltInAWithBody() throws Exception {
        assertEquals("1\n2", run(COUNTER + """
                with counting
                  f := (_ -> tick ())
                  println (f ())
                  println (f ())
                """));
    }

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(SmBranchLoweringTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }
}
