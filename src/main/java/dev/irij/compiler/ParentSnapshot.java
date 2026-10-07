package dev.irij.compiler;

/** What a forked fiber inherits from the thread that forked it: the SM
 *  handler frames, the effect-row stack, and the session bindings. */
public record ParentSnapshot(
        java.util.Deque<java.util.List<CompiledHandler>> smStack,
        java.util.Deque<java.util.Set<String>> effectRow,
        java.util.Map<String, Object> namespace,
        java.io.PrintStream sessionOut) {}
