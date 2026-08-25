package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtCollections domain. */
public final class RtCollections {

    private RtCollections() {}


    // ── Pattern-match helpers ────────────────────────────────────────────

    public static boolean isTag(Object v, String tag) {
        return v instanceof dev.irij.runtime.Values.Tagged t && t.tag().equals(tag);
    }


    public static Object taggedField(Object v, int i) {
        return ((dev.irij.runtime.Values.Tagged) v).fields().get(i);
    }


    public static int taggedArity(Object v) {
        return ((dev.irij.runtime.Values.Tagged) v).fields().size();
    }


    public static boolean isUnit(Object v) {
        return v == null || v == dev.irij.runtime.Values.UNIT;
    }


    public static boolean isKeyword(Object v, String name) {
        return v instanceof dev.irij.runtime.Values.Keyword k && k.name().equals(name);
    }


    public static boolean isVector(Object v) { return v instanceof dev.irij.runtime.Values.IrijVector; }

    public static boolean isTuple(Object v)  { return v instanceof dev.irij.runtime.Values.IrijTuple; }

    public static boolean isMap(Object v)    { return v instanceof dev.irij.runtime.Values.IrijMap; }


    public static int vecSize(Object v) {
        return ((dev.irij.runtime.Values.IrijVector) v).elements().size();
    }


    public static Object vecGet(Object v, int i) {
        return ((dev.irij.runtime.Values.IrijVector) v).elements().get(i);
    }


    public static Object vecSlice(Object v, int from, int to) {
        var es = ((dev.irij.runtime.Values.IrijVector) v).elements();
        return new dev.irij.runtime.Values.IrijVector(new java.util.ArrayList<>(es.subList(from, to)));
    }


    // ── Bytecode-callable raw primitives ────────────────────────────────
    //
    // Irij-facing names — boxed Long indices, Object return. Stdlib Irij
    // functions (fold/map/length/etc., to be ported) call these via the
    // bytecode emitter's emitBuiltinApp dispatch table; users can call
    // them too. Names mirror the interpreter convention: kebab-case.

    /** `length x` — works on String, IrijVector, IrijMap, IrijTuple, null. */
    public static Object length(Object v) {
        if (v == null) return 0L;
        if (v instanceof String s) return (long) s.length();
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            return (long) vec.elements().size();
        }
        if (v instanceof dev.irij.runtime.Values.IrijMap m) {
            return (long) m.entries().size();
        }
        if (v instanceof dev.irij.runtime.Values.IrijTuple t) {
            return (long) t.elements().length;
        }
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            return (long) r.size();
        }
        if (v instanceof dev.irij.runtime.Values.IrijSet s) {
            return (long) s.elements().size();
        }
        throw new dev.irij.IrijRuntimeError(
                "len: expected Str/Vector/Map/Tuple/Range/Set, got " + RuntimeSupport.typeTag(v));
    }


    /** `nth coll i` — element at index (works on Vector or String). */
    /** Irij convention: {@code nth idx coll}. Matches Builtins.nth
     *  arg order (idx is arg-0 in user code, coll is arg-1). Handles
     *  Vector, Tuple, String. */
    public static Object nth(Object iBoxed, Object v) {
        long idx = ((Long) iBoxed).longValue();
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            if (idx < 0 || idx >= vec.elements().size()) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size "
                                + vec.elements().size() + ")");
            }
            return vec.elements().get((int) idx);
        }
        if (v instanceof dev.irij.runtime.Values.IrijTuple tup) {
            if (idx < 0 || idx >= tup.elements().length) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size "
                                + tup.elements().length + ")");
            }
            return tup.elements()[(int) idx];
        }
        if (v instanceof String s) return String.valueOf(s.charAt((int) idx));
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            long size = r.size();
            if (idx < 0 || idx >= size) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size " + size + ")");
            }
            return r.from() + idx;
        }
        throw new dev.irij.IrijRuntimeError(
                "nth: expected Vector / Tuple / Str / Range, got " + RuntimeSupport.typeTag(v));
    }


    /** Coerce a dynamic map-literal key ({@code {(expr)= val}}) to the
     *  String key IrijMap requires. */
    public static String asMapKey(Object v) {
        if (v instanceof String s) return s;
        throw new dev.irij.IrijRuntimeError(
                "map key must evaluate to Str, got " + RuntimeSupport.typeTag(v));
    }

    /** `conj v x` — append x, return new vector (immutable semantics). */
    public static Object conj(Object v, Object x) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            var out = new java.util.ArrayList<>(vec.elements());
            out.add(x);
            return new dev.irij.runtime.Values.IrijVector(out);
        }
        throw new dev.irij.IrijRuntimeError(
                "conj: expected Vector, got " + RuntimeSupport.typeTag(v));
    }


    /** `empty? x` — true if String/Vector/Map/Tuple/Range is empty, or null. */
    public static Object isEmpty(Object v) {
        if (v == null) return Boolean.TRUE;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            return vec.elements().isEmpty();
        }
        if (v instanceof dev.irij.runtime.Values.IrijMap m) {
            return m.entries().isEmpty();
        }
        if (v instanceof dev.irij.runtime.Values.IrijTuple t) {
            return t.elements().length == 0;
        }
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            return r.from() >= upper;
        }
        throw new dev.irij.IrijRuntimeError(
                "empty?: expected Str/Vector/Map/Tuple/Range, got " + RuntimeSupport.typeTag(v));
    }


    /** `fold f init coll` — left fold. Effect-transparent like the
     *  interpreter BuiltinFn: callback runs in the caller's effect row
     *  (no SM continuation, no row-restricted Irij stub). Bytecode
     *  callers get the same semantics they have in the interpreter. */
    public static Object fold(Object fn, Object init, Object coll) {
        java.util.List<Object> list;
        if (coll instanceof dev.irij.runtime.Values.IrijVector vec) {
            list = vec.elements();
        } else if (coll instanceof dev.irij.runtime.Values.IrijRange r) {
            list = new java.util.ArrayList<>();
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            for (long i = r.from(); i < upper; i++) list.add(i);
        } else if (coll instanceof java.util.List<?> raw) {
            @SuppressWarnings("unchecked") var cast = (java.util.List<Object>) raw;
            list = cast;
        } else {
            throw new dev.irij.IrijRuntimeError(
                    "fold: expected Vector/Range/List, got " + RuntimeSupport.typeTag(coll));
        }
        Object acc = init;
        for (Object elem : list) acc = RuntimeSupport.callAny(fn, new Object[]{acc, elem});
        return acc;
    }


    /** `head v` — first element of a non-empty collection. Works on
     *  Vector and IrijRange (so `head (0 ..< 10)` returns 0L). */
    public static Object head(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                throw new dev.irij.IrijRuntimeError("head: empty vector");
            }
            return es.get(0);
        }
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            if (r.from() >= upper) {
                throw new dev.irij.IrijRuntimeError("head: empty range");
            }
            return r.from();
        }
        throw new dev.irij.IrijRuntimeError(
                "head: expected Vector or Range, got " + RuntimeSupport.typeTag(v));
    }


    /** `tail v` — collection without first element (immutable). Works
     *  on Vector and IrijRange. */
    public static Object tail(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                return new dev.irij.runtime.Values.IrijVector(new java.util.ArrayList<>());
            }
            return new dev.irij.runtime.Values.IrijVector(
                    new java.util.ArrayList<>(es.subList(1, es.size())));
        }
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            if (r.from() >= upper) return r; // empty stays empty
            return new dev.irij.runtime.Values.IrijRange(
                    r.from() + 1, r.to(), r.exclusive());
        }
        throw new dev.irij.IrijRuntimeError(
                "tail: expected Vector or Range, got " + RuntimeSupport.typeTag(v));
    }


    /** Build an IrijVector from args[from..] — used for lambda rest params. */
    public static Object restVector(Object[] args, int from) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (int i = from; i < args.length; i++) out.add(args[i]);
        return new dev.irij.runtime.Values.IrijVector(out);
    }


    public static int tupleSize(Object v) {
        return ((dev.irij.runtime.Values.IrijTuple) v).elements().length;
    }


    public static Object tupleGet(Object v, int i) {
        return ((dev.irij.runtime.Values.IrijTuple) v).elements()[i];
    }


    public static Object mapGet(Object v, String k) {
        return ((dev.irij.runtime.Values.IrijMap) v).entries().get(k);
    }


    public static boolean mapHas(Object v, String k) {
        return ((dev.irij.runtime.Values.IrijMap) v).entries().containsKey(k);
    }


    /** Field lookup across IrijMap and Tagged-with-named-fields. */
    public static boolean recordHas(Object v, String k) {
        if (v instanceof dev.irij.runtime.Values.IrijMap m) return m.entries().containsKey(k);
        if (v instanceof dev.irij.runtime.Values.Tagged t && t.namedFields() != null)
            return t.namedFields().containsKey(k);
        return false;
    }


    public static Object recordGet(Object v, String k) {
        if (v instanceof dev.irij.runtime.Values.IrijMap m) return m.entries().get(k);
        if (v instanceof dev.irij.runtime.Values.Tagged t && t.namedFields() != null)
            return t.namedFields().get(k);
        return null;
    }


    public static boolean isRecord(Object v) {
        return v instanceof dev.irij.runtime.Values.IrijMap
                || (v instanceof dev.irij.runtime.Values.Tagged t && t.namedFields() != null);
    }


    public static long asLongArg(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        throw new dev.irij.IrijRuntimeError(
                op + " expects an Int, got " + RuntimeSupport.typeTag(v));
    }


    public static java.util.List<Object> asListAny(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) return vec.elements();
        if (v instanceof dev.irij.runtime.Values.IrijSet set) {
            return new java.util.ArrayList<>(set.elements());
        }
        if (v instanceof dev.irij.runtime.Values.IrijMap map) {
            return new java.util.ArrayList<>(map.entries().values());
        }
        if (v instanceof dev.irij.runtime.Values.IrijTuple tup) {
            return java.util.Arrays.asList(tup.elements());
        }
        if (v instanceof dev.irij.runtime.Values.IrijRange r) {
            java.util.List<Object> out = new java.util.ArrayList<>(r.size());
            for (Object x : r) out.add(x);
            return out;
        }
        if (v instanceof dev.irij.runtime.Builtins.LazyIterable li) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object x : li) out.add(x);
            return out;
        }
        if (v instanceof java.util.List<?> raw) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> cast = (java.util.List<Object>) raw;
            return cast;
        }
        throw new dev.irij.IrijRuntimeError(
                "expected a collection, got " + RuntimeSupport.typeTag(v));
    }


    // ── Map / collection ───────────────────────────────────────────────

    public static Object getOp(Object key, Object coll) {
        if (coll instanceof dev.irij.runtime.Values.IrijMap map) {
            Object v = map.entries().get(dev.irij.runtime.Values.toIrijString(key));
            return v != null ? v : dev.irij.runtime.Values.UNIT;
        }
        if (coll instanceof dev.irij.runtime.Values.IrijVector vec) {
            long idx = asLongArg(key, "get");
            if (idx < 0 || idx >= vec.elements().size()) return dev.irij.runtime.Values.UNIT;
            return vec.elements().get((int) idx);
        }
        if (coll instanceof dev.irij.runtime.Values.IrijTuple tup) {
            long idx = asLongArg(key, "get");
            if (idx < 0 || idx >= tup.elements().length) return dev.irij.runtime.Values.UNIT;
            return tup.elements()[(int) idx];
        }
        throw new dev.irij.IrijRuntimeError(
                "get expects a Map, Vector, or Tuple as second argument");
    }


    public static Object assoc(Object m, Object key, Object val) {
        if (m instanceof dev.irij.runtime.Values.IrijMap map) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(map.entries());
            entries.put(dev.irij.runtime.Values.toIrijString(key), val);
            return new dev.irij.runtime.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "assoc expects a Map as first argument, got " + RuntimeSupport.typeTag(m));
    }


    public static Object dissoc(Object m, Object key) {
        if (m instanceof dev.irij.runtime.Values.IrijMap map) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(map.entries());
            entries.remove(dev.irij.runtime.Values.toIrijString(key));
            return new dev.irij.runtime.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "dissoc expects a Map as first argument, got " + RuntimeSupport.typeTag(m));
    }


    public static Object merge(Object a, Object b) {
        if (a instanceof dev.irij.runtime.Values.IrijMap m1
                && b instanceof dev.irij.runtime.Values.IrijMap m2) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(m1.entries());
            entries.putAll(m2.entries());
            return new dev.irij.runtime.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "merge expects two Maps, got " + RuntimeSupport.typeTag(a) + " and " + RuntimeSupport.typeTag(b));
    }


    public static Object keys(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijMap map) {
            return new dev.irij.runtime.Values.IrijVector(
                    new java.util.ArrayList<>(map.entries().keySet()));
        }
        throw new dev.irij.IrijRuntimeError("keys expects a Map");
    }


    public static Object vals(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijMap map) {
            return new dev.irij.runtime.Values.IrijVector(
                    new java.util.ArrayList<>(map.entries().values()));
        }
        throw new dev.irij.IrijRuntimeError("vals expects a Map");
    }


    public static Object containsP(Object coll, Object elem) {
        if (coll instanceof dev.irij.runtime.Values.IrijVector vec) {
            return vec.elements().contains(elem);
        }
        if (coll instanceof dev.irij.runtime.Values.IrijSet set) {
            return set.elements().contains(elem);
        }
        if (coll instanceof dev.irij.runtime.Values.IrijMap map) {
            return map.entries().containsKey(
                    dev.irij.runtime.Values.toIrijString(elem));
        }
        throw new dev.irij.IrijRuntimeError(
                "contains? expects a collection, got " + RuntimeSupport.typeTag(coll));
    }


    public static Object last(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            if (vec.elements().isEmpty()) {
                throw new dev.irij.IrijRuntimeError("last of empty vector");
            }
            return vec.elements().get(vec.elements().size() - 1);
        }
        throw new dev.irij.IrijRuntimeError(
                "last expects a Vector, got " + RuntimeSupport.typeTag(v));
    }


    public static Object toVec(Object v) {
        return new dev.irij.runtime.Values.IrijVector(asListAny(v));
    }


    /** `to-set coll` — the collection's elements as a Set. Duplicates
     *  collapse; order is not preserved, since a Set has none. This is
     *  the only way to build a Set of dynamic arity: `conj` is
     *  Vector-only and `#{}` is a literal. */
    public static Object toSet(Object v) {
        return new dev.irij.runtime.Values.IrijSet(
                new java.util.LinkedHashSet<>(asListAny(v)));
    }


    /** `to-tuple coll` — the collection's elements as a Tuple, in order.
     *  The dynamic-arity counterpart of the `#(...)` literal. */
    public static Object toTuple(Object v) {
        return new dev.irij.runtime.Values.IrijTuple(asListAny(v).toArray());
    }


    /** Construct an IrijRange. Args are Long-typed; `exclusive`
     *  controls whether the upper bound is included (`0 ..< 10` is
     *  exclusive, `0 .. 10` is inclusive). Used by the bytecode
     *  emitter's `Expr.Range` lowering. */
    public static Object rangeOf(Object fromArg, Object toArg, boolean exclusive) {
        if (!(fromArg instanceof Long lf) || !(toArg instanceof Long lt)) {
            throw new dev.irij.IrijRuntimeError(
                    "Range requires Int endpoints, got "
                            + RuntimeSupport.typeTag(fromArg) + " .. " + RuntimeSupport.typeTag(toArg));
        }
        return new dev.irij.runtime.Values.IrijRange(lf, lt, exclusive);
    }


    // ── Vec ops not yet emitted ──────────────────────────────────────

    public static Object reverseVal(Object v) {
        if (v instanceof dev.irij.runtime.Values.IrijVector vec) {
            java.util.List<Object> rev = new java.util.ArrayList<>(vec.elements());
            java.util.Collections.reverse(rev);
            return new dev.irij.runtime.Values.IrijVector(rev);
        }
        if (v instanceof String s) return new StringBuilder(s).reverse().toString();
        throw new dev.irij.IrijRuntimeError(
                "reverse expects Vector or Str, got " + RuntimeSupport.typeTag(v));
    }

    public static Object sortVal(Object v) {
        if (!(v instanceof dev.irij.runtime.Values.IrijVector vec)) {
            throw new dev.irij.IrijRuntimeError(
                    "sort expects Vector, got " + RuntimeSupport.typeTag(v));
        }
        java.util.List<Object> out = new java.util.ArrayList<>(vec.elements());
        out.sort((a, b) -> RtOps.cmp(a, b));
        return new dev.irij.runtime.Values.IrijVector(out);
    }

    public static Object takeVal(Object nArg, Object collArg) {
        long n = asLongArg(nArg, "take");
        java.util.List<Object> list = asListAny(collArg);
        return new dev.irij.runtime.Values.IrijVector(
                new java.util.ArrayList<>(list.subList(0, (int) Math.min(n, list.size()))));
    }

    public static Object dropVal(Object nArg, Object collArg) {
        long n = asLongArg(nArg, "drop");
        java.util.List<Object> list = asListAny(collArg);
        return new dev.irij.runtime.Values.IrijVector(
                new java.util.ArrayList<>(list.subList((int) Math.min(n, list.size()), list.size())));
    }


    // ── Sequence ops (Phase R3 batch 5) ──────────────────────────────
    //
    // The Irij sequence operators (`@`, `/?`, `/!`, `@i`, `/^`, `/$`,
    // `/+`, `/*`, `/#`, `/&`, `/|`) are kebab-cased into camelCase here.
    // Each one has two emit forms:
    //   - directly applied (`coll |> /+` → `seqSum(coll)`)
    //   - partially applied (`@ f` → an IrijFn that takes coll)
    //
    // The interpreter implements all of these in evalSeqOp; this is the
    // bytecode counterpart with identical semantics.

    private static java.util.List<Object> seqList(Object v) {
        return asListAny(v);
    }


    public static Object seqMap(Object f, Object coll) {
        java.util.List<Object> in = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>(in.size());
        for (Object x : in) out.add(RuntimeSupport.callAny(f, new Object[]{x}));
        return new dev.irij.runtime.Values.IrijVector(out);
    }


    public static Object seqMapIndexed(Object f, Object coll) {
        java.util.List<Object> in = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            out.add(RuntimeSupport.callAny(f, new Object[]{(long) i, in.get(i)}));
        }
        return new dev.irij.runtime.Values.IrijVector(out);
    }


    public static Object seqFilter(Object pred, Object coll) {
        java.util.List<Object> in = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Object x : in) {
            if (RtOps.truthy(RuntimeSupport.callAny(pred, new Object[]{x}))) out.add(x);
        }
        return new dev.irij.runtime.Values.IrijVector(out);
    }


    public static Object seqFindFirst(Object pred, Object coll) {
        for (Object x : seqList(coll)) {
            if (RtOps.truthy(RuntimeSupport.callAny(pred, new Object[]{x}))) return x;
        }
        return dev.irij.runtime.Values.UNIT;
    }


    public static Object seqReduce(Object f, Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            acc = RuntimeSupport.callAny(f, new Object[]{acc, list.get(i)});
        }
        return acc;
    }


    public static Object seqScan(Object f, Object coll) {
        java.util.List<Object> list = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>(list.size());
        if (list.isEmpty()) return new dev.irij.runtime.Values.IrijVector(out);
        Object acc = list.get(0);
        out.add(acc);
        for (int i = 1; i < list.size(); i++) {
            acc = RuntimeSupport.callAny(f, new Object[]{acc, list.get(i)});
            out.add(acc);
        }
        return new dev.irij.runtime.Values.IrijVector(out);
    }


    public static Object seqSum(Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) acc = RtOps.add(acc, list.get(i));
        return acc;
    }


    public static Object seqProduct(Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) acc = RtOps.mul(acc, list.get(i));
        return acc;
    }


    public static Object seqCount(Object coll) {
        return (long) seqList(coll).size();
    }


    public static Object seqAll(Object coll) {
        for (Object x : seqList(coll)) if (!RtOps.truthy(x)) return Boolean.FALSE;
        return Boolean.TRUE;
    }


    public static Object seqAny(Object coll) {
        for (Object x : seqList(coll)) if (RtOps.truthy(x)) return Boolean.TRUE;
        return Boolean.FALSE;
    }


    // ── SeqOp as values (partial application) ────────────────────────
    //
    // Emitted when a SeqOp expression appears in value position (e.g.
    // `cards |> @ f` lowers to `(SeqOp(@, f))(cards)`). The result is
    // an IrijFn awaiting the collection.

    public static RuntimeSupport.IrijFn seqMapPartial(Object f)      { return args -> seqMap(f, args[0]); }

    public static RuntimeSupport.IrijFn seqMapIndexedPartial(Object f) { return args -> seqMapIndexed(f, args[0]); }

    public static RuntimeSupport.IrijFn seqFilterPartial(Object p)   { return args -> seqFilter(p, args[0]); }

    public static RuntimeSupport.IrijFn seqFindFirstPartial(Object p) { return args -> seqFindFirst(p, args[0]); }

    public static RuntimeSupport.IrijFn seqReducePartial(Object f)   { return args -> seqReduce(f, args[0]); }

    public static RuntimeSupport.IrijFn seqScanPartial(Object f)     { return args -> seqScan(f, args[0]); }


    // SeqOps that don't take a captured fn — single shared instances.
    public static final RuntimeSupport.IrijFn SEQ_SUM     = args -> seqSum(args[0]);

    public static final RuntimeSupport.IrijFn SEQ_PRODUCT = args -> seqProduct(args[0]);

    public static final RuntimeSupport.IrijFn SEQ_COUNT   = args -> seqCount(args[0]);

    public static final RuntimeSupport.IrijFn SEQ_ALL     = args -> seqAll(args[0]);

    public static final RuntimeSupport.IrijFn SEQ_ANY     = args -> seqAny(args[0]);
}
