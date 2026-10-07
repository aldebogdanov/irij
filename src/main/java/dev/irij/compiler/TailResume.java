package dev.irij.compiler;

/**
 * Tail-resume sentinel — thrown by the synthesised {@code resumeFn} when
 * a clause invokes {@code resume v} so the dispatch loop unwinds the
 * clause's JVM frames and continues iteratively. Stack-trace-free.
 *
 * <p><b>Semantic note:</b> idiomatic Irij clauses put {@code resume} in
 * tail position ({@code "stmt; stmt; resume v"}). For those, this throw
 * is purely a control-flow shortcut and behaviour is unchanged. For
 * non-tail clauses ({@code "resume v; postStmt"}) the trampoline causes
 * post-resume statements to be skipped — a deliberate trade-off so that
 * tight perform-loops scale beyond the JVM stack. The same shape can be
 * expressed by moving post-resume code outside the clause.
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
