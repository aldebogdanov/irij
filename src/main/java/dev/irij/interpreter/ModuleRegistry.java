package dev.irij.interpreter;

import java.util.*;

/**
 * Registry for module-qualified names and import resolution.
 *
 * <p>Stdlib functions are registered with fully-qualified names like
 * {@code std.math.abs}. The {@code use} declaration creates short
 * aliases in the interpreter's builtin map.
 *
 * <p>Modules:
 * <ul>
 *   <li>{@code std.core} — identity, show, type-of, etc.
 *   <li>{@code std.math} — abs, min, max, sqrt, etc.
 *   <li>{@code std.text} — length, split, join, trim, etc.
 *   <li>{@code std.collection} — head, tail, map, filter, etc.
 *   <li>{@code std.io} — println, read-line, read-file, etc.
 * </ul>
 */
public final class ModuleRegistry {

    private ModuleRegistry() {}

    /** Maps fully-qualified names to their short names, grouped by module. */
    private static final Map<String, List<String>> MODULE_FUNCTIONS = new LinkedHashMap<>();

    static {
        register("std.core", List.of(
                "identity", "to-str", "show", "type-of", "panic", "dbg",
                "not", "equals?", "true", "false"));
        register("std.math", List.of(
                "abs", "min", "max", "floor", "ceil", "round", "sqrt",
                "pow", "log", "log10", "sin", "cos", "tan",
                "pi", "e", "random", "to-int", "to-float",
                "int?", "float?", "nan?", "infinite?"));
        register("std.text", List.of(
                "length", "split", "join", "trim",
                "contains?", "starts-with?", "ends-with?",
                "to-upper", "to-lower", "replace", "substring",
                "chars", "str-repeat", "str?", "index-of"));
        register("std.collection", List.of(
                "head", "tail", "last", "init", "take", "drop",
                "reverse", "sort", "sort-by", "concat", "flatten",
                "zip", "enumerate", "distinct", "append", "prepend", "nth",
                "get", "put", "remove", "keys", "values", "merge", "has-key?",
                "to-set", "to-list", "union", "intersection", "difference",
                "range", "size", "empty?", "list?", "map?"));
        register("std.io", List.of(
                "println", "print-str", "read-line",
                "read-file", "write-file", "file-exists?"));
    }

    private static void register(String module, List<String> functions) {
        MODULE_FUNCTIONS.put(module, functions);
    }

    /**
     * Get all function short-names in a module.
     *
     * @param modulePath e.g. "std.math"
     * @return list of function names, or empty list if module unknown
     */
    public static List<String> getFunctions(String modulePath) {
        return MODULE_FUNCTIONS.getOrDefault(modulePath, List.of());
    }

    /**
     * Check if a module path is a known stdlib module.
     */
    public static boolean isStdlibModule(String modulePath) {
        return MODULE_FUNCTIONS.containsKey(modulePath);
    }

    /**
     * Get all known module paths.
     */
    public static Set<String> allModules() {
        return MODULE_FUNCTIONS.keySet();
    }
}
