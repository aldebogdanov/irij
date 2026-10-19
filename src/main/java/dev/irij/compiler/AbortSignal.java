package dev.irij.compiler;

/**
 * A handler clause returned without calling {@code resume}, for an
 * operation performed outside the {@code with} body's own state machine —
 * in a fn the body called, say. The clause's value is the value of the
 * whole {@code with}, so the body has to be abandoned from wherever it
 * is: this unwinds the JVM stack to the dispatch loop running the
 * {@code with} whose handler aborted ({@link #target}, that loop's root
 * continuation), which returns {@link #value}.
 *
 * <p>Control flow, not an error: {@code try} and {@code on-failure}
 * re-throw it. Stack-trace-free, like {@link TailResume}.
 */
public final class AbortSignal extends RuntimeException {
    public final Object value;
    public final IrijContinuation target;

    private AbortSignal(Object value, IrijContinuation target) {
        super(null, null, false, false);
        this.value = value;
        this.target = target;
    }

    public static AbortSignal of(Object value, IrijContinuation target) {
        return new AbortSignal(value, target);
    }

    @Override public synchronized Throwable fillInStackTrace() { return this; }
}
