package dev.irij.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The builtin registry {@link Builtins#install} fills: name → value, in
 * registration order. {@code RuntimeSupport} turns it into the table the
 * emitter's callAny fallthrough dispatches to.
 *
 * <p>(This was the tree-walk interpreter's lexical environment — parent
 * chains, mutable and hot-redef cells. Only the flat registry survived
 * the interpreter's removal.)
 */
public final class Environment {

    private final Map<String, Object> bindings = new LinkedHashMap<>();

    public void define(String name, Object value) {
        bindings.put(name, value);
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }
}
