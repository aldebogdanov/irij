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
public record CompileOptions(boolean directLinking, boolean namespaceMode, boolean specLint,
                             boolean trustWarning) {

    public static CompileOptions defaults() {
        return new CompileOptions(false, false, true, false);
    }

    public CompileOptions withDirectLinking(boolean enabled) {
        return new CompileOptions(enabled, namespaceMode, specLint, trustWarning);
    }

    /** Namespace mode is for interactive evals (REPL, nREPL, MCP,
     *  Playground), where a spec-lint warning on every eval is noise —
     *  so it also turns the lint off. */
    public CompileOptions withNamespaceMode(boolean enabled) {
        return new CompileOptions(directLinking, enabled, specLint && !enabled, trustWarning);
    }

    /** {@code specLint}: warn (on stderr) about every {@code pub fn}
     *  without a spec annotation. On by default; {@code --no-spec-lint}
     *  turns it off. */
    public CompileOptions withSpecLint(boolean enabled) {
        return new CompileOptions(directLinking, namespaceMode, enabled, trustWarning);
    }

    /** {@code trustWarning}: with no irij.toml {@code [trust]}, warn (on
     *  stderr) when a seed's modules reach Java — `irij build` refuses a
     *  project without {@code [trust]}, and this says so before deploy
     *  time. Set by `irij run` only. */
    public CompileOptions withTrustWarning(boolean enabled) {
        return new CompileOptions(directLinking, namespaceMode, specLint, enabled);
    }
}
