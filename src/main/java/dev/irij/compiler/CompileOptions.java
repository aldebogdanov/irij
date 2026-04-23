package dev.irij.compiler;

/**
 * Configuration for {@link IrijCompiler}.
 *
 * <p>Primarily selects the handler lowering strategy:
 * <ul>
 *   <li>{@link HandlerStrategy#THREADED} — 14c.2 lowering via
 *       virtual thread + SynchronousQueue (current default, correct,
 *       heavier per-`with`).</li>
 *   <li>{@link HandlerStrategy#STATE_MACHINE} — 14c.3 lowering that
 *       rewrites clause bodies into a state machine so handlers don't
 *       pay the vthread/queue cost. <em>Not yet implemented.</em></li>
 * </ul>
 */
public record CompileOptions(HandlerStrategy handlerStrategy) {

    public enum HandlerStrategy {
        THREADED,
        STATE_MACHINE,
    }

    public static CompileOptions defaults() {
        return new CompileOptions(HandlerStrategy.THREADED);
    }

    public static CompileOptions threaded() {
        return new CompileOptions(HandlerStrategy.THREADED);
    }

    public static CompileOptions stateMachine() {
        return new CompileOptions(HandlerStrategy.STATE_MACHINE);
    }
}
