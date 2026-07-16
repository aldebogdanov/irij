package dev.irij.compiler;

public final class PerformSignal extends RuntimeException {
    public String effectName;
    public String opName;
    public Object[] args;
    public IrijContinuation continuation;

    public PerformSignal() { super(null, null, false, false); }

    public static PerformSignal of(String effectName, String opName,
                                    Object[] args, IrijContinuation k) {
        PerformSignal s = new PerformSignal();
        s.effectName = effectName;
        s.opName = opName;
        s.args = args;
        s.continuation = k;
        return s;
    }

    @Override public synchronized Throwable fillInStackTrace() { return this; }
}
