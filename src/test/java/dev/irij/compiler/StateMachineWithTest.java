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
                source, "irij.Program", null, CompileOptions.defaults());
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

    @Test void with_body_tail_pure_if_returns_value() throws Exception {
        // Regression: a `with` block whose body performs an op and
        // then ends in a pure if/else used to drop the if's value.
        // Caller saw Unit. The EffIR lowering now reconstructs the
        // tail-position if so the with-block's return is the branch's
        // tail expression.
        String src = """
            effect E
              op :: Int
            handler h :: E
              op => resume 42
            fn pick :: Int ::: E
              =>
              with h
                x := op ()
                if (x == 42)
                  100
                else
                  200
            with h
              println (to-str (pick ()))
            """;
        assertEquals(nl("100"), runSM(src));
    }

    @Test void pure_body_no_ops() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler noop :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler answer :: Ask ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler chat :: Chat ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler echo :: Log ::: Console
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
            handler ans :: Ask ::: Console
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
            handler ans :: Ask ::: Console
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
            handler chat :: Chat ::: Console
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
            handler ans :: Ask ::: Console
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
            handler ans :: Ask ::: Console
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
            handler echo :: Log ::: Console
              log msg =>
                println ("got: " ++ msg)
                resume ()
            fn run ::: Console
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
            handler echo :: Log ::: Console
              log msg =>
                println ("got: " ++ msg)
                resume ()
            fn run ::: Console
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
            handler bomb :: Boom ::: Console
              explode () => error "kaboom!"
            fn run ::: Console
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
            handler accumulator :: Counter ::: Console
              state :! 0
              bump () =>
                state <- state + 1
                resume ()
              get-count () => resume state
            fn run ::: Console
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
            handler accumulator :: Counter ::: Console
              state :! 0
              bump () =>
                state <- state + 1
                resume ()
              get-count () => resume state
            fn run ::: Console
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

    // ── Step 6: handler composition (>>) ────────────────────────────

    @Test void composed_two_effects_each_op_once() throws Exception {
        String src = """
            effect Greet
              greet :: Str -> Str
            effect Logger
              log :: Str -> ()
            handler friendly :: Greet ::: Console
              greet name => resume ("Hi, " ++ name)
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            fn run ::: Console
              _ =>
                with quiet-log >> friendly
                  log "ignored"
                  greet "World"
            println (run ())
            """;
        assertEquals(nl("Hi, World"), runSM(src));
    }

    @Test void composed_interleaved_ops_across_handlers() throws Exception {
        // Cross-handler scenario: op-for-inner, resume, op-for-outer, resume,
        // op-for-inner again. Exercises that outer's resume re-enters the
        // body with the inner handler still in scope.
        String src = """
            effect Greet
              greet :: Str -> Str
            effect Logger
              log :: Str -> ()
            handler friendly :: Greet ::: Console
              greet name => resume ("hi-" ++ name)
            handler echo-log :: Logger ::: Console
              log msg =>
                println ("log: " ++ msg)
                resume ()
            fn run ::: Console
              _ =>
                with echo-log >> friendly
                  a := greet "A"
                  log "between"
                  b := greet "B"
                  println (a ++ " / " ++ b)
            run ()
            """;
        assertEquals(nl("log: between", "hi-A / hi-B"), runSM(src));
    }

    @Test void composed_local_binding() throws Exception {
        String src = """
            effect Greet
              greet :: Str -> Str
            effect Logger
              log :: Str -> ()
            handler friendly :: Greet ::: Console
              greet name => resume ("Hi, " ++ name)
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            fn run ::: Console
              _ =>
                combined := quiet-log >> friendly
                with combined
                  log "ignored"
                  greet "World"
            println (run ())
            """;
        assertEquals(nl("Hi, World"), runSM(src));
    }

    // ── Step 7: tier (c) — clauses that themselves perform effects ───
    // Native: clause body compiled as its own SM step. Foreign performs
    // throw PerformSignal that escapes to the next-outer SM frame via
    // SM_STACK fallback. The threaded path is now only used for handlers
    // whose body shape SM can't lower (none in current tests).

    @Test void tier_c_clause_with_required_effects_annotation() throws Exception {
        // Handler declares `::: Logger` and clause body performs log.
        // Native tier-c emits clause body as an SM step; the foreign
        // perform escapes the empty-hs clause loop and is dispatched
        // by the outer composed handler chain via SM_STACK fallback.
        String src = """
            effect Logger
              log :: Str -> ()
            effect Counter
              bump :: () -> Int
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            handler loud-counter :: Counter ::: Logger
              state :! 0
              bump () =>
                state <- state + 1
                log "bumped"
                resume state
            fn run ::: Console
              _ =>
                with quiet-log >> loud-counter
                  bump ()
                  bump ()
                  bump ()
            println (to-str (run ()))
            """;
        assertEquals(nl("3"), runSM(src));
    }

    @Test void tier_c_clause_with_explicit_foreign_effect_annotation() throws Exception {
        // Handler whose clause performs a foreign effect MUST declare
        // that effect via `:::` — the compile-time effect-row checker
        // rejects unannotated tier-c clauses. This test pins the
        // annotated form (the previous "implicit" variant now fails at
        // compile time, by design — see tier_c_clause_without_annotation_
        // rejected for the negative case).
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            handler chatty :: Greet ::: Logger
              greet name =>
                log ("greeting " ++ name)
                resume ("Hi, " ++ name)
            fn run ::: Console
              _ =>
                with quiet-log >> chatty
                  greet "World"
            println (run ())
            """;
        assertEquals(nl("Hi, World"), runSM(src));
    }

    @Test void tier_c_clause_without_annotation_rejected() throws Exception {
        // Negative test: clause body performs Logger.log without the
        // handler declaring ::: Logger. The effect-row checker rejects
        // this at compile time.
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            handler chatty :: Greet ::: Console
              greet name =>
                log ("greeting " ++ name)
                resume ("Hi, " ++ name)
            fn run ::: Console
              _ =>
                with quiet-log >> chatty
                  greet "World"
            println (run ())
            """;
        org.junit.jupiter.api.Assertions.assertThrows(
                IrijCompiler.CompileException.class, () -> runSM(src));
    }

    // ── Step 8: nested `with` ────────────────────────────────────────
    // Outer `with h2 (with h1 body)`. Inner SM frame catches its own effect;
    // anything else propagates out and is caught by the outer SM frame.

    @Test void handler_as_fn_param_dispatches_through_with() throws Exception {
        // v0.8.0b — opaque-handler emit path. A fn parameter typed
        // (Handler Logger) is used as the head of a `with` block;
        // the emitter detects the fn-param as opaque, evaluates the
        // value at runtime, pushes its effect via
        // RT.enterWithFromValue, and dispatches through runWithSM.
        String src = """
            effect Logger
              log :: Str ()
            handler quiet :: Logger
              log _ => resume ()
            handler tag :: Logger
              log msg => resume ()
            fn run-it :: (Handler Logger) () ::: Logger
              => h
              with h
                log "a"
                log "b"
            fn driver ::: Logger
              =>
              run-it quiet
              run-it tag
              "ok"
            with quiet
              println (driver ())
            """;
        assertEquals(nl("ok"), runSM(src));
    }

    @Test void composed_fn_param_handlers_dispatch_correctly() throws Exception {
        // v0.8.0c — runtime handler composition. Two handler values
        // arrive as fn parameters; `>>` composes them at runtime
        // (RuntimeSupport.compose is a normal Object→Object op) and
        // the resulting CompiledComposedHandler flows through the
        // opaque-handler emit path. Both handlers' effects are
        // pushed to EFFECT_ROW via enterWithFromValue.
        String src = """
            effect Logger
              log :: Str ()
            effect Greet
              greet :: Str Str
            handler quiet :: Logger
              log _ => resume ()
            handler hi :: Greet
              greet n => resume ("Hi, " ++ n)
            fn use-pair :: (Handler Logger) (Handler Greet) Str ::: Logger Greet
              => log-h greet-h
              with log-h >> greet-h
                log "preamble"
                greet "Alice"
            println (use-pair quiet hi)
            """;
        assertEquals(nl("Hi, Alice"), runSM(src));
    }

    @Test void nested_with_captures_fn_param_across_inner_step() throws Exception {
        // Regression: prior to the fix in collectFreeVarsStmt, `Stmt.With`
        // fell through the default arm so an inner `with` block's body
        // never propagated its free vars up to the outer SM step's
        // capture list. A fn-param referenced from inside two nested
        // `with` frames showed up as "Unbound variable" at runtime.
        // Verifies fn-param `name` reaches the inner clause body
        // through two SM frames.
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            handler friendly :: Greet ::: Console
              greet n => resume ("Hi, " ++ n)
            fn run :: Str Str ::: Console
              => name
              with quiet-log
                with friendly
                  log "before"
                  greet name
            println (run "Ana")
            """;
        assertEquals(nl("Hi, Ana"), runSM(src));
    }

    @Test void nested_with_inner_handles_inner_effect() throws Exception {
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            handler friendly :: Greet ::: Console
              greet name => resume ("Hi, " ++ name)
            fn run ::: Console
              _ =>
                with quiet-log
                  with friendly
                    log "ignored"
                    greet "World"
            println (run ())
            """;
        assertEquals(nl("Hi, World"), runSM(src));
    }

    @Test void nested_with_outer_handles_after_inner_resume() throws Exception {
        // body: greet (inner), log (outer), greet (inner) — exercises that
        // re-throw from inner SM frame escapes to outer SM frame and that
        // outer's resume re-enters body which still has inner frame above.
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler echo-log :: Logger ::: Console
              log msg =>
                println ("log: " ++ msg)
                resume ()
            handler friendly :: Greet ::: Console
              greet name => resume ("hi-" ++ name)
            fn run ::: Console
              _ =>
                with echo-log
                  with friendly
                    a := greet "A"
                    log "between"
                    b := greet "B"
                    println (a ++ " / " ++ b)
            run ()
            """;
        assertEquals(nl("log: between", "hi-A / hi-B"), runSM(src));
    }

    // ── Step 9: concurrency parity ───────────────────────────────────
    // Forked fibers must see handlers active at fork time. SM mode doesn't
    // have a thread-local handler stack the way the interpreter does; for
    // safety, `with` containing `spawn` falls back to threaded mode where
    // EffectSystem.STACK snapshot machinery is in place.

    @Test void spawn_inside_with_runs_under_handler() throws Exception {
        // Native concurrency parity: spawn snapshots both EffectSystem.STACK
        // and SM_STACK at fork time; the fiber inherits both. The fiber's
        // perform goes through fireOp which falls through to fireOpToSM
        // when no threaded handler matches, dispatching synchronously
        // against the inherited SM frame.
        String src = """
            effect Logger
              log :: Str -> ()
            handler echo-log :: Logger ::: Console
              log msg =>
                println ("log: " ++ msg)
                resume ()
            fn fiber-body ::: Logger
              _ =>
                log "from-fiber"
            with echo-log
              t := spawn (-> fiber-body ())
              sleep 50
              log "from-main"
            """;
        assertEquals(nl("log: from-fiber", "log: from-main"), runSM(src));
    }

    // ── Trampoline: deep perform-loops don't blow the JVM stack ──────

    @Test void deep_perform_loop_no_stackoverflow() throws Exception {
        // 1000 sequential top-level performs — would have blown the stack
        // before the trampoline (recursive resumeFn → k.resume → throw →
        // dispatchSM grew ~3 JVM frames per iteration). Capped below the
        // JVM 64KB method-size limit (each state adds ~30 bytes).
        int n = 1000;
        StringBuilder src = new StringBuilder();
        src.append("effect Counter\n");
        src.append("  bump :: () -> ()\n");
        src.append("  get-count :: () -> Int\n");
        src.append("handler accumulator :: Counter\n");
        src.append("  state :! 0\n");
        src.append("  bump () =>\n");
        src.append("    state <- state + 1\n");
        src.append("    resume ()\n");
        src.append("  get-count () => resume state\n");
        src.append("fn run\n");
        src.append("  _ =>\n");
        src.append("    with accumulator\n");
        for (int i = 0; i < n; i++) src.append("      bump ()\n");
        src.append("      get-count ()\n");
        src.append("println (to-str (run ()))\n");
        assertEquals(nl(String.valueOf(n)), runSM(src.toString()));
    }

    // ── Native tier-c — clause body as its own SM continuation ──────

    @Test void native_tier_c_clause_resume_value_flows_through() throws Exception {
        // Inner handler's clause body performs an outer effect, then
        // resumes with a derived value. The body's `:= bump` bind must
        // receive the post-foreign-perform value.
        String src = """
            effect Counter
              bump :: () -> Int
            effect Logger
              log :: Str -> ()
            handler echo-log :: Logger ::: Console
              log msg =>
                println ("log: " ++ msg)
                resume ()
            handler counted :: Counter ::: Logger
              state :! 0
              bump () =>
                state <- state + 10
                log "bumped"
                resume state
            fn run :: _ _ ::: Console
              _ =>
                with echo-log
                  with counted
                    a := bump ()
                    b := bump ()
                    println (to-str a ++ "/" ++ to-str b)
            run ()
            """;
        assertEquals(nl("log: bumped", "log: bumped", "10/20"), runSM(src));
    }

    // ── Native nested SM — `r := with X body` binds inner result ────

    @Test void nested_inner_with_as_bind_rhs() throws Exception {
        // The inner with appears as a Bind RHS (Block-wrapped). Native
        // nested SM extracts it as a segment, runs runWithSM, stores
        // the result both in vSlot AND in k.fields[bindIdx_of_r] so
        // subsequent segments can read it.
        String src = """
            effect Greet
              greet :: Str -> Str
            handler friendly :: Greet ::: Console
              greet name => resume ("Hi, " ++ name)
            effect Logger
              log :: Str -> ()
            handler quiet-log :: Logger ::: Console
              log msg => resume ()
            fn run ::: Console
              _ =>
                with quiet-log
                  r := with friendly
                    greet "World"
                  log "after"
                  println r
            run ()
            """;
        assertEquals(nl("Hi, World"), runSM(src));
    }

    // ── Native nested SM — on-failure inside inner with ──────────────

    @Test void nested_inner_with_on_failure_native() throws Exception {
        // Inner with has on-failure; outer is also SM. Body in inner
        // with throws via `error` — caught by inner's on-failure block.
        // Native nested-SM emit wraps the inner runWithSM call in a
        // try/catch that re-throws PerformSignal/TailResume so SM
        // control flow keeps working past the on-failure layer.
        StringBuilder src = new StringBuilder();
        src.append("effect Logger\n");
        src.append("  log :: Str -> ()\n");
        src.append("handler quiet-log :: Logger\n");
        src.append("  log msg => resume ()\n");
        src.append("fn run ::: Console\n");
        src.append("  _ =>\n");
        src.append("    with quiet-log\n");
        src.append("      with quiet-log\n");
        src.append("        log \"before\"\n");
        src.append("        error \"boom\"\n");
        src.append("        \"never\"\n");
        src.append("      on-failure\n");
        src.append("        println (\"recovered: \" ++ error)\n");
        src.append("run ()\n");
        assertEquals(nl("recovered: boom"), runSM(src.toString()));
    }

    // ── Native nested SM — outer resume threads value into saved kInner ─

    @Test void nested_sm_outer_resume_into_kInner_preserves_state() throws Exception {
        // Cross-handler choreography under DUAL SM (no threaded fallback):
        //   greet (inner, friendly)   -> resume "hi-A"
        //   log "between" (outer, echo-log) -> escapes inner trampoline,
        //                                       caught by outer; outer resume
        //                                       must thread value INTO the
        //                                       saved inner continuation
        //   greet (inner) -> resume "hi-B"
        // Verifies kInner persists across outer-resume cycle and re-entry
        // resumes inner body where it left off.
        String src = """
            effect Logger
              log :: Str -> ()
            effect Greet
              greet :: Str -> Str
            handler echo-log :: Logger ::: Console
              log msg =>
                println ("log: " ++ msg)
                resume ()
            handler friendly :: Greet ::: Console
              greet name => resume ("hi-" ++ name)
            fn run ::: Console
              _ =>
                with echo-log
                  with friendly
                    a := greet "A"
                    log "between"
                    b := greet "B"
                    println (a ++ " / " ++ b)
            run ()
            """;
        assertEquals(nl("log: between", "hi-A / hi-B"), runSM(src));
    }

    @Test void abort_path_returns_clause_value() throws Exception {
        String src = """
            effect Fail
              fail :: Str -> ()
            handler aborts :: Fail ::: Console
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
