package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each branch of a block-form {@code if} is a block, with its own scope,
 * as a match arm is. The emitter used to put a branch's bindings in the
 * enclosing scope, so {@code x := 50} in a branch rebound {@code x} for
 * the other branch and for the code after the {@code if}. When the
 * branch hadn't run, that slot was never written, and the JVM refused
 * the method: {@code VerifyError: Bad local variable type}.
 */
class IfBranchScopeTest {

    @Test void branchBindingEndsWithTheBranch() throws Exception {
        assertEquals("1\n1", run("""
                fn f :: Bool Int
                  => c
                  x := 1
                  if c
                    x := 50
                  x

                println (f false)
                println (f true)
                """));
    }

    @Test void elseBranchBindingEndsWithTheBranch() throws Exception {
        assertEquals("1\n1", run("""
                fn f :: Bool Int
                  => c
                  x := 1
                  if c
                    y := 2
                  else
                    x := 9
                  x

                println (f false)
                println (f true)
                """));
    }

    /** A tail `if`: the then-branch's binding must not reach the else. */
    @Test void thenBindingDoesNotReachTheElse() throws Exception {
        assertEquals("1\n50", run("""
                fn g :: Bool Int
                  => c
                  x := 1
                  if c
                    x := 50
                    x
                  else
                    x

                println (g false)
                println (g true)
                """));
    }

    /** The same for an inline `if` whose branch is a `(…; …)` block. */
    @Test void inlineBlockBranchDoesNotReachTheElse() throws Exception {
        assertEquals("1\n50", run("""
                fn g :: Bool Int
                  => c
                  x := 1
                  if c (x := 50; x) else x

                println (g false)
                println (g true)
                """));
    }

    @Test void branchReadsTheOuterBindingUntilItRebindsIt() throws Exception {
        assertEquals("11\n1", run("""
                fn f :: Int Int
                  => n
                  x := 1
                  if n > 0
                    x := x + 10
                    x
                  else
                    x

                println (f 5)
                println (f 0)
                """));
    }

    @Test void nestedBranchesEachHaveTheirOwnScope() throws Exception {
        assertEquals("1", run("""
                fn f :: Bool Int
                  => c
                  x := 1
                  if c
                    x := 2
                    if c
                      x := 3
                    else
                      x := 4
                  else
                    x := 5
                  x

                println (f true)
                """));
    }

    @Test void topLevelBranchBindingEndsWithTheBranch() throws Exception {
        assertEquals("50\n1", run("""
                x := 1
                if true
                  x := 50
                  println x
                println x
                """));
    }

    /** `<-` in a branch still writes the enclosing binding. */
    @Test void branchAssignmentWritesTheOuterBinding() throws Exception {
        assertEquals("5\n9", run("""
                fn f :: Bool Int
                  => c
                  x :! 1
                  if c
                    x <- 5
                  else
                    x <- 9
                  x

                println (f true)
                println (f false)
                """));
    }

    @Test void branchInsideAWithBodyHasItsOwnScope() throws Exception {
        assertEquals("50\n1", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 7

                with acc
                  x := 1
                  if true
                    x := 50
                    println x
                  println x
                """));
    }

    /** With an op in the body, `x` lives in the continuation (it must
     *  survive the perform); the branch's own `x` still ends with it. */
    @Test void branchInsideAnOpBearingWithBodyHasItsOwnScope() throws Exception {
        assertEquals("50\n8", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 7

                with acc
                  x := 1
                  t := tick ()
                  if true
                    x := 50
                    println x
                  println (x + t)
                """));
    }

    /** A branch that performs is lowered into the step's own scope,
     *  where lifted locals are keyed by name; its bindings get fresh
     *  names, so they still end with the branch. */
    @Test void bindingInABranchThatPerformsEndsWithTheBranch() throws Exception {
        assertEquals("7\n1\n8\n1", run(COUNTER + """
                with acc
                  x := 1
                  if true
                    x := tick ()
                    println x
                  println x
                  if true
                    x := x + tick ()
                    println x
                  println x
                """));
    }

    @Test void nestedBranchesThatPerformEachHaveTheirOwnScope() throws Exception {
        assertEquals("14\n7\n1", run(COUNTER + """
                with acc
                  x := 1
                  if true
                    x := tick ()
                    if true
                      x := x + tick ()
                      println x
                    println x
                  println x
                """));
    }

    @Test void assignmentsInsideAnOpBearingWithBodyPickTheRightBinding() throws Exception {
        assertEquals("51\n8\n12", run("""
                effect Counter
                  tick :: () -> Int

                handler acc :: Counter
                  tick () => resume 7

                with acc
                  x := 1
                  t := tick ()
                  if true
                    x :! 50
                    x <- x + 1
                    println x
                  println (x + t)

                with acc
                  u := tick ()
                  y :! 1
                  if true
                    y <- 5
                  println (y + u)
                """));
    }

    /** A lambda built in a top-level branch reads a top-level binding
     *  live, as one built outside the branch does. */
    @Test void lambdaInTopLevelBranchSeesLaterWrites() throws Exception {
        assertEquals("2", run("""
                hits :! 0

                fn bump :: () Int
                  => _
                  hits <- hits + 1
                  hits

                fn call-it :: Fn _
                  => f
                  f ()

                if true
                  f := (_ -> hits)
                  bump ()
                  bump ()
                  println (call-it f)
                """));
    }

    private static final String COUNTER = """
            effect Counter
              tick :: () -> Int

            handler acc :: Counter
              tick () => resume 7

            """;

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(IfBranchScopeTest.class.getClassLoader()); }
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
