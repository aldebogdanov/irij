package dev.irij.compiler;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The numeric tower: Int, Rational and Float, and how they mix.
 *
 * <ul>
 *   <li><b>Int</b> — arbitrary precision, never wraps. Represented as a
 *       {@code Long} when the value fits in 64 bits and as a
 *       {@link BigInteger} only when it doesn't. The representation is
 *       canonical — a {@code BigInteger} Int is always outside the
 *       {@code long} range — so {@code equals}/{@code hashCode} need no
 *       cross-representation cases. {@link RtOps} keeps the 64-bit fast
 *       path inline and calls here only on overflow or a non-Long operand.</li>
 *   <li><b>Rational</b> — {@link Values.Rational}, an exact fraction of two
 *       Ints, always in lowest terms with a positive denominator, and never
 *       with denominator 1 (that is an Int).</li>
 *   <li><b>Float</b> — a {@code Double}. Any Float operand makes the result
 *       a Float.</li>
 * </ul>
 *
 * Int ⊕ Rational → Rational; anything ⊕ Float → Float. {@code /} on two
 * Ints truncates toward zero, as it always has; it is exact when either
 * operand is a Rational.
 */
public final class RtNum {

    private RtNum() {}

    // ── Int ─────────────────────────────────────────────────────────────

    public static boolean isInt(Object o) {
        return o instanceof Long || o instanceof BigInteger;
    }

    static BigInteger big(Object o) {
        return o instanceof Long l ? BigInteger.valueOf(l) : (BigInteger) o;
    }

    /** The canonical Int for {@code b}: a Long when it fits. */
    public static Object norm(BigInteger b) {
        return b.bitLength() < 64 ? (Object) b.longValue() : b;
    }

    /** An Int from its decimal digits (an optional leading `-`). */
    public static Object parseInt(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return norm(new BigInteger(digits)); // throws NumberFormatException if not digits
        }
    }

    // ── Rational ────────────────────────────────────────────────────────

    /** {@code n/d} in lowest terms: an Int when {@code d} divides {@code n}. */
    public static Object ratio(BigInteger n, BigInteger d) {
        if (d.signum() == 0) throw new IrijRuntimeError("division by zero");
        if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
        BigInteger g = n.gcd(d);
        if (!g.equals(BigInteger.ONE)) { n = n.divide(g); d = d.divide(g); }
        if (d.equals(BigInteger.ONE)) return norm(n);
        return new Values.Rational(n, d);
    }

    /** A rational literal `n/d` (the lexer's RATIONAL token). */
    public static Object ratioLiteral(String n, String d) {
        return ratio(new BigInteger(n), new BigInteger(d));
    }

    private static BigInteger num(Object o) {
        return o instanceof Values.Rational r ? r.num() : big(o);
    }

    private static BigInteger den(Object o) {
        return o instanceof Values.Rational r ? r.den() : BigInteger.ONE;
    }

    /** True when the operands combine exactly (Ints and Rationals only). */
    private static boolean exact(Object a, Object b) {
        return (isInt(a) || a instanceof Values.Rational) && (isInt(b) || b instanceof Values.Rational);
    }

    // ── Float view ──────────────────────────────────────────────────────

    /** The double closest to a number; throws for a non-number. */
    public static double toDouble(Object v, String op) {
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l;
        if (v instanceof BigInteger b) return b.doubleValue();
        if (v instanceof Values.Rational r) return r.toDouble();
        if (v instanceof Number n) return n.doubleValue(); // Java interop values
        throw new IrijRuntimeError(op + " expects a number, got " + RuntimeSupport.typeTag(v));
    }

    // ── Arithmetic (slow paths; RtOps holds the Long fast paths) ────────

    public static Object add(Object a, Object b) {
        if (isInt(a) && isInt(b)) return norm(big(a).add(big(b)));
        if (exact(a, b)) return ratio(num(a).multiply(den(b)).add(num(b).multiply(den(a))), den(a).multiply(den(b)));
        return toDouble(a, "+") + toDouble(b, "+");
    }

    public static Object sub(Object a, Object b) {
        if (isInt(a) && isInt(b)) return norm(big(a).subtract(big(b)));
        if (exact(a, b)) return ratio(num(a).multiply(den(b)).subtract(num(b).multiply(den(a))), den(a).multiply(den(b)));
        return toDouble(a, "-") - toDouble(b, "-");
    }

    public static Object mul(Object a, Object b) {
        if (isInt(a) && isInt(b)) return norm(big(a).multiply(big(b)));
        if (exact(a, b)) return ratio(num(a).multiply(num(b)), den(a).multiply(den(b)));
        return toDouble(a, "*") * toDouble(b, "*");
    }

    /** {@code /}: truncating on two Ints, exact with a Rational, Float otherwise. */
    public static Object div(Object a, Object b) {
        if (isInt(a) && isInt(b)) {
            BigInteger d = big(b);
            if (d.signum() == 0) throw new ArithmeticException("division by zero");
            return norm(big(a).divide(d));
        }
        if (exact(a, b)) {
            if (num(b).signum() == 0) throw new ArithmeticException("division by zero");
            return ratio(num(a).multiply(den(b)), den(a).multiply(num(b)));
        }
        double db = toDouble(b, "/");
        if (db == 0.0) throw new ArithmeticException("division by zero");
        return toDouble(a, "/") / db;
    }

    /** {@code %}: remainder with the dividend's sign, as on Longs. */
    public static Object mod(Object a, Object b) {
        if (isInt(a) && isInt(b)) {
            BigInteger d = big(b);
            if (d.signum() == 0) throw new ArithmeticException("division by zero");
            return norm(big(a).remainder(d));
        }
        if (exact(a, b)) {
            if (num(b).signum() == 0) throw new ArithmeticException("division by zero");
            // a - b * trunc(a / b)
            BigInteger q = num(a).multiply(den(b)).divide(den(a).multiply(num(b)));
            return sub(a, mul(b, norm(q)));
        }
        return toDouble(a, "%") % toDouble(b, "%");
    }

    /** {@code quo} — integer quotient, truncating; Ints only. */
    public static Object quo(Object a, Object b) {
        requireInt(a, "quo");
        requireInt(b, "quo");
        if (big(b).signum() == 0) throw new IrijRuntimeError("Division by zero");
        return norm(big(a).divide(big(b)));
    }

    /** {@code rem} — integer remainder (sign of the dividend); Ints only. */
    public static Object rem(Object a, Object b) {
        requireInt(a, "rem");
        requireInt(b, "rem");
        if (big(b).signum() == 0) throw new IrijRuntimeError("Division by zero");
        return norm(big(a).remainder(big(b)));
    }

    private static void requireInt(Object v, String op) {
        if (!isInt(v)) throw new IrijRuntimeError(op + " expects an Int, got " + RuntimeSupport.typeTag(v));
    }

    public static Object abs(Object v) {
        if (v instanceof Long l) return l == Long.MIN_VALUE ? (Object) BigInteger.valueOf(l).negate() : (Object) Math.abs(l);
        if (v instanceof BigInteger b) return norm(b.abs());
        if (v instanceof Values.Rational r) return new Values.Rational(r.num().abs(), r.den());
        if (v instanceof Double d) return Math.abs(d);
        throw new IrijRuntimeError("abs expects a number, got " + RuntimeSupport.typeTag(v));
    }

    /** Exponent bits past which an exact power is refused (a 2^(10^9)
     *  would take minutes and gigabytes). */
    private static final long MAX_POW_BITS = 16_000_000L;

    /** {@code **}: exact for an Int or Rational base and a non-negative
     *  Int exponent; a Float otherwise. */
    public static Object pow(Object a, Object b) {
        if ((isInt(a) || a instanceof Values.Rational) && isInt(b) && big(b).signum() >= 0) {
            BigInteger e = big(b);
            long bits = Math.max(num(a).bitLength(), den(a).bitLength());
            if (bits > 1 && (e.bitLength() > 31 || bits * e.longValue() > MAX_POW_BITS)) {
                throw new IrijRuntimeError("**: result too large (" + bits + "-bit base to the power " + e + ")");
            }
            int n = e.intValue();
            if (a instanceof Values.Rational r) return ratio(r.num().pow(n), r.den().pow(n));
            return norm(big(a).pow(n));
        }
        return Math.pow(toDouble(a, "**"), toDouble(b, "**"));
    }

    // ── Rounding to Int ─────────────────────────────────────────────────

    public static Object floor(Object v) {
        if (isInt(v)) return v;
        if (v instanceof Values.Rational r) return norm(floorDiv(r.num(), r.den()));
        return fromDouble(Math.floor(toDouble(v, "floor")), "floor");
    }

    public static Object ceil(Object v) {
        if (isInt(v)) return v;
        if (v instanceof Values.Rational r) return norm(floorDiv(r.num(), r.den()).add(BigInteger.ONE));
        return fromDouble(Math.ceil(toDouble(v, "ceil")), "ceil");
    }

    /** Half up, like {@code Math.round}. */
    public static Object round(Object v) {
        if (isInt(v)) return v;
        if (v instanceof Values.Rational r) {
            // floor(n/d + 1/2) = floor((2n + d) / 2d)
            return norm(floorDiv(r.num().shiftLeft(1).add(r.den()), r.den().shiftLeft(1)));
        }
        return fromDouble(Math.floor(toDouble(v, "round") + 0.5), "round");
    }

    private static BigInteger floorDiv(BigInteger n, BigInteger d) {
        BigInteger[] qr = n.divideAndRemainder(d);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static Object fromDouble(double d, String op) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IrijRuntimeError(op + ": " + d + " has no Int value");
        }
        if (d >= -9.223372036854775808E18 && d < 9.223372036854775808E18) return (long) d;
        return norm(new BigDecimal(d).toBigInteger());
    }

    // ── Comparison ──────────────────────────────────────────────────────

    /** Numeric order, or {@code null} when either side isn't a number. */
    public static Integer compare(Object a, Object b) {
        boolean na = isInt(a) || a instanceof Values.Rational || a instanceof Double;
        boolean nb = isInt(b) || b instanceof Values.Rational || b instanceof Double;
        if (!na || !nb) return null;
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        if (isInt(a) && isInt(b)) return big(a).compareTo(big(b));
        if (exact(a, b)) return num(a).multiply(den(b)).compareTo(num(b).multiply(den(a)));
        return Double.compare(toDouble(a, "compare"), toDouble(b, "compare"));
    }

    /** {@code ==} between numbers: exact between Ints and Rationals (whose
     *  canonical forms make that plain equality), by value against a Float. */
    public static boolean numEq(Object a, Object b) {
        if (exact(a, b)) return a.equals(b);
        return toDouble(a, "==") == toDouble(b, "==");
    }

    public static boolean isNumber(Object v) {
        return isInt(v) || v instanceof Double || v instanceof Values.Rational;
    }

    /** The long value of an Int argument used as a count or an index. */
    public static long longArg(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof BigInteger b) {
            throw new IrijRuntimeError(op + ": " + b + " is too large here (beyond 64-bit)");
        }
        if (v instanceof Number n) return n.longValue();
        throw new IrijRuntimeError(op + " expects an Int, got " + RuntimeSupport.typeTag(v));
    }
}
