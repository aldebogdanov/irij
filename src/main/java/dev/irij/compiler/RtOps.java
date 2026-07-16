package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtOps domain. */
public final class RtOps {

    private RtOps() {}


    // ── Operator sections — (+), (-), (*), etc. as first-class values ──
    //
    // Emitter lowers `Expr.OpSection(op)` to GETSTATIC of one of these
    // constants. Lets users pass operators by name to higher-order fns:
    //   fold (+) 0 #[1 2 3]   ;; sums 6
    public static final RuntimeSupport.IrijFn OP_ADD = args -> add(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_SUB = args -> sub(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_MUL = args -> mul(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_DIV = args -> div(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_MOD = args -> mod(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_CONCAT = args -> concat(args[0], args[1]);

    public static final RuntimeSupport.IrijFn OP_LT  = args -> Boolean.valueOf(lt(args[0], args[1]));

    public static final RuntimeSupport.IrijFn OP_LE  = args -> Boolean.valueOf(le(args[0], args[1]));

    public static final RuntimeSupport.IrijFn OP_GT  = args -> Boolean.valueOf(gt(args[0], args[1]));

    public static final RuntimeSupport.IrijFn OP_GE  = args -> Boolean.valueOf(ge(args[0], args[1]));

    public static final RuntimeSupport.IrijFn OP_EQ  = args -> Boolean.valueOf(eq(args[0], args[1]));

    public static final RuntimeSupport.IrijFn OP_NEQ = args -> Boolean.valueOf(neq(args[0], args[1]));


    public static Object concat(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return RuntimeSupport.display(a) + RuntimeSupport.display(b);
        }
        if (a instanceof dev.irij.runtime.Values.IrijVector va
                && b instanceof dev.irij.runtime.Values.IrijVector vb) {
            java.util.List<Object> out = new java.util.ArrayList<>(va.elements());
            out.addAll(vb.elements());
            return new dev.irij.runtime.Values.IrijVector(out);
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


    public static int cmp(Object a, Object b) {
        // Delegate to the canonical comparator in Builtins — handles
        // Long/Double/String/Keyword/Tuple/Vector recursively.
        return dev.irij.runtime.Builtins.compare(a, b);
    }


    private static double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("Not a number: " + v);
    }


    // ── Logic ───────────────────────────────────────────────────────────

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v == dev.irij.runtime.Values.UNIT) return false;
        if (v instanceof Boolean b) return b;
        return true;
    }


    // ── Misc ───────────────────────────────────────────────────────────

    public static Object notOp(Object v) {
        return !truthy(v);
    }

    public static Object min(Object a, Object b) {
        return cmp(a, b) <= 0 ? a : b;
    }

    public static Object max(Object a, Object b) {
        return cmp(a, b) >= 0 ? a : b;
    }

    public static Object divInt(Object a, Object b) {
        long la = RtCollections.asLongArg(a, "div");
        long lb = RtCollections.asLongArg(b, "div");
        if (lb == 0) throw new dev.irij.IrijRuntimeError("Division by zero");
        return la / lb;
    }

    public static Object modInt(Object a, Object b) {
        long la = RtCollections.asLongArg(a, "mod");
        long lb = RtCollections.asLongArg(b, "mod");
        if (lb == 0) throw new dev.irij.IrijRuntimeError("Division by zero");
        return la % lb;
    }

    public static Object concatTwo(Object a, Object b) {
        // Match Builtins.concatValues semantics: Vec+Vec→Vec, Str+Str→Str.
        if (a instanceof String sa && b instanceof String sb) return sa + sb;
        if (a instanceof dev.irij.runtime.Values.IrijVector va
                && b instanceof dev.irij.runtime.Values.IrijVector vb) {
            java.util.List<Object> out = new java.util.ArrayList<>(va.elements());
            out.addAll(vb.elements());
            return new dev.irij.runtime.Values.IrijVector(out);
        }
        throw new dev.irij.IrijRuntimeError(
                "concat: type mismatch (" + RuntimeSupport.typeTag(a) + ", " + RuntimeSupport.typeTag(b) + ")");
    }


    public static double asDoubleArg(Object v, String op) {
        if (v instanceof Number n) return n.doubleValue();
        throw new dev.irij.IrijRuntimeError(
                op + " expects a number, got " + RuntimeSupport.typeTag(v));
    }
}
