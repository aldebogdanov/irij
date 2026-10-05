package dev.irij.compiler;

/**
 * State-machine-lowered effect-bearing body or clause.
 *
 * <p>Concrete — not subclassed. The lowering pass emits an {@code IrijFn}
 * "step" closure that implements the switch-on-state; the continuation
 * holds the mutable state ({@code state} label + {@code fields} for
 * locals that cross {@code perform} boundaries).
 *
 * <p>Step contract: {@code step.apply([thisContinuation, resumeValue])}
 * either returns the final body value or throws {@link PerformSignal}.
 * The first entry passes {@code null} as {@code resumeValue}.
 *
 * <p>Lifted locals are stored in {@link #fields} so they survive across
 * state transitions (JVM operand stack does not survive a throw). The
 * lowering pass assigns each lifted local a stable index into this array.
 */
public final class IrijContinuation {
    public int state;
    public final Object[] fields;
    public final RuntimeSupport.IrijFn step;

    public IrijContinuation(RuntimeSupport.IrijFn step, int nFields) {
        this.step = step;
        this.fields = nFields == 0 ? EMPTY_FIELDS : new Object[nFields];
    }

    public static final Object[] EMPTY_FIELDS = new Object[0];

    /**
     * Enter or re-enter the state machine. Argument is the value fed in
     * by the handler's {@code resume} call (or {@code null} on first entry).
     * Either returns the body's final value, or throws
     * {@link PerformSignal} to yield to the enclosing handler.
     */
    public Object resume(Object value) {
        return step.apply(new Object[]{this, value});
    }
}
