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
            return concatVectors(va, vb);
        }
        throw new IllegalArgumentException("++ not defined for: " + a + " and " + b);
    }


    /** {@code a ++ b} on Vectors: appends b's elements to a's persistent
     *  vector — O(|b|), so accumulating with {@code acc ++ #[x]} is linear. */
    static dev.irij.runtime.Values.IrijVector concatVectors(
            dev.irij.runtime.Values.IrijVector a, dev.irij.runtime.Values.IrijVector b) {
        if (b.elements().isEmpty()) return a;
        if (a.elements().isEmpty()) return b;
        var out = (dev.irij.runtime.PVec) a.elements();
        for (Object x : b.elements()) out = out.cons(x);
        return new dev.irij.runtime.Values.IrijVector(out);
    }

    public static boolean and(Object a, Object b) { return truthy(a) && truthy(b); }

    public static boolean or(Object a, Object b) { return truthy(a) || truthy(b); }


    // ── Arithmetic ──────────────────────────────────────────────────────

    // The 64-bit fast paths stay inline; an overflow, or any operand that
    // isn't a Long (a big Int, a Rational, a Float), goes to RtNum. Int
    // never wraps: past 64 bits it continues as a BigInteger.

    public static Object add(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            long x = la, y = lb, r = x + y;
            if (((x ^ r) & (y ^ r)) >= 0) return r;
        }
        return RtNum.add(a, b);
    }


    public static Object sub(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            long x = la, y = lb, r = x - y;
            if (((x ^ y) & (x ^ r)) >= 0) return r;
        }
        return RtNum.sub(a, b);
    }


    public static Object mul(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            long x = la, y = lb, r = x * y;
            if (Math.multiplyHigh(x, y) == (r >> 63)) return r;
        }
        return RtNum.mul(a, b);
    }


    public static Object div(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb && lb != 0 && !(la == Long.MIN_VALUE && lb == -1)) {
            return la / lb;
        }
        return RtNum.div(a, b);
    }


    public static Object mod(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb && lb != 0) return la % lb;
        return RtNum.mod(a, b);
    }


    // ── Comparison ──────────────────────────────────────────────────────

    public static boolean lt(Object a, Object b) { return cmp(a, b) < 0; }

    public static boolean le(Object a, Object b) { return cmp(a, b) <= 0; }

    public static boolean gt(Object a, Object b) { return cmp(a, b) > 0; }

    public static boolean ge(Object a, Object b) { return cmp(a, b) >= 0; }


    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        // Int == Int compares exactly: through double, every pair of Ints
        // past 2^53 that round to the same double compared equal.
        if (a instanceof Long la && b instanceof Long lb) return la.longValue() == lb.longValue();
        if (RtNum.isNumber(a) && RtNum.isNumber(b)) return RtNum.numEq(a, b);
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
        return RtNum.quo(a, b);
    }

    public static Object modInt(Object a, Object b) {
        return RtNum.rem(a, b);
    }

    public static Object concatTwo(Object a, Object b) {
        // Match Builtins.concatValues semantics: Vec+Vec→Vec, Str+Str→Str.
        if (a instanceof String sa && b instanceof String sb) return sa + sb;
        if (a instanceof dev.irij.runtime.Values.IrijVector va
                && b instanceof dev.irij.runtime.Values.IrijVector vb) {
            return concatVectors(va, vb);
        }
        throw new dev.irij.IrijRuntimeError(
                "concat: type mismatch (" + RuntimeSupport.typeTag(a) + ", " + RuntimeSupport.typeTag(b) + ")");
    }


    public static double asDoubleArg(Object v, String op) {
        return RtNum.toDouble(v, op);
    }
}
