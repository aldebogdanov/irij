package dev.irij.compiler;

/**
 * Compiled handler value: clause map from op-name to IrijFn.
 * Each clause IrijFn is invoked with arg-array that ends with the resume
 * IrijFn: {@code args..., resume}. Clause returns the value that should
 * be the result of the enclosing `with` block.
 */
public final class CompiledHandler {
    public final String name;
    public final String effectName;
    public final java.util.Map<String, RuntimeSupport.IrijFn> clauses;
    public CompiledHandler(String name, String effectName, java.util.Map<String, RuntimeSupport.IrijFn> clauses) {
        this.name = name; this.effectName = effectName; this.clauses = clauses;
    }
}
