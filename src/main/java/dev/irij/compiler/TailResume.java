package dev.irij.compiler;

public final class TailResume extends RuntimeException {
    public Object value;
    /**
     * The dispatch loop this resume targets — the continuation that
     * threw the original {@link PerformSignal}. Each loop's catch
     * compares against its own expected target and re-throws on
     * mismatch so nested loops don't accidentally consume each other's
     * resumes (relevant for native nested-SM and future tier-c
     * clause-as-SM compilation).
     */
    public Object target;

    public TailResume() { super(null, null, false, false); }

    public static TailResume of(Object v, Object target) {
        TailResume r = new TailResume();
        r.value = v;
        r.target = target;
        return r;
    }

    @Override public synchronized Throwable fillInStackTrace() { return this; }
}
