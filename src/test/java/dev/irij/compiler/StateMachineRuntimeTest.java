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
 * {@link RuntimeSupport#runWithSM}) using hand-written continuation
 * subclasses. The compiler doesn't emit continuations yet; these tests
 * pin the runtime contract that later steps will target.
 */
class StateMachineRuntimeTest {

    /**
     * Simulates:
     *   with logger
     *     log "hi"           ;; yields at state 0 -> 1
     *     println "done"     ;; pure, after resume
     *     42                 ;; body result
     */
    static final class LogThenReturn extends IrijContinuation {
        final AtomicInteger sideEffect;
        LogThenReturn(AtomicInteger s) { this.sideEffect = s; }

        @Override public Object resume(Object value) {
            switch (state) {
                case 0:
                    state = 1;
                    throw PerformSignal.of("Log", "log", new Object[]{"hi"}, this);
                case 1:
                    sideEffect.incrementAndGet();
                    return 42L;
                default:
                    throw new IllegalStateException("bad state " + state);
            }
        }
    }

    private CompiledHandler logHandler(IrijFn clause) {
        return new CompiledHandler("logger", "Log", Map.of("log", clause));
    }

    @Test void resumes_body_and_returns_body_value() {
        AtomicInteger postResume = new AtomicInteger(0);
        IrijFn logClause = args -> {
            // args = [msg, resumeFn]
            assertEquals("hi", args[0]);
            IrijFn resume = (IrijFn) args[1];
            return resume.apply(new Object[]{}); // resume () — tail-resume
        };
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), new LogThenReturn(postResume));
        assertEquals(42L, result);
        assertEquals(1, postResume.get(), "body must have continued after resume");
    }

    @Test void aborts_body_if_clause_does_not_resume() {
        AtomicInteger postResume = new AtomicInteger(0);
        IrijFn logClause = args -> "aborted"; // no resume()
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), new LogThenReturn(postResume));
        assertEquals("aborted", result);
        assertEquals(0, postResume.get(), "body must NOT have continued");
    }

    @Test void one_shot_resume_rejects_second_call() {
        IrijFn logClause = args -> {
            IrijFn resume = (IrijFn) args[1];
            resume.apply(new Object[]{});
            // Second call must throw.
            assertThrows(RuntimeException.class,
                    () -> resume.apply(new Object[]{}));
            return "done";
        };
        Object result = RuntimeSupport.runWithSM(
                logHandler(logClause), new LogThenReturn(new AtomicInteger()));
        assertEquals("done", result);
    }

    @Test void multiple_sequential_performs_loop_through_handler() {
        // Body: log "a"; log "b"; log "c"; return "final"
        class MultiLog extends IrijContinuation {
            final java.util.List<String> seen = new java.util.ArrayList<>();
            @Override public Object resume(Object value) {
                switch (state) {
                    case 0: state = 1;
                        throw PerformSignal.of("Log", "log", new Object[]{"a"}, this);
                    case 1: state = 2;
                        throw PerformSignal.of("Log", "log", new Object[]{"b"}, this);
                    case 2: state = 3;
                        throw PerformSignal.of("Log", "log", new Object[]{"c"}, this);
                    case 3: return "final";
                }
                throw new IllegalStateException();
            }
        }
        MultiLog body = new MultiLog();
        IrijFn logClause = args -> {
            body.seen.add((String) args[0]);
            IrijFn resume = (IrijFn) args[1];
            return resume.apply(new Object[]{});
        };
        Object result = RuntimeSupport.runWithSM(logHandler(logClause), body);
        assertEquals("final", result);
        assertEquals(java.util.List.of("a", "b", "c"), body.seen);
    }

    @Test void unhandled_effect_reraises_to_outer() {
        // Inner handler only handles "Log"; body performs "Other".
        class OtherOp extends IrijContinuation {
            @Override public Object resume(Object v) {
                throw PerformSignal.of("Other", "boom", new Object[]{}, this);
            }
        }
        PerformSignal raised = assertThrows(PerformSignal.class, () ->
                RuntimeSupport.runWithSM(
                        logHandler(args -> null), new OtherOp()));
        assertEquals("Other", raised.effectName);
        assertEquals("boom",  raised.opName);
    }

    @Test void perform_signal_has_no_stack_trace() {
        PerformSignal s = PerformSignal.of("E", "op", new Object[]{}, null);
        assertEquals(0, s.getStackTrace().length,
                "stack-trace-free signal — hot-path perf");
    }
}
