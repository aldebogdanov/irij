package dev.irij.compiler;

/**
 * Configuration for {@link IrijCompiler}.
 *
 * <p>Selects the handler lowering strategy, controls hot-redef linking,
 * and toggles namespace mode for REPL-style stateful evaluation.
 *
 * <ul>
 *   <li>{@link HandlerStrategy#THREADED} — 14c.2 lowering via
 *       virtual thread + SynchronousQueue.</li>
 *   <li>{@link HandlerStrategy#STATE_MACHINE} — 14c.3 lowering that
 *       compiles handler bodies into a state machine.</li>
 *   <li>{@code directLinking} mirrors Clojure's {@code --direct-linking}
 *       deploy flag: when {@code true} (the deploy default), top-level
 *       fn calls use direct {@code invokestatic} for max JIT inlinability.
 *       When {@code false} (the dev default), they go through
 *       {@code invokedynamic} + {@code MutableCallSite} so the REPL can
 *       swap implementations live.</li>
 *   <li>{@code namespaceMode} — when {@code true}, top-level
 *       {@code :=} binds write through to a shared session namespace
 *       (via {@link RuntimeSupport#nsPut}), and unresolved Var loads
 *       fall back to the namespace (via {@link RuntimeSupport#nsGet}).
 *       Used by the nREPL's {@code eval-bytecode} op so successive
 *       evals share state. Off in normal build / interp.</li>
 * </ul>
 */
public record CompileOptions(HandlerStrategy handlerStrategy,
                              boolean directLinking,
                              boolean namespaceMode) {

    public enum HandlerStrategy {
        THREADED,
        STATE_MACHINE,
    }

    public static CompileOptions defaults() {
        return new CompileOptions(HandlerStrategy.THREADED, false, false);
    }

    public static CompileOptions threaded() {
        return new CompileOptions(HandlerStrategy.THREADED, false, false);
    }

    public static CompileOptions stateMachine() {
        return new CompileOptions(HandlerStrategy.STATE_MACHINE, false, false);
    }

    public CompileOptions withDirectLinking(boolean enabled) {
        return new CompileOptions(handlerStrategy, enabled, namespaceMode);
    }

    public CompileOptions withNamespaceMode(boolean enabled) {
        return new CompileOptions(handlerStrategy, directLinking, enabled);
    }
}
