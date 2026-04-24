package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 14c.3 step 2: end-to-end compilation of `with` under STATE_MACHINE
 * strategy. Covers pure bodies (no op call) and single-op bodies (exactly
 * one top-level op call, with or without `x := op args` binding).
 */
class StateMachineWithTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(StateMachineWithTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String runSM(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(
                source, "irij.Program", null, CompileOptions.stateMachine());
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
        return buf.toString();
    }

    private static String nl(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append(System.lineSeparator());
        return sb.toString();
    }

    @Test void pure_body_no_ops() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler noop :: Log
              log msg => resume ()
            with noop
              println "inside"
            println "after"
            """;
        assertEquals(nl("inside", "after"), runSM(src));
    }

    @Test void single_op_call_resumed() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              log "hi"
            println "done"
            """;
        assertEquals(nl("got: hi", "done"), runSM(src));
    }

    @Test void single_op_with_pre_and_post_stmts() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              println "before"
              log "middle"
              println "after"
            println "done"
            """;
        assertEquals(nl("before", "got: middle", "after", "done"), runSM(src));
    }

    @Test void single_op_binding_receives_resume_value() throws Exception {
        String src = """
            effect Ask
              ask :: () -> Int
            handler answer :: Ask
              ask => resume 42
            with answer
              x := ask ()
              println x
            """;
        assertEquals(nl("42"), runSM(src));
    }

    // ── Step 3a: multi-op sequences + local lifting ──────────────────

    @Test void two_ops_in_sequence() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              log "a"
              log "b"
            println "done"
            """;
        assertEquals(nl("got: a", "got: b", "done"), runSM(src));
    }

    @Test void three_ops_interleaved_with_prints() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              println "start"
              log "a"
              println "mid1"
              log "b"
              println "mid2"
              log "c"
              println "end"
            """;
        assertEquals(nl("start", "got: a", "mid1", "got: b", "mid2", "got: c", "end"),
                runSM(src));
    }

    @Test void pre_op_local_used_after_perform() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              greeting := "hello"
              log "middle"
              println greeting
            """;
        assertEquals(nl("got: middle", "hello"), runSM(src));
    }

    @Test void resume_value_used_in_later_op() throws Exception {
        // Single-handler case with two ops from same effect; first op's
        // resume-bind is consumed after the second perform returns.
        String src = """
            effect Chat
              hello :: () -> Str
              say   :: Str -> ()
            handler chat :: Chat
              hello => resume "world"
              say msg =>
                println ("say: " ++ msg)
                resume ()
            with chat
              who := hello ()
              say "before-who"
              println who
            """;
        assertEquals(nl("say: before-who", "world"), runSM(src));
    }

    // ── Step 3b: branching with ops ──────────────────────────────────

    @Test void if_then_perform_taken() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              if true
                log "yes"
              println "after"
            """;
        assertEquals(nl("got: yes", "after"), runSM(src));
    }

    @Test void if_then_perform_not_taken() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              if false
                log "skipped"
              println "after"
            """;
        assertEquals(nl("after"), runSM(src));
    }

    @Test void if_both_branches_perform() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              if true
                log "a"
              else
                log "b"
              println "merge"
            """;
        assertEquals(nl("got: a", "merge"), runSM(src));
    }

    @Test void if_else_branch_performs() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              if false
                log "a"
              else
                log "b"
              println "merge"
            """;
        assertEquals(nl("got: b", "merge"), runSM(src));
    }

    @Test void op_before_and_inside_if() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              log "pre"
              if true
                log "mid"
              log "post"
            """;
        assertEquals(nl("got: pre", "got: mid", "got: post"), runSM(src));
    }

    @Test void nested_if_with_ops() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              if true
                if false
                  log "x"
                else
                  log "y"
              println "done"
            """;
        assertEquals(nl("got: y", "done"), runSM(src));
    }

    // ── Step 3c: A-normalization of nested op calls ─────────────────

    @Test void op_nested_in_binary_op() throws Exception {
        String src = """
            effect Ask
              ask :: () -> Str
            handler ans :: Ask
              ask => resume "world"
            with ans
              println ("hello, " ++ ask ())
            """;
        assertEquals(nl("hello, world"), runSM(src));
    }

    @Test void op_nested_in_function_arg() throws Exception {
        String src = """
            effect Ask
              ask :: () -> Int
            handler ans :: Ask
              ask => resume 41
            with ans
              println (ask () + 1)
            """;
        assertEquals(nl("42"), runSM(src));
    }

    @Test void two_nested_ops_in_one_expression() throws Exception {
        String src = """
            effect Chat
              hello :: () -> Str
              world :: () -> Str
            handler chat :: Chat
              hello => resume "hi"
              world => resume "earth"
            with chat
              println (hello () ++ " " ++ world ())
            """;
        assertEquals(nl("hi earth"), runSM(src));
    }

    @Test void op_result_used_in_if_cond() throws Exception {
        String src = """
            effect Ask
              pick :: () -> Int
            handler ans :: Ask
              pick => resume 1
            with ans
              if pick () == 1
                println "one"
              else
                println "other"
            """;
        assertEquals(nl("one"), runSM(src));
    }

    @Test void op_nested_in_bind_rhs() throws Exception {
        String src = """
            effect Ask
              ask :: () -> Int
            handler ans :: Ask
              ask => resume 10
            with ans
              x := ask () + ask ()
              println x
            """;
        assertEquals(nl("20"), runSM(src));
    }

    // ── Step 4: on-failure ───────────────────────────────────────────

    @Test void on_failure_catches_body_error() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            fn run
              _ =>
                with echo
                  log "before"
                  error "kaboom!"
                  "never"
                on-failure
                  "recovered: " ++ error
            println (run ())
            """;
        assertEquals(nl("got: before", "recovered: kaboom!"), runSM(src));
    }

    @Test void on_failure_not_triggered_on_success() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            fn run
              _ =>
                with echo
                  log "hello"
                  "ok"
                on-failure
                  "recovered: " ++ error
            println (run ())
            """;
        assertEquals(nl("got: hello", "ok"), runSM(src));
    }

    @Test void on_failure_catches_error_from_clause() throws Exception {
        String src = """
            effect Boom
              explode :: () -> ()
            handler bomb :: Boom
              explode () => error "kaboom!"
            fn run
              _ =>
                with bomb
                  explode ()
                  "never reached"
                on-failure
                  "recovered: " ++ error
            println (run ())
            """;
        assertEquals(nl("recovered: kaboom!"), runSM(src));
    }

    // ── Step 5: handler state + dot-access ───────────────────────────

    @Test void handler_state_accumulates_across_ops() throws Exception {
        String src = """
            effect Counter
              bump :: () -> ()
              get-count :: () -> Int
            handler accumulator :: Counter
              state :! 0
              bump () =>
                state <- state + 1
                resume ()
              get-count () => resume state
            fn run
              _ =>
                with accumulator
                  bump ()
                  bump ()
                  bump ()
                accumulator.state
            println (to-str (run ()))
            """;
        assertEquals(nl("3"), runSM(src));
    }

    @Test void handler_state_read_via_op_and_dot_access() throws Exception {
        String src = """
            effect Counter
              bump :: () -> ()
              get-count :: () -> Int
            handler accumulator :: Counter
              state :! 0
              bump () =>
                state <- state + 1
                resume ()
              get-count () => resume state
            fn run
              _ =>
                with accumulator
                  bump ()
                  bump ()
                  c := get-count ()
                  println ("via op: " ++ to-str c)
                println ("via dot: " ++ to-str accumulator.state)
            run ()
            """;
        assertEquals(nl("via op: 2", "via dot: 2"), runSM(src));
    }

    @Test void abort_path_returns_clause_value() throws Exception {
        String src = """
            effect Fail
              fail :: Str -> ()
            handler aborts :: Fail
              fail msg =>
                println ("aborted: " ++ msg)
                "gave-up"
            r := with aborts
              fail "boom"
              "unreachable"
            println r
            """;
        assertEquals(nl("aborted: boom", "gave-up"), runSM(src));
    }
}
