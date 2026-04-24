package dev.irij.compiler;

import dev.irij.compiler.RuntimeSupport.CompiledHandler;
import dev.irij.compiler.RuntimeSupport.IrijContinuation;
import dev.irij.compiler.RuntimeSupport.IrijFn;
import dev.irij.compiler.RuntimeSupport.PerformSignal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14c.3 step 1: exercise the state-machine runtime surface
 * ({@link IrijContinuation}, {@link PerformSignal},
 * {@link RuntimeSupport#runWithSM}) using hand-written step closures. The
 * compiler doesn't emit continuations yet; these tests pin the runtime
 * contract that later steps will target.
 */
class StateMachineRuntimeTest {

    /**
     * Hand-written equivalent of lowering for:
     *   with logger
     *     log "hi"           ;; yields at state 0 -> 1
     *     sideEffect.++      ;; pure, after resume
     *     42                 ;; body result
     * Shape after step 2 lowering (state 0 throws PerformSignal; state 1
     * returns the body value). {@code fields[0]} holds the AtomicInteger
     * captured from the enclosing scope.
     */
    static IrijContinuation logThenReturn(AtomicInteger sideEffect) {
        IrijFn step = args -> {
            IrijContinuation k = (IrijContinuation) args[0];
            Object v = args[1]; // resume value (null on first entry)
            switch (k.state) {
                case 0:
                    k.state = 1;
                    throw PerformSignal.of("Log", "log", new Object[]{"hi"}, k);
                case 1:
                    ((AtomicInteger) k.fields[0]).incrementAndGet();
                    // swallow v; body value is 42
                    return (Long) 42L + ((v == null) ? 0L : 0L);
                default:
                    throw new IllegalStateException("bad state " + k.state);
            }
        };
        IrijContinuation k = new IrijContinuation(step, 1);
        k.fields[0] = sideEffect;
        return k;
    }

    private CompiledHandler logHandler(IrijFn clause) {
        return new CompiledHandler("logger", "Log", Map.of("log", clause));
    }

    @Test void resumes_body_and_returns_body_value() {
        AtomicInteger postResume = new AtomicInteger(0);
        IrijFn logClause = args -> {
            assertEquals("hi", args[0]);
            IrijFn resume = (IrijFn) args[1];
            return resume.apply(new Object[]{}); // tail-resume
        };
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), logThenReturn(postResume));
        assertEquals(42L, result);
        assertEquals(1, postResume.get(), "body must have continued after resume");
    }

    @Test void aborts_body_if_clause_does_not_resume() {
        AtomicInteger postResume = new AtomicInteger(0);
        IrijFn logClause = args -> "aborted";
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), logThenReturn(postResume));
        assertEquals("aborted", result);
        assertEquals(0, postResume.get(), "body must NOT have continued");
    }

    @Test void one_shot_resume_rejects_second_call() {
        IrijFn logClause = args -> {
            IrijFn resume = (IrijFn) args[1];
            resume.apply(new Object[]{});
            assertThrows(RuntimeException.class,
                    () -> resume.apply(new Object[]{}));
            return "done";
        };
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), logThenReturn(new AtomicInteger()));
        assertEquals("done", result);
    }

    @Test void multiple_sequential_performs_loop_through_handler() {
        // Body: log "a"; log "b"; log "c"; return "final"
        final java.util.List<String> seen = new java.util.ArrayList<>();
        IrijFn step = args -> {
            IrijContinuation k = (IrijContinuation) args[0];
            switch (k.state) {
                case 0: k.state = 1;
                    throw PerformSignal.of("Log", "log", new Object[]{"a"}, k);
                case 1: k.state = 2;
                    throw PerformSignal.of("Log", "log", new Object[]{"b"}, k);
                case 2: k.state = 3;
                    throw PerformSignal.of("Log", "log", new Object[]{"c"}, k);
                case 3: return "final";
            }
            throw new IllegalStateException();
        };
        IrijContinuation body = new IrijContinuation(step, 0);

        IrijFn logClause = args -> {
            seen.add((String) args[0]);
            IrijFn resume = (IrijFn) args[1];
            return resume.apply(new Object[]{});
        };
        Object result = RuntimeSupport.runWithSM(logHandler(logClause), body);
        assertEquals("final", result);
        assertEquals(java.util.List.of("a", "b", "c"), seen);
    }

    @Test void unhandled_effect_reraises_to_outer() {
        IrijFn step = args -> {
            IrijContinuation k = (IrijContinuation) args[0];
            throw PerformSignal.of("Other", "boom", new Object[]{}, k);
        };
        IrijContinuation body = new IrijContinuation(step, 0);
        PerformSignal raised = assertThrows(PerformSignal.class, () ->
                RuntimeSupport.runWithSM(logHandler(args -> null), body));
        assertEquals("Other", raised.effectName);
        assertEquals("boom",  raised.opName);
    }

    @Test void perform_signal_has_no_stack_trace() {
        PerformSignal s = PerformSignal.of("E", "op", new Object[]{}, null);
        assertEquals(0, s.getStackTrace().length,
                "stack-trace-free signal — hot-path perf");
    }
}
