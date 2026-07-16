package dev.irij.compiler;

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
