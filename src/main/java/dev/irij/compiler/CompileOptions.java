package dev.irij.compiler;

/**
 * Configuration for {@link IrijCompiler}.
 *
 * <p>Selects the handler lowering strategy and controls hot-redef linking:
 * <ul>
 *   <li>{@link HandlerStrategy#THREADED} — 14c.2 lowering via
 *       virtual thread + SynchronousQueue.</li>
 *   <li>{@link HandlerStrategy#STATE_MACHINE} — 14c.3 lowering that
 *       compiles handler bodies into a state machine.</li>
 * </ul>
 *
 * <p>{@code directLinking} mirrors Clojure's {@code --direct-linking}
 * deploy flag: when {@code true} (the deploy default), top-level fn calls
 * use direct {@code invokestatic} for max JIT inlinability. When
 * {@code false} (the dev default), they go through {@code invokedynamic}
 * + {@code MutableCallSite} so the REPL can swap implementations live.
 */
public record CompileOptions(HandlerStrategy handlerStrategy, boolean directLinking) {

    public enum HandlerStrategy {
        THREADED,
        STATE_MACHINE,
    }

    public static CompileOptions defaults() {
        return new CompileOptions(HandlerStrategy.THREADED, false);
    }

    public static CompileOptions threaded() {
        return new CompileOptions(HandlerStrategy.THREADED, false);
    }

    public static CompileOptions stateMachine() {
        return new CompileOptions(HandlerStrategy.STATE_MACHINE, false);
    }

    public CompileOptions withDirectLinking(boolean enabled) {
        return new CompileOptions(handlerStrategy, enabled);
    }
}
