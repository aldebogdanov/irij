package dev.irij.interpreter;

import dev.irij.ast.Expr;
import dev.irij.ast.Pattern;

import java.util.*;
import java.util.function.Function;

/**
 * Runtime value types for the Irij interpreter.
 *
 * Primitive values use Java boxed types:
 *   Int → Long, Float → Double, Bool → Boolean, Str → String
 *
 * Complex values use the records defined here.
 */
public final class Values {

    private Values() {} // utility class

    // ── Unit singleton ──────────────────────────────────────────────────

    public static final Object UNIT = new Object() {
        @Override public String toString() { return "()"; }
        @Override public int hashCode() { return 0; }
        @Override public boolean equals(Object o) { return o == this; }
    };

    // ── Rational number ─────────────────────────────────────────────────

    public record Rational(long num, long den) {
        public Rational {
            if (den == 0) throw new ArithmeticException("Rational with zero denominator");
            // Normalize: keep denominator positive
            if (den < 0) { num = -num; den = -den; }
            long g = gcd(Math.abs(num), den);
            num = num / g;
            den = den / g;
        }

        public double toDouble() {
            return (double) num / den;
        }

        @Override
        public String toString() {
            return num + "/" + den;
        }

        private static long gcd(long a, long b) {
            while (b != 0) { long t = b; b = a % b; a = t; }
            return a;
        }
    }

    // ── Keyword atom ────────────────────────────────────────────────────

    public record Keyword(String name) {
        @Override
        public String toString() {
            return ":" + name;
        }
    }

    // ── Tagged value (ADT constructor) ──────────────────────────────────

    /**
     * A tagged value (ADT variant or product type instance).
     * Sum types use positional fields; product types also carry namedFields.
     */
    public record Tagged(String tag, List<Object> fields, Map<String, Object> namedFields) {
        /** Convenience constructor for sum types (positional only). */
        public Tagged(String tag, List<Object> fields) {
            this(tag, fields, null);
        }

        @Override
        public String toString() {
            if (namedFields != null) {
                // Product type: Person {name= "Jo" age= 42}
                if (namedFields.isEmpty()) return tag;
                var sb = new StringBuilder(tag);
                sb.append(" {");
                boolean first = true;
                for (var e : namedFields.entrySet()) {
                    if (!first) sb.append(' ');
                    first = false;
                    sb.append(e.getKey()).append("= ").append(Values.toIrijString(e.getValue()));
                }
                sb.append('}');
                return sb.toString();
            }
            // Sum type: Some 42
            if (fields.isEmpty()) return tag;
            var sb = new StringBuilder(tag);
            for (var f : fields) {
                sb.append(' ');
                if (f instanceof String s) {
                    sb.append('"').append(s).append('"');
                } else {
                    sb.append(Values.toIrijString(f));
                }
            }
            return sb.toString();
        }
    }

    // ── Collections ─────────────────────────────────────────────────────

    public record IrijVector(List<Object> elements) {
        public IrijVector {
            elements = List.copyOf(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.toIrijString(elements.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    public record IrijSet(Set<Object> elements) {
        public IrijSet {
            elements = Set.copyOf(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#{");
            boolean first = true;
            for (var e : elements) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(Values.toIrijString(e));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    public record IrijMap(Map<String, Object> entries) {
        public IrijMap {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var e : entries.entrySet()) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(e.getKey()).append("= ").append(Values.toIrijString(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    public record IrijTuple(Object[] elements) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IrijTuple t && Arrays.equals(elements, t.elements);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#(");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.toIrijString(elements[i]));
            }
            sb.append(')');
            return sb.toString();
        }
    }

    // ── Range (lazy iterable) ───────────────────────────────────────────

    public record IrijRange(long from, long to, boolean exclusive) implements Iterable<Object> {
        @Override
        public Iterator<Object> iterator() {
            long end = exclusive ? to : to + 1;
            return new Iterator<>() {
                long current = from;

                @Override
                public boolean hasNext() {
                    return current < end;
                }

                @Override
                public Object next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return current++;
                }
            };
        }

        public int size() {
            long end = exclusive ? to : to + 1;
            return (int) Math.max(0, end - from);
        }

        @Override
        public String toString() {
            return from + (exclusive ? " ..< " : " .. ") + to;
        }
    }

    // ── Lambda (closure) ────────────────────────────────────────────────

    public record Lambda(List<Pattern> params, Expr body, Environment closure, String name) {
        /** Anonymous lambda. */
        public Lambda(List<Pattern> params, Expr body, Environment closure) {
            this(params, body, closure, null);
        }

        public int arity() {
            return params.size();
        }

        @Override
        public String toString() {
            return name != null ? "<fn " + name + ">" : "<lambda>";
        }
    }

    // ── Builtin function ────────────────────────────────────────────────

    public record BuiltinFn(String name, int arity,
                            Function<List<Object>, Object> impl) {
        public Object apply(List<Object> args) {
            return impl.apply(args);
        }

        @Override
        public String toString() {
            return "<builtin " + name + ">";
        }
    }

    // ── Partial application ─────────────────────────────────────────────

    public record PartialApp(Object fn, List<Object> appliedArgs) {
        @Override
        public String toString() {
            return "<partial>";
        }
    }

    // ── Composed function ───────────────────────────────────────────────

    public record ComposedFn(Object first, Object second) {
        @Override
        public String toString() {
            return "<composed>";
        }
    }

    // ── Type constructor function ───────────────────────────────────────

    /**
     * Constructor function for ADT variants and product types.
     * Sum type variants: fieldNames is null (positional).
     * Product types: fieldNames maps positional args to named fields.
     */
    public record Constructor(String tag, int arity, List<String> fieldNames) {
        /** Convenience constructor for sum type variants (positional only). */
        public Constructor(String tag, int arity) {
            this(tag, arity, null);
        }

        public Tagged apply(List<Object> args) {
            if (fieldNames != null) {
                // Product type: build named field map
                var named = new java.util.LinkedHashMap<String, Object>();
                for (int i = 0; i < fieldNames.size() && i < args.size(); i++) {
                    named.put(fieldNames.get(i), args.get(i));
                }
                return new Tagged(tag, List.copyOf(args), named);
            }
            return new Tagged(tag, List.copyOf(args));
        }

        @Override
        public String toString() {
            return "<constructor " + tag + "/" + arity + ">";
        }
    }

    // ── Effect system values ─────────────────────────────────────────────

    /**
     * Descriptor for a declared effect (e.g., {@code effect Console}).
     * Stored in the environment so handlers can validate against it.
     */
    public record EffectDescriptor(String name, List<String> ops) {
        @Override
        public String toString() {
            return "<effect " + name + ">";
        }
    }

    /**
     * A first-class handler value created by {@code handler h :: E}.
     *
     * @param name        handler name (e.g., "console-to-stdout")
     * @param effectName  the effect this handler handles (e.g., "Console")
     * @param clauses     map from op name → HandlerClause AST node
     * @param closureEnv  environment capturing handler-local state
     */
    public record HandlerValue(String name, String effectName,
                               Map<String, dev.irij.ast.Decl.HandlerClause> clauses,
                               Environment closureEnv) {
        @Override
        public String toString() {
            return "<handler " + name + " :: " + effectName + ">";
        }
    }

    /**
     * Two or more handlers composed via {@code >>}.
     * When used with {@code with}, decomposes into nested {@code with} blocks:
     * {@code with (h1 >> h2)} ≡ {@code with h1 (with h2 body)}.
     */
    public record ComposedHandler(List<Object> handlers) {
        @Override
        public String toString() {
            return "<composed-handler " + handlers.size() + ">";
        }
    }

    // ── Protocol system values ───────────────────────────────────────────

    /**
     * Descriptor for a declared protocol (e.g., {@code proto Monoid a}).
     * Holds method names and a dispatch table mapping type names to impl bindings.
     *
     * <p>When a protocol method is called, the dispatch function checks
     * {@code Values.typeName(firstArg)} against this table to find the
     * correct implementation.</p>
     *
     * @param name        protocol name (e.g., "Monoid")
     * @param methodNames list of method names declared by this protocol
     * @param impls       map from type name → (method name → value)
     */
    public record ProtocolDescriptor(String name, List<String> methodNames,
                                     Map<String, Map<String, Object>> impls) {
        public ProtocolDescriptor(String name, List<String> methodNames) {
            this(name, methodNames, new LinkedHashMap<>());
        }

        /** Register an implementation for a given type. */
        public void registerImpl(String typeName, Map<String, Object> bindings) {
            impls.put(typeName, bindings);
        }

        /** Look up a method for a given runtime type. */
        public Object dispatch(String methodName, String typeName) {
            var typeImpls = impls.get(typeName);
            if (typeImpls == null) return null;
            return typeImpls.get(methodName);
        }

        @Override
        public String toString() {
            return "<proto " + name + ">";
        }
    }

    // ── Module system values ────────────────────────────────────────────

    /**
     * A loaded module value, created by {@code use} declarations.
     * The {@code exports} environment contains only public bindings.
     * Dot-access on a ModuleValue looks up names in the exports environment.
     */
    public record ModuleValue(String qualifiedName, Environment exports) {
        @Override
        public String toString() {
            return "<module " + qualifiedName + ">";
        }
    }

    // ── Value utilities ─────────────────────────────────────────────────

    /** Convert a runtime value to its Irij string representation. */
    public static String toIrijString(Object value) {
        if (value == null) return "()";
        if (value == UNIT) return "()";
        if (value instanceof Long l) return l.toString();
        if (value instanceof Double d) {
            // Show integers without decimal point if possible
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return String.valueOf(d);
        }
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof String s) return s;
        if (value instanceof Thread t) return "<thread " + t.threadId() + ">";
        return value.toString();
    }

    /** Check if a value is truthy. */
    public static boolean isTruthy(Object value) {
        if (value == null || value == UNIT) return false;
        if (value instanceof Boolean b) return b;
        // Everything else is truthy
        return true;
    }

    /** Get a human-readable type name for error messages. */
    public static String typeName(Object value) {
        if (value == null || value == UNIT) return "Unit";
        if (value instanceof Long) return "Int";
        if (value instanceof Double) return "Float";
        if (value instanceof Rational) return "Rational";
        if (value instanceof Boolean) return "Bool";
        if (value instanceof String) return "Str";
        if (value instanceof Keyword) return "Keyword";
        if (value instanceof IrijVector) return "Vector";
        if (value instanceof IrijMap) return "Map";
        if (value instanceof IrijSet) return "Set";
        if (value instanceof IrijTuple) return "Tuple";
        if (value instanceof IrijRange) return "Range";
        if (value instanceof Tagged t) return t.tag();
        if (value instanceof Lambda) return "Lambda";
        if (value instanceof BuiltinFn) return "BuiltinFn";
        if (value instanceof PartialApp) return "PartialApp";
        if (value instanceof ComposedFn) return "ComposedFn";
        if (value instanceof Constructor) return "Constructor";
        if (value instanceof EffectDescriptor ed) return "Effect(" + ed.name() + ")";
        if (value instanceof HandlerValue hv) return "Handler(" + hv.name() + ")";
        if (value instanceof ComposedHandler) return "ComposedHandler";
        if (value instanceof ProtocolDescriptor pd) return "Proto(" + pd.name() + ")";
        if (value instanceof ModuleValue mv) return "Module(" + mv.qualifiedName() + ")";
        if (value instanceof Thread) return "Thread";
        return value.getClass().getSimpleName();
    }

    /** Check if a value is numeric (Int, Float, or Rational). */
    public static boolean isNumeric(Object value) {
        return value instanceof Long || value instanceof Double || value instanceof Rational;
    }
}
