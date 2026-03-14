package dev.irij.interpreter;

import dev.irij.ast.Node.SourceLoc;
import dev.irij.interpreter.Values.*;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * All built-in functions and global bindings.
 */
public final class Builtins {

    private Builtins() {}

    /** Install all builtins into the given environment. */
    public static void install(Environment env, PrintStream out) {
        // Boolean constants
        env.define("true", Boolean.TRUE);
        env.define("false", Boolean.FALSE);

        // ── I/O ─────────────────────────────────────────────────────────
        env.define("print", new BuiltinFn("print", 1, args -> {
            out.print(Values.toIrijString(args.get(0)));
            return Values.UNIT;
        }));

        env.define("println", new BuiltinFn("println", 1, args -> {
            out.println(Values.toIrijString(args.get(0)));
            return Values.UNIT;
        }));

        env.define("dbg", new BuiltinFn("dbg", 1, args -> {
            var v = args.get(0);
            out.println("[dbg] " + Values.typeName(v) + ": " + Values.toIrijString(v));
            return v;
        }));

        env.define("to-str", new BuiltinFn("to-str", 1, args -> {
            return Values.toIrijString(args.get(0));
        }));

        // ── Arithmetic ──────────────────────────────────────────────────
        env.define("div", new BuiltinFn("div", 2, args -> {
            long a = asLong(args.get(0), "div");
            long b = asLong(args.get(1), "div");
            if (b == 0) throw new IrijRuntimeError("Division by zero");
            return a / b;
        }));

        env.define("mod", new BuiltinFn("mod", 2, args -> {
            long a = asLong(args.get(0), "mod");
            long b = asLong(args.get(1), "mod");
            if (b == 0) throw new IrijRuntimeError("Division by zero");
            return a % b;
        }));

        env.define("abs", new BuiltinFn("abs", 1, args -> {
            var v = args.get(0);
            if (v instanceof Long l) return Math.abs(l);
            if (v instanceof Double d) return Math.abs(d);
            throw new IrijRuntimeError("abs expects a number, got " + Values.typeName(v));
        }));

        env.define("min", new BuiltinFn("min", 2, args -> {
            return compare(args.get(0), args.get(1)) <= 0 ? args.get(0) : args.get(1);
        }));

        env.define("max", new BuiltinFn("max", 2, args -> {
            return compare(args.get(0), args.get(1)) >= 0 ? args.get(0) : args.get(1);
        }));

        env.define("pi", Math.PI);
        env.define("e", Math.E);

        // ── Collection ──────────────────────────────────────────────────
        env.define("head", new BuiltinFn("head", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("head of empty vector");
                return vec.elements().get(0);
            }
            throw new IrijRuntimeError("head expects a Vector, got " + Values.typeName(v));
        }));

        env.define("tail", new BuiltinFn("tail", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("tail of empty vector");
                return new IrijVector(vec.elements().subList(1, vec.elements().size()));
            }
            throw new IrijRuntimeError("tail expects a Vector, got " + Values.typeName(v));
        }));

        env.define("length", new BuiltinFn("length", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) return (long) vec.elements().size();
            if (v instanceof String s) return (long) s.length();
            if (v instanceof IrijMap m) return (long) m.entries().size();
            if (v instanceof IrijSet s) return (long) s.elements().size();
            if (v instanceof IrijTuple t) return (long) t.elements().length;
            if (v instanceof IrijRange r) return (long) r.size();
            throw new IrijRuntimeError("length expects a collection, got " + Values.typeName(v));
        }));

        env.define("reverse", new BuiltinFn("reverse", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                var rev = new ArrayList<>(vec.elements());
                Collections.reverse(rev);
                return new IrijVector(rev);
            }
            if (v instanceof String s) return new StringBuilder(s).reverse().toString();
            throw new IrijRuntimeError("reverse expects a Vector or Str, got " + Values.typeName(v));
        }));

        env.define("sort", new BuiltinFn("sort", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                var sorted = new ArrayList<>(vec.elements());
                sorted.sort((a, b) -> compare(a, b));
                return new IrijVector(sorted);
            }
            throw new IrijRuntimeError("sort expects a Vector, got " + Values.typeName(v));
        }));

        env.define("concat", new BuiltinFn("concat", 2, args -> {
            return concatValues(args.get(0), args.get(1));
        }));

        env.define("take", new BuiltinFn("take", 2, args -> {
            long n = asLong(args.get(0), "take");
            var v = args.get(1);
            var list = toList(v);
            return new IrijVector(list.subList(0, (int) Math.min(n, list.size())));
        }));

        env.define("drop", new BuiltinFn("drop", 2, args -> {
            long n = asLong(args.get(0), "drop");
            var v = args.get(1);
            var list = toList(v);
            return new IrijVector(list.subList((int) Math.min(n, list.size()), list.size()));
        }));

        env.define("to-vec", new BuiltinFn("to-vec", 1, args -> {
            return new IrijVector(toList(args.get(0)));
        }));

        env.define("contains?", new BuiltinFn("contains?", 2, args -> {
            var coll = args.get(0);
            var elem = args.get(1);
            if (coll instanceof IrijVector vec) return vec.elements().contains(elem);
            if (coll instanceof IrijSet set) return set.elements().contains(elem);
            if (coll instanceof IrijMap map) return map.entries().containsKey(Values.toIrijString(elem));
            throw new IrijRuntimeError("contains? expects a collection, got " + Values.typeName(coll));
        }));

        env.define("keys", new BuiltinFn("keys", 1, args -> {
            if (args.get(0) instanceof IrijMap map) {
                return new IrijVector(new ArrayList<>(map.entries().keySet()));
            }
            throw new IrijRuntimeError("keys expects a Map");
        }));

        env.define("vals", new BuiltinFn("vals", 1, args -> {
            if (args.get(0) instanceof IrijMap map) {
                return new IrijVector(new ArrayList<>(map.entries().values()));
            }
            throw new IrijRuntimeError("vals expects a Map");
        }));

        env.define("get", new BuiltinFn("get", 2, args -> {
            var key = args.get(0);
            var coll = args.get(1);
            if (coll instanceof IrijMap map) {
                var v = map.entries().get(Values.toIrijString(key));
                return v != null ? v : Values.UNIT;
            }
            if (coll instanceof IrijVector vec) {
                long idx = asLong(key, "get");
                if (idx < 0 || idx >= vec.elements().size()) return Values.UNIT;
                return vec.elements().get((int) idx);
            }
            if (coll instanceof IrijTuple tup) {
                long idx = asLong(key, "get");
                if (idx < 0 || idx >= tup.elements().length) return Values.UNIT;
                return tup.elements()[(int) idx];
            }
            throw new IrijRuntimeError("get expects a Map, Vector, or Tuple as second argument");
        }));

        env.define("nth", new BuiltinFn("nth", 2, args -> {
            long idx = asLong(args.get(0), "nth");
            var coll = args.get(1);
            if (coll instanceof IrijVector vec) {
                if (idx < 0 || idx >= vec.elements().size())
                    throw new IrijRuntimeError("nth: index " + idx + " out of bounds (size " + vec.elements().size() + ")");
                return vec.elements().get((int) idx);
            }
            if (coll instanceof IrijTuple tup) {
                if (idx < 0 || idx >= tup.elements().length)
                    throw new IrijRuntimeError("nth: index " + idx + " out of bounds (size " + tup.elements().length + ")");
                return tup.elements()[(int) idx];
            }
            throw new IrijRuntimeError("nth expects a Vector or Tuple, got " + Values.typeName(coll));
        }));

        env.define("last", new BuiltinFn("last", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("last of empty vector");
                return vec.elements().get(vec.elements().size() - 1);
            }
            throw new IrijRuntimeError("last expects a Vector, got " + Values.typeName(v));
        }));


        // ── Functional ──────────────────────────────────────────────────
        env.define("identity", new BuiltinFn("identity", 1, args -> args.get(0)));
        env.define("const", new BuiltinFn("const", 2, args -> args.get(0)));
        env.define("flip", new BuiltinFn("flip", 3, args -> {
            // flip f a b = f b a
            // This is tricky — we need to return a partially applied version
            // For now: flip f returns a function that takes (a, b) and calls f(b, a)
            throw new IrijRuntimeError("flip requires partial application support");
        }));

        env.define("not", new BuiltinFn("not", 1, args -> {
            return !Values.isTruthy(args.get(0));
        }));

        env.define("empty?", new BuiltinFn("empty?", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) return vec.elements().isEmpty();
            if (v instanceof IrijSet set) return set.elements().isEmpty();
            if (v instanceof IrijMap map) return map.entries().isEmpty();
            if (v instanceof String s) return s.isEmpty();
            return false;
        }));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    static long asLong(Object value, String context) {
        if (value instanceof Long l) return l;
        throw new IrijRuntimeError(context + " expects Int, got " + Values.typeName(value));
    }

    static int compare(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        if (a instanceof Double da && b instanceof Double db) return Double.compare(da, db);
        if (a instanceof Long la && b instanceof Double db) return Double.compare(la, db);
        if (a instanceof Double da && b instanceof Long lb) return Double.compare(da, lb);
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Keyword ka && b instanceof Keyword kb) return ka.name().compareTo(kb.name());
        throw new IrijRuntimeError("Cannot compare " + Values.typeName(a) + " and " + Values.typeName(b));
    }

    static Object concatValues(Object a, Object b) {
        if (a instanceof IrijVector va && b instanceof IrijVector vb) {
            var combined = new ArrayList<>(va.elements());
            combined.addAll(vb.elements());
            return new IrijVector(combined);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa + sb;
        }
        if (a instanceof IrijVector va) {
            var combined = new ArrayList<>(va.elements());
            combined.add(b);
            return new IrijVector(combined);
        }
        throw new IrijRuntimeError("Cannot concat " + Values.typeName(a) + " and " + Values.typeName(b));
    }

    /** Convert any iterable value to a List<Object>. */
    static List<Object> toList(Object value) {
        if (value instanceof IrijVector vec) return new ArrayList<>(vec.elements());
        if (value instanceof IrijSet set) return new ArrayList<>(set.elements());
        if (value instanceof IrijRange range) {
            var list = new ArrayList<Object>();
            for (var e : range) list.add(e);
            return list;
        }
        if (value instanceof LazyIterable li) {
            var list = new ArrayList<Object>();
            for (var e : li) list.add(e);
            return list;
        }
        throw new IrijRuntimeError("Cannot iterate over " + Values.typeName(value));
    }

    /** Get an iterable view of any collection-like value. */
    static Iterable<Object> toIterable(Object value) {
        if (value instanceof IrijVector vec) return vec.elements();
        if (value instanceof IrijSet set) return set.elements();
        if (value instanceof IrijRange range) return range;
        if (value instanceof LazyIterable li) return li;
        throw new IrijRuntimeError("Cannot iterate over " + Values.typeName(value));
    }

    // ── Rational arithmetic ───────────────────────────────────────────────

    static Rational addRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den() + b.num() * a.den(), a.den() * b.den());
    }

    static Rational subRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den() - b.num() * a.den(), a.den() * b.den());
    }

    static Rational mulRational(Rational a, Rational b) {
        return new Rational(a.num() * b.num(), a.den() * b.den());
    }

    static Rational divRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den(), a.den() * b.num());
    }

    // ── Lazy iterable wrappers ──────────────────────────────────────────

    /** A lazy mapped iterable. */
    public record LazyIterable(Iterable<Object> source, java.util.function.Function<Object, Object> transform,
                               java.util.function.Predicate<Object> filter) implements Iterable<Object> {
        /** Map-only constructor. */
        public LazyIterable(Iterable<Object> source, java.util.function.Function<Object, Object> transform) {
            this(source, transform, null);
        }

        /** Filter-only constructor. */
        public LazyIterable(Iterable<Object> source, java.util.function.Predicate<Object> filter, boolean dummy) {
            this(source, null, filter);
        }

        @Override
        public Iterator<Object> iterator() {
            if (transform != null && filter == null) {
                return new Iterator<>() {
                    final Iterator<Object> it = source.iterator();
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public Object next() { return transform.apply(it.next()); }
                };
            }
            if (filter != null && transform == null) {
                return new Iterator<>() {
                    final Iterator<Object> it = source.iterator();
                    Object nextVal;
                    boolean hasNext;
                    { advance(); }
                    private void advance() {
                        while (it.hasNext()) {
                            nextVal = it.next();
                            if (filter.test(nextVal)) { hasNext = true; return; }
                        }
                        hasNext = false;
                    }
                    @Override public boolean hasNext() { return hasNext; }
                    @Override public Object next() {
                        var v = nextVal;
                        advance();
                        return v;
                    }
                };
            }
            // Both map and filter
            return new Iterator<>() {
                final Iterator<Object> it = source.iterator();
                Object nextVal;
                boolean hasNext;
                { advance(); }
                private void advance() {
                    while (it.hasNext()) {
                        var raw = it.next();
                        var mapped = transform != null ? transform.apply(raw) : raw;
                        if (filter == null || filter.test(mapped)) {
                            nextVal = mapped;
                            hasNext = true;
                            return;
                        }
                    }
                    hasNext = false;
                }
                @Override public boolean hasNext() { return hasNext; }
                @Override public Object next() {
                    var v = nextVal;
                    advance();
                    return v;
                }
            };
        }
    }
}
