package dev.irij.compiler;

public final class CompiledHandler {
    public final String name;
    public final String effectName;
    public final java.util.Map<String, RuntimeSupport.IrijFn> clauses;
    public CompiledHandler(String name, String effectName, java.util.Map<String, RuntimeSupport.IrijFn> clauses) {
        this.name = name; this.effectName = effectName; this.clauses = clauses;
    }
}
