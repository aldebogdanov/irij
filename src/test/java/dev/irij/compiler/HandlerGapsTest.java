package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler semantics the state-machine lowering used to get wrong or
 * refuse: code after `resume` (now a compile error — `resume` is a
 * clause's last step), a clause that doesn't resume for an op performed
 * in a called fn, and `scope` / `:!` in a `with` body that performs.
 */
class HandlerGapsTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(HandlerGapsTest.class.getClassLoader()); }
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
        return buf.toString().trim().replace("\r", "");
    }

    private static void rejected(String source, String fragment) {
        var e = assertThrows(IrijCompiler.CompileException.class, () -> run(source));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    private static final String ASK = """
            effect Ask
              ask :: Str -> Str
            """;

    // ── `resume` is a clause's last step ────────────────────────────────

    @Test
    void codeBeforeResumeRunsThenTheBodyContinues() throws Exception {
        // Same order whether the op is performed in the body or in a fn
        // it calls.
        String noisy = """
                effect Log
                  log :: Str -> ()
                handler noisy :: Log ::: Console
                  log msg =>
                    println ("got " ++ msg)
                    resume ()
                fn work ::: Log Console
                  =>
                  log "a"
                  println "between"
                  log "b"
                  "result"
                """;
        assertEquals("got a\nbetween\ngot b\nresult",
                run(noisy + "println (with noisy do (work ()))"));
        assertEquals("got a\nbetween\ngot b\nresult", run(noisy + """
                r := with noisy
                  log "a"
                  println "between"
                  log "b"
                  "result"
                println r
                """));
    }

    @Test
    void resumeAtTheEndOfABranch() throws Exception {
        assertEquals("ADA!\nada\n?", run(ASK + """
                handler shout :: Ask
                  ask q =>
                    if (q == "loud")
                      v := "ADA!"
                      resume v
                    else
                      match q
                        "quiet" => resume "ada"
                        _ => resume "?"
                println (with shout do (ask "loud"))
                println (with shout do (ask "quiet"))
                println (with shout do (ask "other"))
                """));
    }

    @Test
    void tailResumeStillRunsInConstantStack() throws Exception {
        assertEquals("200001", run("""
                effect Tick
                  tick :: () -> Int
                handler counter :: Tick
                  n :! 0
                  tick () =>
                    n <- n + 1
                    resume n
                fn spin ::: Tick
                  => k
                  if (k == 0) (tick ()) else (do (tick ()) (spin (k - 1)))
                println (with counter do (spin 200000))
                """));
    }

    @Test
    void codeAfterResumeIsACompileError() {
        rejected(ASK + """
                handler late :: Ask ::: Console
                  ask q =>
                    resume "Ada"
                    println "never"
                """, "code after `resume` never runs");
    }

    @Test
    void codeAfterABranchThatResumesIsACompileError() {
        rejected(ASK + """
                handler late :: Ask ::: Console
                  ask q =>
                    if (q == "x")
                      resume "Ada"
                    println "after"
                    resume "Bob"
                """, "code after `resume` never runs");
    }

    @Test
    void bindingResumeIsACompileError() {
        rejected(ASK + """
                handler wrap :: Ask
                  ask q =>
                    r := resume "Ada"
                    "[" ++ r ++ "]"
                """, "can't be bound");
    }

    @Test
    void resumeInsideAnExpressionIsACompileError() {
        rejected(ASK + """
                handler bad :: Ask
                  ask q =>
                    "[" ++ resume "Ada"
                """, "not part of an expression");
        rejected(ASK + """
                handler bad :: Ask
                  ask q =>
                    if (resume "Ada" == "x") 1 else 2
                """, "not part of an expression");
    }

    // ── A clause that doesn't resume, for an op performed in a called fn ─

    private static final String ABORT = """
            effect Abort
              abort :: Str -> ()
            handler to-err :: Abort
              abort msg => Err msg
            fn checked ::: Abort
              => n
              if (n < 0)
                abort "negative"
              Ok (n * 10)
            fn deep ::: Abort
              => n
              checked n
            """;

    @Test
    void abortFromACalledFn() throws Exception {
        assertEquals("Ok 50\nErr \"negative\"\nErr \"negative\"", run(ABORT + """
                println (with to-err do (checked 5))
                println (with to-err do (checked (-5)))
                println (with to-err do (deep (-1)))
                """));
    }

    @Test
    void abortPassesThroughTryAndOnFailure() throws Exception {
        assertEquals("Err \"negative\"\nErr \"negative\"", run(ABORT + """
                fn via-try
                  => n
                  with to-err
                    r := try (-> deep n)
                    Ok r
                fn via-on-failure
                  => n
                  with to-err
                    deep n
                  on-failure
                    "failed"
                println (via-try (-1))
                println (via-on-failure (-1))
                """));
    }

    @Test
    void abortFromAForkEndsTheParentsWith() throws Exception {
        assertEquals("Err \"in fork\"", run("""
                effect Abort
                  abort :: Str -> ()
                handler to-err :: Abort
                  abort msg => Err msg
                fn f
                  =>
                  with to-err
                    scope s
                      x := s.fork (-> abort "in fork")
                      await x
                    "after"
                println (f ())
                """));
    }

    // ── scope and :! in a with body that performs ──────────────────────

    @Test
    void scopeInAPerformingWithBody() throws Exception {
        // `await f` waits for the fiber, so its log lands between the two.
        assertEquals("42\n#[before fiber after]", run("""
                effect Log
                  log :: Str -> ()
                handler rec :: Log
                  lines :! #[]
                  log m =>
                    lines <- conj lines m
                    resume ()
                v := with rec
                  log "before"
                  n :! 0
                  scope s
                    f := s.fork (-> log "fiber"; 21)
                    n <- await f
                  log "after"
                  n * 2
                println v
                println rec.lines
                """));
    }

    @Test
    void scopeBesideAPerformingBranch() throws Exception {
        assertEquals("done\n#[on slept]", run("""
                effect Log
                  log :: Str -> ()
                handler rec :: Log
                  lines :! #[]
                  log m =>
                    lines <- conj lines m
                    resume ()
                fn job ::: Log Time
                  => flag
                  with rec
                    if flag
                      log "on"
                    scope s
                      s.fork (-> sleep 5; log "slept")
                    "done"
                println (job true)
                println rec.lines
                """));
    }

    @Test
    void mutableBindingAcrossPerforms() throws Exception {
        assertEquals("42", run("""
                effect Log
                  log :: Str -> ()
                handler quiet :: Log
                  log _ => resume ()
                v := with quiet
                  log "before"
                  n :! 0
                  n <- 21
                  log "after"
                  n * 2
                println v
                """));
    }
}
