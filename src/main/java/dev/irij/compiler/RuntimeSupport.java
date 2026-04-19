package dev.irij.compiler;

/**
 * Runtime helpers invoked from compiled Irij bytecode.
 *
 * All Irij values are represented as boxed Java objects at runtime:
 *   Int   -> Long
 *   Float -> Double
 *   Bool  -> Boolean
 *   Str   -> String
 *   Unit  -> null (sentinel) // TODO: replace with dedicated Unit singleton
 */
public final class RuntimeSupport {

    private RuntimeSupport() {}

    /** Functional interface for first-class Irij lambdas (variadic via Object[]). */
    @FunctionalInterface
    public interface IrijFn {
        Object apply(Object[] args);
    }

    /** Helper for App sites when callee is an expression of unknown type. */
    public static Object callFn(Object fn, Object[] args) {
        if (fn instanceof IrijFn f) return f.apply(args);
        throw new IllegalArgumentException("Not callable: " + display(fn));
    }

    public static void print(Object v) {
        System.out.print(display(v));
    }

    public static void println(Object v) {
        System.out.println(display(v));
    }

    public static String display(Object v) {
        // Delegate to interpreter's display logic for bit-exact parity.
        if (v == null) return dev.irij.interpreter.Values.toIrijString(dev.irij.interpreter.Values.UNIT);
        return dev.irij.interpreter.Values.toIrijString(v);
    }

    public static String toStr(Object v) { return display(v); }

    public static Object concat(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return display(a) + display(b);
        }
        if (a instanceof dev.irij.interpreter.Values.IrijVector va
                && b instanceof dev.irij.interpreter.Values.IrijVector vb) {
            java.util.List<Object> out = new java.util.ArrayList<>(va.elements());
            out.addAll(vb.elements());
            return new dev.irij.interpreter.Values.IrijVector(out);
        }
        throw new IllegalArgumentException("++ not defined for: " + a + " and " + b);
    }

    public static boolean and(Object a, Object b) { return truthy(a) && truthy(b); }
    public static boolean or(Object a, Object b) { return truthy(a) || truthy(b); }

    // ── Arithmetic ──────────────────────────────────────────────────────

    public static Object add(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la + lb;
        return asDouble(a) + asDouble(b);
    }

    public static Object sub(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la - lb;
        return asDouble(a) - asDouble(b);
    }

    public static Object mul(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la * lb;
        return asDouble(a) * asDouble(b);
    }

    public static Object div(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            if (lb == 0) throw new ArithmeticException("division by zero");
            return la / lb;
        }
        double db = asDouble(b);
        if (db == 0.0) throw new ArithmeticException("division by zero");
        return asDouble(a) / db;
    }

    public static Object mod(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la % lb;
        return asDouble(a) % asDouble(b);
    }

    // ── Comparison ──────────────────────────────────────────────────────

    public static boolean lt(Object a, Object b) { return cmp(a, b) < 0; }
    public static boolean le(Object a, Object b) { return cmp(a, b) <= 0; }
    public static boolean gt(Object a, Object b) { return cmp(a, b) > 0; }
    public static boolean ge(Object a, Object b) { return cmp(a, b) >= 0; }

    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    public static boolean neq(Object a, Object b) { return !eq(a, b); }

    private static int cmp(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(asDouble(a), asDouble(b));
        }
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        throw new IllegalArgumentException("Cannot compare: " + a + " and " + b);
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("Not a number: " + v);
    }

    // ── Logic ───────────────────────────────────────────────────────────

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v == dev.irij.interpreter.Values.UNIT) return false;
        if (v instanceof Boolean b) return b;
        return true;
    }

    // ── Pattern-match helpers ────────────────────────────────────────────

    public static boolean isTag(Object v, String tag) {
        return v instanceof dev.irij.interpreter.Values.Tagged t && t.tag().equals(tag);
    }

    public static Object taggedField(Object v, int i) {
        return ((dev.irij.interpreter.Values.Tagged) v).fields().get(i);
    }

    public static int taggedArity(Object v) {
        return ((dev.irij.interpreter.Values.Tagged) v).fields().size();
    }

    public static boolean isUnit(Object v) {
        return v == null || v == dev.irij.interpreter.Values.UNIT;
    }

    public static boolean isKeyword(Object v, String name) {
        return v instanceof dev.irij.interpreter.Values.Keyword k && k.name().equals(name);
    }

    public static boolean isVector(Object v) { return v instanceof dev.irij.interpreter.Values.IrijVector; }
    public static boolean isTuple(Object v)  { return v instanceof dev.irij.interpreter.Values.IrijTuple; }
    public static boolean isMap(Object v)    { return v instanceof dev.irij.interpreter.Values.IrijMap; }

    public static int vecSize(Object v) {
        return ((dev.irij.interpreter.Values.IrijVector) v).elements().size();
    }

    public static Object vecGet(Object v, int i) {
        return ((dev.irij.interpreter.Values.IrijVector) v).elements().get(i);
    }

    public static Object vecSlice(Object v, int from, int to) {
        var es = ((dev.irij.interpreter.Values.IrijVector) v).elements();
        return new dev.irij.interpreter.Values.IrijVector(new java.util.ArrayList<>(es.subList(from, to)));
    }

    /** Build an IrijVector from args[from..] — used for lambda rest params. */
    public static Object restVector(Object[] args, int from) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (int i = from; i < args.length; i++) out.add(args[i]);
        return new dev.irij.interpreter.Values.IrijVector(out);
    }

    public static int tupleSize(Object v) {
        return ((dev.irij.interpreter.Values.IrijTuple) v).elements().length;
    }

    public static Object tupleGet(Object v, int i) {
        return ((dev.irij.interpreter.Values.IrijTuple) v).elements()[i];
    }

    public static Object mapGet(Object v, String k) {
        return ((dev.irij.interpreter.Values.IrijMap) v).entries().get(k);
    }

    public static boolean mapHas(Object v, String k) {
        return ((dev.irij.interpreter.Values.IrijMap) v).entries().containsKey(k);
    }

    /** Field lookup across IrijMap and Tagged-with-named-fields. */
    public static boolean recordHas(Object v, String k) {
        if (v instanceof dev.irij.interpreter.Values.IrijMap m) return m.entries().containsKey(k);
        if (v instanceof dev.irij.interpreter.Values.Tagged t && t.namedFields() != null)
            return t.namedFields().containsKey(k);
        return false;
    }

    public static Object recordGet(Object v, String k) {
        if (v instanceof dev.irij.interpreter.Values.IrijMap m) return m.entries().get(k);
        if (v instanceof dev.irij.interpreter.Values.Tagged t && t.namedFields() != null)
            return t.namedFields().get(k);
        return null;
    }

    public static boolean isRecord(Object v) {
        return v instanceof dev.irij.interpreter.Values.IrijMap
                || (v instanceof dev.irij.interpreter.Values.Tagged t && t.namedFields() != null);
    }

    public static IllegalStateException noMatch(Object v) {
        return new IllegalStateException("No match arm for: " + display(v));
    }

    /** Runtime type tag used for protocol dispatch. */
    public static String typeTag(Object v) {
        if (v == null || v == dev.irij.interpreter.Values.UNIT) return "Unit";
        if (v instanceof Long) return "Int";
        if (v instanceof Double) return "Float";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof String) return "Str";
        if (v instanceof dev.irij.interpreter.Values.Keyword) return "Keyword";
        if (v instanceof dev.irij.interpreter.Values.IrijVector) return "Vector";
        if (v instanceof dev.irij.interpreter.Values.IrijTuple) return "Tuple";
        if (v instanceof dev.irij.interpreter.Values.IrijMap) return "Map";
        if (v instanceof dev.irij.interpreter.Values.IrijSet) return "Set";
        if (v instanceof dev.irij.interpreter.Values.Tagged t) {
            return t.specName() != null ? t.specName() : t.tag();
        }
        return v.getClass().getSimpleName();
    }

    public static IllegalStateException noImpl(String method, Object arg) {
        return new IllegalStateException("No impl of " + method + " for " + typeTag(arg));
    }
}
