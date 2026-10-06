package dev.irij.compiler;

/** Flat ordered list of handlers from a `>>` composition. */
public final class CompiledComposedHandler {
    public final java.util.List<CompiledHandler> handlers;
    public CompiledComposedHandler(java.util.List<CompiledHandler> handlers) {
        this.handlers = handlers;
    }
}
