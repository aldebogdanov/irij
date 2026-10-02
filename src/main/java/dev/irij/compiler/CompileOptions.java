package dev.irij.compiler;

/**
 * Configuration for {@link IrijCompiler}.
 *
 * <p>v0.6.13: Irij has a single execution model — bytecode compilation
 * with state-machine handler lowering. The old threaded (14c.2)
 * handler strategy was removed; SM (14c.3) is now the only option.
 *
 * <ul>
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
 *       Used by the REPL / nREPL / MCP / Playground sessions so
 *       successive evals share state. Off in normal build.</li>
 * </ul>
 */
public record CompileOptions(boolean directLinking, boolean namespaceMode, boolean specLint) {

    public static CompileOptions defaults() {
        return new CompileOptions(false, false, true);
    }

    /** @deprecated Threaded handler mode was removed in v0.6.13.
     *  Kept only as a no-op alias for source compatibility — returns
     *  the default SM-strategy options. */
    @Deprecated
    public static CompileOptions stateMachine() {
        return defaults();
    }

    public CompileOptions withDirectLinking(boolean enabled) {
        return new CompileOptions(enabled, namespaceMode, specLint);
    }

    /** Namespace mode is for interactive evals (REPL, nREPL, MCP,
     *  Playground), where a spec-lint warning on every eval is noise —
     *  so it also turns the lint off. */
    public CompileOptions withNamespaceMode(boolean enabled) {
        return new CompileOptions(directLinking, enabled, specLint && !enabled);
    }

    /** {@code specLint}: warn (on stderr) about every {@code pub fn}
     *  without a spec annotation. On by default; {@code --no-spec-lint}
     *  turns it off. */
    public CompileOptions withSpecLint(boolean enabled) {
        return new CompileOptions(directLinking, namespaceMode, enabled);
    }
}
