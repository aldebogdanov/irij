package dev.irij.compiler;

/**
 * Tail-resume sentinel — thrown by the synthesised {@code resumeFn} when
 * a clause invokes {@code resume v} so the dispatch loop unwinds the
 * clause's JVM frames and continues iteratively. Stack-trace-free.
 *
 * <p>Every {@code resume} is a clause's last step — anything else is a
 * compile error ({@code EffectEmitter.checkResumeIsLast}, spec §3.2) — so
 * this throw never skips clause code, and tight perform-loops run in
 * constant stack.
 */
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
