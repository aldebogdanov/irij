package dev.irij.compiler;

public record ParentSnapshot(
        java.util.Deque<dev.irij.runtime.EffectSystem.HandlerContext> effectStack,
        java.util.Deque<java.util.List<CompiledHandler>> smStack,
        java.util.Deque<java.util.Set<String>> effectRow,
        java.util.Map<String, Object> namespace,
        java.io.PrintStream sessionOut) {}
