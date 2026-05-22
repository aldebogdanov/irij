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

    /** Wrap a raw {@link IrijFn} (typically produced by an
     *  invokedynamic-bootstrapped lambda) with arity metadata + the
     *  curry-friendly {@link CurriedFn#apply} logic. Used at every
     *  user-lambda creation site. */
    public static IrijFn curry(IrijFn impl, int arity) {
        return new CurriedFn(impl, arity, new Object[0]);
    }

    /** Curried wrapper: applying it with fewer args than {@code arity}
     *  returns a new {@link CurriedFn} with the partial application
     *  accumulated; applying with exactly {@code arity} args invokes
     *  the underlying impl; applying with more than {@code arity}
     *  invokes the impl with the first arity-many args and passes the
     *  remainder to whatever the impl returned (mirrors the
     *  interpreter's apply()-with-arity semantics). */
    public static final class CurriedFn implements IrijFn {
        final IrijFn impl;
        public final int arity;
        final Object[] captured;

        CurriedFn(IrijFn impl, int arity, Object[] captured) {
            this.impl = impl;
            this.arity = arity;
            this.captured = captured;
        }

        @Override
        public Object apply(Object[] args) {
            // Variadic (arity == -1): pass every arg straight to impl.
            if (arity < 0) {
                if (captured.length == 0) return impl.apply(args);
                Object[] combined = new Object[captured.length + args.length];
                System.arraycopy(captured, 0, combined, 0, captured.length);
                System.arraycopy(args, 0, combined, captured.length, args.length);
                return impl.apply(combined);
            }
            int total = captured.length + args.length;
            if (total < arity) {
                Object[] combined = new Object[total];
                System.arraycopy(captured, 0, combined, 0, captured.length);
                System.arraycopy(args, 0, combined, captured.length, args.length);
                return new CurriedFn(impl, arity, combined);
            }
            int take = arity - captured.length;
            Object[] satisfied = new Object[arity];
            System.arraycopy(captured, 0, satisfied, 0, captured.length);
            System.arraycopy(args, 0, satisfied, captured.length, take);
            Object result = impl.apply(satisfied);
            if (total == arity) return result;
            Object[] rest = new Object[total - arity];
            System.arraycopy(args, take, rest, 0, rest.length);
            return callAny(result, rest);
        }
    }

    // ── Operator sections — (+), (-), (*), etc. as first-class values ──
    //
    // Emitter lowers `Expr.OpSection(op)` to GETSTATIC of one of these
    // constants. Lets users pass operators by name to higher-order fns:
    //   fold (+) 0 #[1 2 3]   ;; sums 6
    public static final IrijFn OP_ADD = args -> add(args[0], args[1]);
    public static final IrijFn OP_SUB = args -> sub(args[0], args[1]);
    public static final IrijFn OP_MUL = args -> mul(args[0], args[1]);
    public static final IrijFn OP_DIV = args -> div(args[0], args[1]);
    public static final IrijFn OP_MOD = args -> mod(args[0], args[1]);
    public static final IrijFn OP_CONCAT = args -> concat(args[0], args[1]);
    public static final IrijFn OP_LT  = args -> Boolean.valueOf(lt(args[0], args[1]));
    public static final IrijFn OP_LE  = args -> Boolean.valueOf(le(args[0], args[1]));
    public static final IrijFn OP_GT  = args -> Boolean.valueOf(gt(args[0], args[1]));
    public static final IrijFn OP_GE  = args -> Boolean.valueOf(ge(args[0], args[1]));
    public static final IrijFn OP_EQ  = args -> Boolean.valueOf(eq(args[0], args[1]));
    public static final IrijFn OP_NEQ = args -> Boolean.valueOf(neq(args[0], args[1]));

    /** Helper for App sites when callee is an expression of unknown type. */
    public static Object callFn(Object fn, Object[] args) {
        return callAny(fn, args);
    }

    // ── Namespace mode (nREPL eval-bytecode cross-eval state) ────────
    //
    // The nREPL session sets `NS` to a per-session map before invoking
    // a compiled eval class's `main`. The emitter, when configured
    // with `namespaceMode=true`, writes top-level `:= name value`
    // bindings into the map via `nsPut`, and routes unresolved Var
    // loads through `nsGet`. Result: a `:= x 5` in one eval, then a
    // `println x` in the next eval, both work — bytecode-compiled.

    /** Per-thread namespace map. nREPL sets this before each
     *  eval-bytecode invocation and shares the same map across all
     *  evals in the session. Inherited by virtual-thread fibers /
     *  spawned tasks so cross-eval fn refs survive into background
     *  work. */
    public static final ThreadLocal<java.util.Map<String, Object>> NS =
            ThreadLocal.withInitial(java.util.HashMap::new);

    /** Per-thread PrintStream override for sandboxed sessions. When
     *  set (non-null), every {@link #sessionPrintln} / {@link
     *  #sessionPrint} call routes there instead of {@code System.out}.
     *  Inherited by spawned virtual threads so a Playground session's
     *  spawn captures its stdout into the session buffer rather than
     *  leaking to the server's process stdout. */
    public static final ThreadLocal<java.io.PrintStream> SESSION_OUT =
            new ThreadLocal<>();

    // (Bytecode effect-row enforcement happens at compile time via
    //  EffectRowChecker.check(decls). No runtime stack needed —
    //  subsumption violations fail the build. See
    //  docs/internals/specs.md for the model.)

    // (Bytecode spec validation lives in SpecValidator — covers all
    //  SpecExpr variants the interpreter does. Emitted call sites pass
    //  an encoded spec string into SpecValidator.validateEncoded; see
    //  docs/internals/specs.md "Bytecode-mode spec validation".)

    /** Look up `name` in the current namespace. Throws if not bound. */
    public static Object nsGet(String name) {
        var ns = NS.get();
        if (!ns.containsKey(name)) {
            throw new dev.irij.IrijRuntimeError(
                    "Unbound variable: " + name);
        }
        return ns.get(name);
    }

    /** Store `name → value` in the current namespace. Returns the
     *  value so call sites can chain (`var := nsPut(...)`). */
    public static Object nsPut(String name, Object value) {
        NS.get().put(name, value);
        return value;
    }

    /**
     * Dispatch a call against any runtime "callable": IrijFn (compiled
     * lambdas), interpreter BuiltinFn (Java interop refs from
     * Class/member or obj.method), or interpreter Closure.
     */
    public static Object callAny(Object fn, Object[] args) {
        // `f ()` calling a zero-arg curried lambda: strip the unit so
        // CurriedFn doesn't think this is an over-application.
        if (fn instanceof CurriedFn cf && cf.arity == 0
                && args.length == 1 && args[0] == dev.irij.interpreter.Values.UNIT) {
            return cf.apply(new Object[0]);
        }
        if (fn instanceof IrijFn f) return f.apply(args);
        if (fn instanceof dev.irij.interpreter.Values.BuiltinFn bf) {
            return bf.apply(java.util.Arrays.asList(args));
        }
        throw new IllegalArgumentException("Not callable: " + display(fn));
    }

    /** Resolve a Java static ref like "System/getenv" → BuiltinFn. */
    public static Object javaStaticRef(String ref) {
        return dev.irij.interpreter.JavaInterop.resolveStaticRef(ref);
    }

    /**
     * Dot-access fallthrough at runtime: dispatch over compile-time unknown
     * targets. Returns handler-state/closure fields if applicable, else
     * defers to JavaInterop.resolveInstanceRef.
     */
    public static Object javaInstanceRef(Object recv, String member) {
        if (recv == null) {
            throw new IllegalArgumentException("Cannot access ." + member + " on null");
        }
        // Match Interpreter.evalDotAccess: check Irij record-shaped
        // values first so `map.key` and `tagged.field` work in
        // bytecode mode without falling through to JavaInterop.
        if (recv instanceof dev.irij.interpreter.Values.IrijMap m) {
            Object v = m.entries().get(member);
            return v != null ? v : dev.irij.interpreter.Values.UNIT;
        }
        if (recv instanceof dev.irij.interpreter.Values.Tagged t
                && t.namedFields() != null) {
            Object v = t.namedFields().get(member);
            if (v != null) return v;
            throw new dev.irij.IrijRuntimeError(
                    "No field '" + member + "' on " + t.tag());
        }
        return dev.irij.interpreter.JavaInterop.resolveInstanceRef(recv, member);
    }

    public static void print(Object v) {
        java.io.PrintStream out = SESSION_OUT.get();
        if (out == null) out = System.out;
        out.print(display(v));
    }

    public static void println(Object v) {
        java.io.PrintStream out = SESSION_OUT.get();
        if (out == null) out = System.out;
        out.println(display(v));
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
        // Delegate to the canonical comparator in Builtins — handles
        // Long/Double/String/Keyword/Tuple/Vector recursively.
        return dev.irij.interpreter.Builtins.compare(a, b);
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
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            return (long) vec.elements().size();
        }
        if (v instanceof dev.irij.interpreter.Values.IrijMap m) {
            return (long) m.entries().size();
        }
        if (v instanceof dev.irij.interpreter.Values.IrijTuple t) {
            return (long) t.elements().length;
        }
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            return (long) r.size();
        }
        if (v instanceof dev.irij.interpreter.Values.IrijSet s) {
            return (long) s.elements().size();
        }
        throw new dev.irij.IrijRuntimeError(
                "len: expected Str/Vector/Map/Tuple/Range/Set, got " + typeTag(v));
    }

    /** `nth coll i` — element at index (works on Vector or String). */
    /** Irij convention: {@code nth idx coll}. Matches Builtins.nth
     *  arg order (idx is arg-0 in user code, coll is arg-1). Handles
     *  Vector, Tuple, String. */
    public static Object nth(Object iBoxed, Object v) {
        long idx = ((Long) iBoxed).longValue();
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            if (idx < 0 || idx >= vec.elements().size()) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size "
                                + vec.elements().size() + ")");
            }
            return vec.elements().get((int) idx);
        }
        if (v instanceof dev.irij.interpreter.Values.IrijTuple tup) {
            if (idx < 0 || idx >= tup.elements().length) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size "
                                + tup.elements().length + ")");
            }
            return tup.elements()[(int) idx];
        }
        if (v instanceof String s) return String.valueOf(s.charAt((int) idx));
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            long size = r.size();
            if (idx < 0 || idx >= size) {
                throw new dev.irij.IrijRuntimeError(
                        "nth: index " + idx + " out of bounds (size " + size + ")");
            }
            return r.from() + idx;
        }
        throw new dev.irij.IrijRuntimeError(
                "nth: expected Vector / Tuple / Str / Range, got " + typeTag(v));
    }

    /** `conj v x` — append x, return new vector (immutable semantics). */
    public static Object conj(Object v, Object x) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var out = new java.util.ArrayList<>(vec.elements());
            out.add(x);
            return new dev.irij.interpreter.Values.IrijVector(out);
        }
        throw new dev.irij.IrijRuntimeError(
                "conj: expected Vector, got " + typeTag(v));
    }

    /** `empty? x` — true if String/Vector/Map/Tuple/Range is empty, or null. */
    public static Object isEmpty(Object v) {
        if (v == null) return Boolean.TRUE;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            return vec.elements().isEmpty();
        }
        if (v instanceof dev.irij.interpreter.Values.IrijMap m) {
            return m.entries().isEmpty();
        }
        if (v instanceof dev.irij.interpreter.Values.IrijTuple t) {
            return t.elements().length == 0;
        }
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            return r.from() >= upper;
        }
        throw new dev.irij.IrijRuntimeError(
                "empty?: expected Str/Vector/Map/Tuple/Range, got " + typeTag(v));
    }

    /** `fold f init coll` — left fold. Effect-transparent like the
     *  interpreter BuiltinFn: callback runs in the caller's effect row
     *  (no SM continuation, no row-restricted Irij stub). Bytecode
     *  callers get the same semantics they have in the interpreter. */
    public static Object fold(Object fn, Object init, Object coll) {
        java.util.List<Object> list;
        if (coll instanceof dev.irij.interpreter.Values.IrijVector vec) {
            list = vec.elements();
        } else if (coll instanceof dev.irij.interpreter.Values.IrijRange r) {
            list = new java.util.ArrayList<>();
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            for (long i = r.from(); i < upper; i++) list.add(i);
        } else if (coll instanceof java.util.List<?> raw) {
            @SuppressWarnings("unchecked") var cast = (java.util.List<Object>) raw;
            list = cast;
        } else {
            throw new dev.irij.IrijRuntimeError(
                    "fold: expected Vector/Range/List, got " + typeTag(coll));
        }
        Object acc = init;
        for (Object elem : list) acc = callAny(fn, new Object[]{acc, elem});
        return acc;
    }

    /** `head v` — first element of a non-empty collection. Works on
     *  Vector and IrijRange (so `head (0 ..< 10)` returns 0L). */
    public static Object head(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                throw new dev.irij.IrijRuntimeError("head: empty vector");
            }
            return es.get(0);
        }
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            if (r.from() >= upper) {
                throw new dev.irij.IrijRuntimeError("head: empty range");
            }
            return r.from();
        }
        throw new dev.irij.IrijRuntimeError(
                "head: expected Vector or Range, got " + typeTag(v));
    }

    /** `tail v` — collection without first element (immutable). Works
     *  on Vector and IrijRange. */
    public static Object tail(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                return new dev.irij.interpreter.Values.IrijVector(new java.util.ArrayList<>());
            }
            return new dev.irij.interpreter.Values.IrijVector(
                    new java.util.ArrayList<>(es.subList(1, es.size())));
        }
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            long upper = r.exclusive() ? r.to() : r.to() + 1;
            if (r.from() >= upper) return r; // empty stays empty
            return new dev.irij.interpreter.Values.IrijRange(
                    r.from() + 1, r.to(), r.exclusive());
        }
        throw new dev.irij.IrijRuntimeError(
                "tail: expected Vector or Range, got " + typeTag(v));
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

    // ── Heavy-hitter builtins ported from Builtins.java (Phase R3) ──────
    //
    // These exist so `irij build --mode=bytecode-sm` can compile real
    // programs without falling through to an interpreter-installed
    // BuiltinFn. Semantics match Interpreter exactly (same coercions,
    // same error messages). Names are Java-friendly; the emitter maps
    // Irij names like `replace` / `index-of` / `contains?` to these.

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new dev.irij.IrijRuntimeError(
                op + " expects a String, got " + typeTag(v));
    }

    private static long asLongArg(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        throw new dev.irij.IrijRuntimeError(
                op + " expects an Int, got " + typeTag(v));
    }

    private static java.util.List<Object> asListAny(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) return vec.elements();
        if (v instanceof dev.irij.interpreter.Values.IrijSet set) {
            return new java.util.ArrayList<>(set.elements());
        }
        if (v instanceof dev.irij.interpreter.Values.IrijMap map) {
            return new java.util.ArrayList<>(map.entries().values());
        }
        if (v instanceof dev.irij.interpreter.Values.IrijTuple tup) {
            return java.util.Arrays.asList(tup.elements());
        }
        if (v instanceof dev.irij.interpreter.Values.IrijRange r) {
            java.util.List<Object> out = new java.util.ArrayList<>(r.size());
            for (Object x : r) out.add(x);
            return out;
        }
        if (v instanceof dev.irij.interpreter.Builtins.LazyIterable li) {
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
                "expected a collection, got " + typeTag(v));
    }

    // ── Strings ────────────────────────────────────────────────────────

    public static Object replace(Object str, Object from, Object to) {
        return asStr(str, "replace").replace(
                asStr(from, "replace"), asStr(to, "replace"));
    }

    public static Object substring(Object str, Object startArg, Object endArg) {
        String s = asStr(str, "substring");
        int start = (int) asLongArg(startArg, "substring");
        int end = (int) asLongArg(endArg, "substring");
        if (start < 0 || end > s.length() || start > end) {
            throw new dev.irij.IrijRuntimeError(
                    "substring: index out of bounds (start=" + start
                            + ", end=" + end + ", length=" + s.length() + ")");
        }
        return s.substring(start, end);
    }

    public static Object split(Object str, Object sep) {
        String s = asStr(str, "split");
        String sp = asStr(sep, "split");
        java.util.List<Object> parts = new java.util.ArrayList<>();
        if (sp.isEmpty()) {
            for (int i = 0; i < s.length(); i++) parts.add(String.valueOf(s.charAt(i)));
        } else {
            for (String p : s.split(java.util.regex.Pattern.quote(sp), -1)) parts.add(p);
        }
        return new dev.irij.interpreter.Values.IrijVector(parts);
    }

    public static Object join(Object sep, Object coll) {
        String sp = asStr(sep, "join");
        java.util.List<Object> list = asListAny(coll);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sp);
            sb.append(dev.irij.interpreter.Values.toIrijString(list.get(i)));
        }
        return sb.toString();
    }

    public static Object trimStr(Object v) {
        return asStr(v, "trim").strip();
    }

    public static Object upperCase(Object v) {
        return asStr(v, "upper-case").toUpperCase();
    }

    public static Object lowerCase(Object v) {
        return asStr(v, "lower-case").toLowerCase();
    }

    public static Object startsWithP(Object str, Object prefix) {
        return asStr(str, "starts-with?").startsWith(asStr(prefix, "starts-with?"));
    }

    public static Object endsWithP(Object str, Object suffix) {
        return asStr(str, "ends-with?").endsWith(asStr(suffix, "ends-with?"));
    }

    public static Object indexOf(Object str, Object sub) {
        return (long) asStr(str, "index-of").indexOf(asStr(sub, "index-of"));
    }

    public static Object urlEncode(Object s) {
        return java.net.URLEncoder.encode(asStr(s, "url-encode"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Object urlDecode(Object s) {
        return java.net.URLDecoder.decode(asStr(s, "url-decode"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Map / collection ───────────────────────────────────────────────

    public static Object getOp(Object key, Object coll) {
        if (coll instanceof dev.irij.interpreter.Values.IrijMap map) {
            Object v = map.entries().get(dev.irij.interpreter.Values.toIrijString(key));
            return v != null ? v : dev.irij.interpreter.Values.UNIT;
        }
        if (coll instanceof dev.irij.interpreter.Values.IrijVector vec) {
            long idx = asLongArg(key, "get");
            if (idx < 0 || idx >= vec.elements().size()) return dev.irij.interpreter.Values.UNIT;
            return vec.elements().get((int) idx);
        }
        if (coll instanceof dev.irij.interpreter.Values.IrijTuple tup) {
            long idx = asLongArg(key, "get");
            if (idx < 0 || idx >= tup.elements().length) return dev.irij.interpreter.Values.UNIT;
            return tup.elements()[(int) idx];
        }
        throw new dev.irij.IrijRuntimeError(
                "get expects a Map, Vector, or Tuple as second argument");
    }

    public static Object assoc(Object m, Object key, Object val) {
        if (m instanceof dev.irij.interpreter.Values.IrijMap map) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(map.entries());
            entries.put(dev.irij.interpreter.Values.toIrijString(key), val);
            return new dev.irij.interpreter.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "assoc expects a Map as first argument, got " + typeTag(m));
    }

    public static Object dissoc(Object m, Object key) {
        if (m instanceof dev.irij.interpreter.Values.IrijMap map) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(map.entries());
            entries.remove(dev.irij.interpreter.Values.toIrijString(key));
            return new dev.irij.interpreter.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "dissoc expects a Map as first argument, got " + typeTag(m));
    }

    public static Object merge(Object a, Object b) {
        if (a instanceof dev.irij.interpreter.Values.IrijMap m1
                && b instanceof dev.irij.interpreter.Values.IrijMap m2) {
            java.util.LinkedHashMap<String, Object> entries =
                    new java.util.LinkedHashMap<>(m1.entries());
            entries.putAll(m2.entries());
            return new dev.irij.interpreter.Values.IrijMap(entries);
        }
        throw new dev.irij.IrijRuntimeError(
                "merge expects two Maps, got " + typeTag(a) + " and " + typeTag(b));
    }

    public static Object keys(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijMap map) {
            return new dev.irij.interpreter.Values.IrijVector(
                    new java.util.ArrayList<>(map.entries().keySet()));
        }
        throw new dev.irij.IrijRuntimeError("keys expects a Map");
    }

    public static Object vals(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijMap map) {
            return new dev.irij.interpreter.Values.IrijVector(
                    new java.util.ArrayList<>(map.entries().values()));
        }
        throw new dev.irij.IrijRuntimeError("vals expects a Map");
    }

    public static Object containsP(Object coll, Object elem) {
        if (coll instanceof dev.irij.interpreter.Values.IrijVector vec) {
            return vec.elements().contains(elem);
        }
        if (coll instanceof dev.irij.interpreter.Values.IrijSet set) {
            return set.elements().contains(elem);
        }
        if (coll instanceof dev.irij.interpreter.Values.IrijMap map) {
            return map.entries().containsKey(
                    dev.irij.interpreter.Values.toIrijString(elem));
        }
        throw new dev.irij.IrijRuntimeError(
                "contains? expects a collection, got " + typeTag(coll));
    }

    public static Object last(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            if (vec.elements().isEmpty()) {
                throw new dev.irij.IrijRuntimeError("last of empty vector");
            }
            return vec.elements().get(vec.elements().size() - 1);
        }
        throw new dev.irij.IrijRuntimeError(
                "last expects a Vector, got " + typeTag(v));
    }

    public static Object toVec(Object v) {
        return new dev.irij.interpreter.Values.IrijVector(asListAny(v));
    }

    // ── Misc ───────────────────────────────────────────────────────────

    public static Object notOp(Object v) {
        return !truthy(v);
    }

    public static Object typeOf(Object v) {
        return typeTag(v);
    }

    /** `validate spec-name value` — returns Ok(v) on pass, Err(msg)
     *  on failure. Mirrors the interpreter's `validate` builtin. */
    public static Object validate(Object specNameArg, Object value) {
        String name = asStr(specNameArg, "validate");
        try {
            Object result = dev.irij.compiler.SpecValidator.validate(
                    value, new dev.irij.ast.SpecExpr.Name(name));
            return new dev.irij.interpreter.Values.Tagged(
                    "Ok", java.util.List.of(result));
        } catch (dev.irij.IrijRuntimeError e) {
            return new dev.irij.interpreter.Values.Tagged(
                    "Err", java.util.List.of(e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** `validate! spec-name value` — returns value on pass, throws on fail. */
    /** Start a record-update: clone base IrijMap's entries into a new
     *  LinkedHashMap, returning that. Bytecode emitter then puts each
     *  updated field and wraps in IrijMap. */
    public static java.util.LinkedHashMap<String, Object> recordUpdateBegin(Object base) {
        if (!(base instanceof dev.irij.interpreter.Values.IrijMap bm)) {
            throw new dev.irij.IrijRuntimeError(
                    "Record update requires a Map, got " + typeTag(base));
        }
        return new java.util.LinkedHashMap<>(bm.entries());
    }

    public static Object validateBang(Object specNameArg, Object value) {
        String name = asStr(specNameArg, "validate!");
        return dev.irij.compiler.SpecValidator.validate(
                value, new dev.irij.ast.SpecExpr.Name(name));
    }

    // ── JSON (delegates to interp's Builtins helpers; same semantics) ──

    public static Object jsonParse(Object strArg) {
        String str = asStr(strArg, "json-parse");
        try {
            return dev.irij.interpreter.Builtins.jsonToIrij(
                    com.google.gson.JsonParser.parseString(str));
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new dev.irij.IrijRuntimeError("json-parse: " + e.getMessage());
        }
    }

    public static Object jsonEncode(Object v) {
        return dev.irij.interpreter.Builtins.irijToJson(v).toString();
    }

    public static Object jsonEncodePretty(Object v) {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create();
        return gson.toJson(dev.irij.interpreter.Builtins.irijToJson(v));
    }

    // ── FileIO ───────────────────────────────────────────────────────

    public static Object makeDir(Object pathArg) {
        String path = asStr(pathArg, "make-dir");
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("make-dir: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object listDir(Object pathArg) {
        String path = asStr(pathArg, "list-dir");
        try (java.util.stream.Stream<java.nio.file.Path> stream =
                java.nio.file.Files.list(java.nio.file.Path.of(path))) {
            java.util.List<Object> names = new java.util.ArrayList<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            return new dev.irij.interpreter.Values.IrijVector(names);
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("list-dir: " + e.getMessage());
        }
    }

    public static Object deleteFile(Object pathArg) {
        String path = asStr(pathArg, "delete-file");
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path)); }
        catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("delete-file: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object readFile(Object pathArg) {
        String path = asStr(pathArg, "read-file");
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("read-file: " + e.getMessage());
        }
    }

    public static Object writeFile(Object pathArg, Object content) {
        String path = asStr(pathArg, "write-file");
        String text = asStr(content, "write-file");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), text,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("write-file: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object appendFile(Object pathArg, Object content) {
        String path = asStr(pathArg, "append-file");
        String text = asStr(content, "append-file");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), text,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("append-file: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object fileExistsQ(Object pathArg) {
        return java.nio.file.Files.exists(
                java.nio.file.Path.of(asStr(pathArg, "file-exists?")));
    }

    // ── Env / time / misc ────────────────────────────────────────────

    public static Object getEnv(Object nameArg) {
        String name = asStr(nameArg, "get-env");
        String v = System.getenv(name);
        return v != null ? v : dev.irij.interpreter.Values.UNIT;
    }

    public static Object nowMs() {
        return System.currentTimeMillis();
    }

    /** Construct an IrijRange. Args are Long-typed; `exclusive`
     *  controls whether the upper bound is included (`0 ..< 10` is
     *  exclusive, `0 .. 10` is inclusive). Used by the bytecode
     *  emitter's `Expr.Range` lowering. */
    public static Object rangeOf(Object fromArg, Object toArg, boolean exclusive) {
        if (!(fromArg instanceof Long lf) || !(toArg instanceof Long lt)) {
            throw new dev.irij.IrijRuntimeError(
                    "Range requires Int endpoints, got "
                            + typeTag(fromArg) + " .. " + typeTag(toArg));
        }
        return new dev.irij.interpreter.Values.IrijRange(lf, lt, exclusive);
    }

    // ── Math primitives (Phase R3) ────────────────────────────────────

    public static Object sqrt(Object x) { return Math.sqrt(asDoubleArg(x, "sqrt")); }
    public static Object sin(Object x)  { return Math.sin(asDoubleArg(x, "sin")); }
    public static Object cos(Object x)  { return Math.cos(asDoubleArg(x, "cos")); }
    public static Object tan(Object x)  { return Math.tan(asDoubleArg(x, "tan")); }
    public static Object log(Object x)  { return Math.log(asDoubleArg(x, "log")); }
    public static Object exp(Object x)  { return Math.exp(asDoubleArg(x, "exp")); }
    public static Object floor(Object x) { return (long) Math.floor(asDoubleArg(x, "floor")); }
    public static Object ceil(Object x)  { return (long) Math.ceil(asDoubleArg(x, "ceil")); }
    public static Object round(Object x) { return Math.round(asDoubleArg(x, "round")); }
    public static Object pow(Object a, Object b) {
        double da = asDoubleArg(a, "pow");
        double db = asDoubleArg(b, "pow");
        double result = Math.pow(da, db);
        // If both operands were Long and result fits a long without loss,
        // return Long (matches interp's powOp narrowing).
        if (a instanceof Long && b instanceof Long
                && result == Math.floor(result)
                && !Double.isInfinite(result)
                && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
            return (long) result;
        }
        return result;
    }
    public static Object abs(Object v) {
        if (v instanceof Long l) return Math.abs(l);
        if (v instanceof Double d) return Math.abs(d);
        throw new dev.irij.IrijRuntimeError(
                "abs expects a number, got " + typeTag(v));
    }
    public static Object min(Object a, Object b) {
        return cmp(a, b) <= 0 ? a : b;
    }
    public static Object max(Object a, Object b) {
        return cmp(a, b) >= 0 ? a : b;
    }
    public static Object divInt(Object a, Object b) {
        long la = asLongArg(a, "div");
        long lb = asLongArg(b, "div");
        if (lb == 0) throw new dev.irij.IrijRuntimeError("Division by zero");
        return la / lb;
    }
    public static Object modInt(Object a, Object b) {
        long la = asLongArg(a, "mod");
        long lb = asLongArg(b, "mod");
        if (lb == 0) throw new dev.irij.IrijRuntimeError("Division by zero");
        return la % lb;
    }

    // Math constants — boxed once. Bytecode emitVarLoad sees `pi`
    // and `e` Var references and emits GETSTATIC against these
    // fields. Interpreter binds them as raw doubles in globalEnv.
    public static final Object PI_BOXED = Double.valueOf(Math.PI);
    public static final Object E_BOXED  = Double.valueOf(Math.E);

    // ── Random ────────────────────────────────────────────────────────

    public static Object randomInt(Object boundArg) {
        long bound = asLongArg(boundArg, "random-int");
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(bound);
    }
    public static Object randomFloat() {
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
    }

    // ── String parsing / chars ────────────────────────────────────────

    public static Object parseInt(Object strArg) {
        String s = asStr(strArg, "parse-int");
        try { return Long.parseLong(s.strip()); }
        catch (NumberFormatException e) {
            throw new dev.irij.IrijRuntimeError(
                    "parse-int: cannot parse '" + s + "' as Int");
        }
    }
    public static Object parseFloat(Object strArg) {
        String s = asStr(strArg, "parse-float");
        try { return Double.parseDouble(s.strip()); }
        catch (NumberFormatException e) {
            throw new dev.irij.IrijRuntimeError(
                    "parse-float: cannot parse '" + s + "' as Float");
        }
    }
    public static Object charAt(Object strArg, Object idxArg) {
        String s = asStr(strArg, "char-at");
        int i = (int) asLongArg(idxArg, "char-at");
        if (i < 0 || i >= s.length()) {
            throw new dev.irij.IrijRuntimeError(
                    "char-at: index " + i + " out of bounds (length " + s.length() + ")");
        }
        return String.valueOf(s.charAt(i));
    }
    public static Object charCode(Object strArg) {
        String s = asStr(strArg, "char-code");
        if (s.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("char-code: empty string");
        }
        return (long) s.codePointAt(0);
    }
    public static Object fromCharCode(Object cpArg) {
        int cp = (int) asLongArg(cpArg, "from-char-code");
        return String.valueOf(Character.toChars(cp));
    }

    // ── Vec ops not yet emitted ──────────────────────────────────────

    public static Object reverseVal(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            java.util.List<Object> rev = new java.util.ArrayList<>(vec.elements());
            java.util.Collections.reverse(rev);
            return new dev.irij.interpreter.Values.IrijVector(rev);
        }
        if (v instanceof String s) return new StringBuilder(s).reverse().toString();
        throw new dev.irij.IrijRuntimeError(
                "reverse expects Vector or Str, got " + typeTag(v));
    }
    public static Object sortVal(Object v) {
        if (!(v instanceof dev.irij.interpreter.Values.IrijVector vec)) {
            throw new dev.irij.IrijRuntimeError(
                    "sort expects Vector, got " + typeTag(v));
        }
        java.util.List<Object> out = new java.util.ArrayList<>(vec.elements());
        out.sort((a, b) -> cmp(a, b));
        return new dev.irij.interpreter.Values.IrijVector(out);
    }
    public static Object takeVal(Object nArg, Object collArg) {
        long n = asLongArg(nArg, "take");
        java.util.List<Object> list = asListAny(collArg);
        return new dev.irij.interpreter.Values.IrijVector(
                new java.util.ArrayList<>(list.subList(0, (int) Math.min(n, list.size()))));
    }
    public static Object dropVal(Object nArg, Object collArg) {
        long n = asLongArg(nArg, "drop");
        java.util.List<Object> list = asListAny(collArg);
        return new dev.irij.interpreter.Values.IrijVector(
                new java.util.ArrayList<>(list.subList((int) Math.min(n, list.size()), list.size())));
    }
    public static Object concatTwo(Object a, Object b) {
        // Match Builtins.concatValues semantics: Vec+Vec→Vec, Str+Str→Str.
        if (a instanceof String sa && b instanceof String sb) return sa + sb;
        if (a instanceof dev.irij.interpreter.Values.IrijVector va
                && b instanceof dev.irij.interpreter.Values.IrijVector vb) {
            java.util.List<Object> out = new java.util.ArrayList<>(va.elements());
            out.addAll(vb.elements());
            return new dev.irij.interpreter.Values.IrijVector(out);
        }
        throw new dev.irij.IrijRuntimeError(
                "concat: type mismatch (" + typeTag(a) + ", " + typeTag(b) + ")");
    }

    // ── Functional combinators ───────────────────────────────────────

    public static final IrijFn IDENTITY = args -> args[0];
    public static final IrijFn CONST    = args -> args[0];  // const x y → x

    // ── Builtins exposed as IrijFn values ────────────────────────────
    //
    // Users sometimes pass a builtin by name to a HOF: `sort-by length
    // #[...]`. The emitter's per-call emit cases only fire at App
    // sites; for Var loads we need a first-class function value. Each
    // of the IrijFn statics below wraps one builtin so the bytecode
    // can push it as a value via GETSTATIC. emitVarLoad consults this
    // table when a Var name matches.

    public static final IrijFn LENGTH      = args -> length(args[0]);
    public static final IrijFn HEAD        = args -> head(args[0]);
    public static final IrijFn TAIL        = args -> tail(args[0]);
    public static final IrijFn EMPTY_Q     = args -> isEmpty(args[0]);
    public static final IrijFn TO_STR      = args -> toStr(args[0]);
    public static final IrijFn NOT_FN      = args -> !truthy(args[0]);
    public static final IrijFn TYPE_OF     = args -> typeTag(args[0]);
    public static final IrijFn ABS_FN      = args -> abs(args[0]);
    public static final IrijFn SQRT_FN     = args -> sqrt(args[0]);
    public static final IrijFn FLOOR_FN    = args -> floor(args[0]);
    public static final IrijFn CEIL_FN     = args -> ceil(args[0]);
    public static final IrijFn ROUND_FN    = args -> round(args[0]);
    public static final IrijFn REVERSE_FN  = args -> reverseVal(args[0]);
    public static final IrijFn SORT_FN     = args -> sortVal(args[0]);
    public static final IrijFn PRINTLN_FN  = args -> { println(args[0]); return dev.irij.interpreter.Values.UNIT; };
    public static final IrijFn PRINT_FN    = args -> { print(args[0]); return dev.irij.interpreter.Values.UNIT; };

    // ── Generic builtin-fn-by-name registry ──────────────────────────
    //
    // For builtins not covered by an explicit static IrijFn above —
    // and for fully open coverage as the interpreter package recedes
    // — emitVarLoad's "Unbound variable" fallback emits
    //   INVOKESTATIC RT.builtinFn("name")
    // which returns an IrijFn wrapping the BuiltinFn registered by
    // `Builtins.install`. The registry is lazily materialised on
    // first call by spinning up a throwaway Environment, running
    // `Builtins.install`, and wrapping each BuiltinFn cell value.
    //
    // This bridge keeps the static interpreter-package dependency
    // (Builtins, Environment) until R5b ports the closures' content
    // into RuntimeSupport directly. At that point the registry stays
    // but stops touching the interpreter package.

    private static volatile java.util.Map<String, IrijFn> BUILTIN_REGISTRY;

    public static IrijFn builtinFn(String name) {
        java.util.Map<String, IrijFn> r = BUILTIN_REGISTRY;
        if (r == null) {
            synchronized (RuntimeSupport.class) {
                r = BUILTIN_REGISTRY;
                if (r == null) {
                    r = initBuiltinRegistry();
                    BUILTIN_REGISTRY = r;
                }
            }
        }
        IrijFn fn = r.get(name);
        if (fn == null) {
            throw new dev.irij.IrijRuntimeError(
                    "Unbound variable: " + name);
        }
        return fn;
    }

    private static java.util.Map<String, IrijFn> initBuiltinRegistry() {
        java.util.Map<String, IrijFn> out = new java.util.HashMap<>();
        dev.irij.interpreter.Environment env =
                new dev.irij.interpreter.Environment(null);
        dev.irij.interpreter.Builtins.install(env, System.out, null);
        for (var entry : env.getBindings().entrySet()) {
            String name = entry.getKey();
            var cell = entry.getValue();
            Object value = unwrapCell(cell);
            if (value instanceof dev.irij.interpreter.Values.BuiltinFn bf) {
                out.put(name, args ->
                        bf.apply(java.util.Arrays.asList(args)));
            }
        }
        return out;
    }

    private static Object unwrapCell(dev.irij.interpreter.Environment.Cell c) {
        if (c instanceof dev.irij.interpreter.Environment.ImmutableCell ic) {
            return ic.value();
        }
        if (c instanceof dev.irij.interpreter.Environment.MutableCell mc) {
            return mc.get();
        }
        return null;
    }

    // ── Misc ─────────────────────────────────────────────────────────

    public static void dbg(Object v) {
        System.err.println("[dbg] " + display(v));
    }
    public static Object tomlParse(Object strArg) {
        String s = asStr(strArg, "toml-parse");
        try {
            return dev.irij.interpreter.Builtins.tomlValueToIrij(
                    new com.moandjiezana.toml.Toml().read(s).toMap());
        } catch (IllegalStateException e) {
            throw new dev.irij.IrijRuntimeError("toml-parse: " + e.getMessage());
        }
    }

    public static Object printlnVal(Object v) {
        println(v);
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object rawHttpRequest(Object optsArg) {
        // Delegate to interp's BuiltinFn via a single direct call.
        // Move to standalone impl post-R5 (interp deletion).
        return dev.irij.interpreter.Builtins.rawHttpRequestImpl(optsArg);
    }

    public static Object readLine() {
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("read-line: " + e.getMessage());
        }
    }

    private static double asDoubleArg(Object v, String op) {
        if (v instanceof Number n) return n.doubleValue();
        throw new dev.irij.IrijRuntimeError(
                op + " expects a number, got " + typeTag(v));
    }

    public static Object envBuiltin(Object[] args) {
        // env "NAME"            -> value or null
        // env "NAME" "default"  -> value or default
        if (args.length == 0) {
            throw new dev.irij.IrijRuntimeError("env requires at least one argument");
        }
        String name = asStr(args[0], "env");
        String v = System.getenv(name);
        if (v != null) return v;
        if (args.length >= 2) return asStr(args[args.length - 1], "env");
        return dev.irij.interpreter.Values.UNIT;
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
        for (Object x : in) out.add(callAny(f, new Object[]{x}));
        return new dev.irij.interpreter.Values.IrijVector(out);
    }

    public static Object seqMapIndexed(Object f, Object coll) {
        java.util.List<Object> in = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            out.add(callAny(f, new Object[]{(long) i, in.get(i)}));
        }
        return new dev.irij.interpreter.Values.IrijVector(out);
    }

    public static Object seqFilter(Object pred, Object coll) {
        java.util.List<Object> in = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Object x : in) {
            if (truthy(callAny(pred, new Object[]{x}))) out.add(x);
        }
        return new dev.irij.interpreter.Values.IrijVector(out);
    }

    public static Object seqFindFirst(Object pred, Object coll) {
        for (Object x : seqList(coll)) {
            if (truthy(callAny(pred, new Object[]{x}))) return x;
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object seqReduce(Object f, Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            acc = callAny(f, new Object[]{acc, list.get(i)});
        }
        return acc;
    }

    public static Object seqScan(Object f, Object coll) {
        java.util.List<Object> list = seqList(coll);
        java.util.List<Object> out = new java.util.ArrayList<>(list.size());
        if (list.isEmpty()) return new dev.irij.interpreter.Values.IrijVector(out);
        Object acc = list.get(0);
        out.add(acc);
        for (int i = 1; i < list.size(); i++) {
            acc = callAny(f, new Object[]{acc, list.get(i)});
            out.add(acc);
        }
        return new dev.irij.interpreter.Values.IrijVector(out);
    }

    public static Object seqSum(Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) acc = add(acc, list.get(i));
        return acc;
    }

    public static Object seqProduct(Object coll) {
        java.util.List<Object> list = seqList(coll);
        if (list.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("Cannot reduce empty collection");
        }
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) acc = mul(acc, list.get(i));
        return acc;
    }

    public static Object seqCount(Object coll) {
        return (long) seqList(coll).size();
    }

    public static Object seqAll(Object coll) {
        for (Object x : seqList(coll)) if (!truthy(x)) return Boolean.FALSE;
        return Boolean.TRUE;
    }

    public static Object seqAny(Object coll) {
        for (Object x : seqList(coll)) if (truthy(x)) return Boolean.TRUE;
        return Boolean.FALSE;
    }

    // ── SeqOp as values (partial application) ────────────────────────
    //
    // Emitted when a SeqOp expression appears in value position (e.g.
    // `cards |> @ f` lowers to `(SeqOp(@, f))(cards)`). The result is
    // an IrijFn awaiting the collection.

    public static IrijFn seqMapPartial(Object f)      { return args -> seqMap(f, args[0]); }
    public static IrijFn seqMapIndexedPartial(Object f) { return args -> seqMapIndexed(f, args[0]); }
    public static IrijFn seqFilterPartial(Object p)   { return args -> seqFilter(p, args[0]); }
    public static IrijFn seqFindFirstPartial(Object p) { return args -> seqFindFirst(p, args[0]); }
    public static IrijFn seqReducePartial(Object f)   { return args -> seqReduce(f, args[0]); }
    public static IrijFn seqScanPartial(Object f)     { return args -> seqScan(f, args[0]); }

    // SeqOps that don't take a captured fn — single shared instances.
    public static final IrijFn SEQ_SUM     = args -> seqSum(args[0]);
    public static final IrijFn SEQ_PRODUCT = args -> seqProduct(args[0]);
    public static final IrijFn SEQ_COUNT   = args -> seqCount(args[0]);
    public static final IrijFn SEQ_ALL     = args -> seqAll(args[0]);
    public static final IrijFn SEQ_ANY     = args -> seqAny(args[0]);

    // ── SQLite raw-db-* (Phase R3 batch 2) ─────────────────────────────
    //
    // Mirrors the interpreter's `raw-db-*` builtins exactly: same
    // jdbc:sqlite URL, same WAL pragma, same Tagged("DbConn", [conn])
    // wrapper, same value coercion in/out. Both back-ends use the
    // same Tagged wrapper, so a connection opened in interp mode can
    // be passed to bytecode code and vice versa (within a JVM).

    public static Object rawDbOpen(Object pathArg) {
        String path = asStr(pathArg, "raw-db-open");
        try {
            java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }
            return new dev.irij.interpreter.Values.Tagged(
                    "DbConn", java.util.List.of(conn), null, null);
        } catch (java.sql.SQLException e) {
            throw new dev.irij.IrijRuntimeError("raw-db-open: " + e.getMessage());
        }
    }

    public static Object rawDbClose(Object connArg) {
        java.sql.Connection conn = extractConnection(connArg, "raw-db-close");
        try { conn.close(); }
        catch (java.sql.SQLException e) {
            throw new dev.irij.IrijRuntimeError("raw-db-close: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object rawDbQuery(Object connArg, Object sqlArg, Object paramsArg) {
        java.sql.Connection conn = extractConnection(connArg, "raw-db-query");
        String sql = asStr(sqlArg, "raw-db-query");
        java.util.List<Object> params = extractParams(paramsArg, "raw-db-query");
        try {
            synchronized (conn) {
                java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                bindParams(ps, params);
                java.sql.ResultSet rs = ps.executeQuery();
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                java.util.List<Object> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i),
                                sqlToIrij(rs, i, meta.getColumnType(i)));
                    }
                    rows.add(new dev.irij.interpreter.Values.IrijMap(row));
                }
                rs.close();
                ps.close();
                return new dev.irij.interpreter.Values.IrijVector(rows);
            }
        } catch (java.sql.SQLException e) {
            throw new dev.irij.IrijRuntimeError("raw-db-query: " + e.getMessage());
        }
    }

    public static Object rawDbExec(Object connArg, Object sqlArg, Object paramsArg) {
        java.sql.Connection conn = extractConnection(connArg, "raw-db-exec");
        String sql = asStr(sqlArg, "raw-db-exec");
        java.util.List<Object> params = extractParams(paramsArg, "raw-db-exec");
        try {
            synchronized (conn) {
                java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                bindParams(ps, params);
                long affected = ps.executeUpdate();
                ps.close();
                return affected;
            }
        } catch (java.sql.SQLException e) {
            throw new dev.irij.IrijRuntimeError("raw-db-exec: " + e.getMessage());
        }
    }

    public static Object rawDbTransaction(Object connArg, Object thunk) {
        java.sql.Connection conn = extractConnection(connArg, "raw-db-transaction");
        try {
            synchronized (conn) {
                boolean savedAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    Object result = callAny(thunk, new Object[0]);
                    conn.commit();
                    return result;
                } catch (Throwable t) {
                    try { conn.rollback(); } catch (java.sql.SQLException ignored) {}
                    if (t instanceof dev.irij.IrijRuntimeError ire) throw ire;
                    if (t instanceof RuntimeException re) throw re;
                    throw new dev.irij.IrijRuntimeError(
                            "raw-db-transaction: " + t.getMessage());
                } finally {
                    try { conn.setAutoCommit(savedAutoCommit); } catch (java.sql.SQLException ignored) {}
                }
            }
        } catch (java.sql.SQLException e) {
            throw new dev.irij.IrijRuntimeError("raw-db-transaction: " + e.getMessage());
        }
    }

    private static java.sql.Connection extractConnection(Object value, String op) {
        if (value instanceof dev.irij.interpreter.Values.Tagged t
                && "DbConn".equals(t.tag())
                && !t.fields().isEmpty()
                && t.fields().get(0) instanceof java.sql.Connection c) {
            return c;
        }
        throw new dev.irij.IrijRuntimeError(
                op + ": first argument must be a database connection (from db-open)");
    }

    private static java.util.List<Object> extractParams(Object value, String op) {
        if (value instanceof dev.irij.interpreter.Values.IrijVector v) return v.elements();
        throw new dev.irij.IrijRuntimeError(op + ": params must be a vector #[...]");
    }

    private static void bindParams(java.sql.PreparedStatement ps, java.util.List<Object> params)
            throws java.sql.SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l)         ps.setLong(i + 1, l);
            else if (p instanceof Double d)  ps.setDouble(i + 1, d);
            else if (p instanceof String s)  ps.setString(i + 1, s);
            else if (p instanceof Boolean b) ps.setBoolean(i + 1, b);
            else if (p == dev.irij.interpreter.Values.UNIT || p == null) {
                ps.setNull(i + 1, java.sql.Types.NULL);
            } else {
                ps.setString(i + 1, dev.irij.interpreter.Values.toIrijString(p));
            }
        }
    }

    private static Object sqlToIrij(java.sql.ResultSet rs, int col, int sqlType)
            throws java.sql.SQLException {
        Object val = rs.getObject(col);
        if (val == null) return dev.irij.interpreter.Values.UNIT;
        return switch (sqlType) {
            case java.sql.Types.INTEGER, java.sql.Types.BIGINT,
                 java.sql.Types.SMALLINT, java.sql.Types.TINYINT ->
                rs.getLong(col);
            case java.sql.Types.REAL, java.sql.Types.FLOAT, java.sql.Types.DOUBLE,
                 java.sql.Types.DECIMAL, java.sql.Types.NUMERIC ->
                rs.getDouble(col);
            case java.sql.Types.BOOLEAN -> rs.getBoolean(col);
            case java.sql.Types.BLOB -> {
                byte[] bytes = rs.getBytes(col);
                yield java.util.Base64.getEncoder().encodeToString(bytes);
            }
            default -> {
                String s = rs.getString(col);
                yield s != null ? s : dev.irij.interpreter.Values.UNIT;
            }
        };
    }

    // ── SSE raw-sse-* (Phase R3 batch 3) ───────────────────────────────

    public static Object rawSseResponse(Object reqArg) {
        if (!(reqArg instanceof dev.irij.interpreter.Values.IrijMap reqMap)) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-sse-response: expected request map");
        }
        Object exchange = reqMap.entries().get("__exchange");
        if (!(exchange instanceof com.sun.net.httpserver.HttpExchange ex)) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-sse-response: no __exchange in request (only works inside raw-http-serve handler)");
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            return new dev.irij.interpreter.Values.SseWriter(ex, ex.getResponseBody());
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("raw-sse-response: " + e.getMessage());
        }
    }

    public static Object rawSseSend(Object sseArg, Object evtArg, Object dataArg) {
        if (!(sseArg instanceof dev.irij.interpreter.Values.SseWriter sse)) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-sse-send: first arg must be SseWriter");
        }
        String evt = asStr(evtArg, "raw-sse-send");
        String data = asStr(dataArg, "raw-sse-send");
        try { sse.send(evt, data); }
        catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("raw-sse-send: " + e.getMessage());
        }
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object rawSseClose(Object sseArg) {
        if (!(sseArg instanceof dev.irij.interpreter.Values.SseWriter sse)) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-sse-close: first arg must be SseWriter");
        }
        sse.close();
        return dev.irij.interpreter.Values.UNIT;
    }

    public static Object rawSseClosedQ(Object sseArg) {
        if (!(sseArg instanceof dev.irij.interpreter.Values.SseWriter sse)) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-sse-closed?: first arg must be SseWriter");
        }
        return sse.isClosed();
    }

    // ── Multipart raw-multipart-* (Phase R3 batch 3) ───────────────────

    public static Object rawMultipartField(Object reqArg, Object fieldArg) {
        byte[] body = mpRequestBytes(reqArg, "raw-multipart-field");
        String boundary = mpBoundary(reqArg, "raw-multipart-field");
        String field = asStr(fieldArg, "raw-multipart-field");
        int[] range = mpFindPartBody(body, boundary, field);
        if (range == null) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-multipart-field: field '" + field + "' not found");
        }
        return new String(body, range[0], range[1] - range[0],
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Object rawMultipartSave(Object reqArg, Object fieldArg, Object pathArg) {
        byte[] body = mpRequestBytes(reqArg, "raw-multipart-save");
        String boundary = mpBoundary(reqArg, "raw-multipart-save");
        String field = asStr(fieldArg, "raw-multipart-save");
        String savePath = asStr(pathArg, "raw-multipart-save");
        int[] range = mpFindPartBody(body, boundary, field);
        if (range == null) {
            throw new dev.irij.IrijRuntimeError(
                    "raw-multipart-save: field '" + field + "' not found");
        }
        try {
            java.nio.file.Path target = java.nio.file.Path.of(savePath);
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.write(target,
                    java.util.Arrays.copyOfRange(body, range[0], range[1]));
            return savePath;
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("raw-multipart-save: " + e.getMessage());
        }
    }

    private static byte[] mpRequestBytes(Object reqArg, String op) {
        if (!(reqArg instanceof dev.irij.interpreter.Values.IrijMap req)) {
            throw new dev.irij.IrijRuntimeError(op + ": expects request Map");
        }
        Object bodyBytes = req.entries().get("__body_bytes");
        if (!(bodyBytes instanceof byte[] bytes)) {
            throw new dev.irij.IrijRuntimeError(op + ": no raw body bytes in request");
        }
        return bytes;
    }

    private static String mpBoundary(Object reqArg, String op) {
        dev.irij.interpreter.Values.IrijMap req = (dev.irij.interpreter.Values.IrijMap) reqArg;
        String contentType = "";
        Object headers = req.entries().get("headers");
        if (headers instanceof dev.irij.interpreter.Values.IrijMap hm) {
            Object ct = hm.entries().get("content-type");
            if (ct instanceof String s) contentType = s;
        }
        String boundary = mpExtractBoundary(contentType);
        if (boundary == null) {
            throw new dev.irij.IrijRuntimeError(op + ": no boundary in content-type");
        }
        return boundary;
    }

    private static String mpExtractBoundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String t = part.trim();
            if (t.startsWith("boundary=")) return t.substring("boundary=".length()).trim();
        }
        return null;
    }

    private static int[] mpFindPartBody(byte[] data, String boundary, String fieldName) {
        byte[] delim = ("--" + boundary).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] namePat = ("name=\"" + fieldName + "\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] crlfcrlf = "\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < data.length) {
            int delimPos = mpIndexOf(data, delim, pos);
            if (delimPos < 0) break;
            int nextDelim = mpIndexOf(data, delim, delimPos + delim.length);
            if (nextDelim < 0) nextDelim = data.length;
            int namePos = mpIndexOf(data, namePat, delimPos);
            if (namePos >= 0 && namePos < nextDelim) {
                int bodyStart = mpIndexOf(data, crlfcrlf, delimPos);
                if (bodyStart >= 0) {
                    bodyStart += crlfcrlf.length;
                    int bodyEnd = nextDelim - 2;
                    if (bodyEnd > bodyStart) return new int[]{bodyStart, bodyEnd};
                }
            }
            pos = delimPos + delim.length;
        }
        return null;
    }

    private static int mpIndexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ── HTTP server raw-http-serve (Phase R3 batch 3) ──────────────────
    //
    // Bytecode-mode HTTP server. Mirrors Interpreter.raw-http-serve's
    // behaviour: static-file serving (classpath __irij_resources/ +
    // __irij_app/ + script-relative resources/), request-map building,
    // SSE-handler passthrough, response writing. Differences vs interp:
    //
    //  - Handler invocation goes through {@link #callAny} so an IrijFn
    //    (bytecode lambda) works exactly like the interpreter's apply()
    //    on a BuiltinFn/Lambda; both back-ends can host the same
    //    `handler` value.
    //  - sourcePath is taken from the JVM working dir; bundled mode is
    //    detected by probing for __irij_resources/ on the classpath.

    public static Object rawHttpServe(Object portArg, Object handler) {
        long port = asLongArg(portArg, "raw-http-serve");
        try {
            com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress((int) port), 0);

            java.nio.file.Path scriptDir = java.nio.file.Path.of("").toAbsolutePath();
            final boolean isBundled = RuntimeSupport.class
                    .getClassLoader().getResource("__irij_resources/") != null
                    || RuntimeSupport.class
                    .getClassLoader().getResource("__irij_app/") != null;

            server.createContext("/", exchange -> {
                try {
                    if (httpServeStatic(exchange, scriptDir, isBundled)) return;

                    dev.irij.interpreter.Values.IrijMap req = buildRequestMap(exchange);
                    Object resp = callAny(handler, new Object[]{req});

                    if (resp instanceof dev.irij.interpreter.Values.SseWriter sse) {
                        // Handler took over the exchange via SSE — block this
                        // dispatch thread until SSE writer closes so the
                        // connection stays open.
                        while (!sse.isClosed()) {
                            try { Thread.sleep(500); }
                            catch (InterruptedException ie) { sse.close(); break; }
                        }
                        return;
                    }

                    writeResponse(exchange, resp);
                } catch (Exception e) {
                    System.err.println("HTTP 500 " + exchange.getRequestMethod()
                            + " " + exchange.getRequestURI() + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                    try {
                        String errMsg = "Internal Server Error: " + e.getMessage();
                        byte[] errBytes = errMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, errBytes.length);
                        try (var os = exchange.getResponseBody()) { os.write(errBytes); }
                    } catch (Exception ignored) {}
                }
            });

            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            System.out.println("Irij HTTP server listening on http://localhost:" + port);
            try { Thread.currentThread().join(); }
            catch (InterruptedException e) { server.stop(0); }
            return dev.irij.interpreter.Values.UNIT;
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("raw-http-serve: " + e.getMessage());
        }
    }

    private static boolean httpServeStatic(com.sun.net.httpserver.HttpExchange exchange,
                                            java.nio.file.Path scriptDir,
                                            boolean isBundled) throws java.io.IOException {
        String reqPath = exchange.getRequestURI().getPath();
        if (reqPath.length() <= 1 || reqPath.contains("..")) return false;

        if (isBundled) {
            if (serveClasspathResource(exchange, "__irij_resources/" + reqPath.substring(1), reqPath)) return true;
            if (serveClasspathResource(exchange, "__irij_app/" + reqPath.substring(1), reqPath)) return true;
        }
        java.nio.file.Path resourcesPath = scriptDir.resolve("resources")
                .resolve(reqPath.substring(1)).normalize();
        java.nio.file.Path resourcesRoot = scriptDir.resolve("resources").normalize();
        if (resourcesPath.startsWith(resourcesRoot)
                && java.nio.file.Files.isRegularFile(resourcesPath)) {
            return sendFile(exchange, resourcesPath, reqPath);
        }
        java.nio.file.Path filePath = scriptDir.resolve(reqPath.substring(1)).normalize();
        if (filePath.startsWith(scriptDir) && java.nio.file.Files.isRegularFile(filePath)) {
            return sendFile(exchange, filePath, reqPath);
        }
        return false;
    }

    private static boolean serveClasspathResource(com.sun.net.httpserver.HttpExchange exchange,
                                                    String resourcePath, String reqPath) throws java.io.IOException {
        java.io.InputStream is = RuntimeSupport.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return false;
        try (is) {
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", httpGuessMime(reqPath));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
            return true;
        }
    }

    private static boolean sendFile(com.sun.net.httpserver.HttpExchange exchange,
                                     java.nio.file.Path path, String reqPath) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        String mime = java.nio.file.Files.probeContentType(path);
        if (mime == null) mime = httpGuessMime(reqPath);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) { os.write(bytes); }
        return true;
    }

    private static dev.irij.interpreter.Values.IrijMap buildRequestMap(
            com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        java.util.LinkedHashMap<String, Object> reqMap = new java.util.LinkedHashMap<>();
        reqMap.put("method", exchange.getRequestMethod());
        java.net.URI uri = exchange.getRequestURI();
        reqMap.put("path", uri.getPath());
        String rawQuery = uri.getQuery() != null ? uri.getQuery() : "";
        reqMap.put("query", rawQuery);
        reqMap.put("params", new dev.irij.interpreter.Values.IrijMap(httpParseQueryParams(rawQuery)));
        java.util.LinkedHashMap<String, Object> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) ->
                headers.put(k.toLowerCase(),
                        v.size() == 1 ? v.get(0) : String.join(", ", v)));
        reqMap.put("headers", new dev.irij.interpreter.Values.IrijMap(headers));
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        reqMap.put("body", new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8));
        reqMap.put("__body_bytes", bodyBytes);
        reqMap.put("__exchange", exchange);
        return new dev.irij.interpreter.Values.IrijMap(reqMap);
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange exchange, Object resp)
            throws java.io.IOException {
        long status = 200;
        String respBody = "";
        String filePath = null;
        java.util.Map<String, Object> respHeaders = java.util.Map.of();
        if (resp instanceof dev.irij.interpreter.Values.IrijMap rm) {
            java.util.Map<String, Object> e = rm.entries();
            if (e.get("status") instanceof Long s) status = s;
            if (e.get("body") instanceof String b) respBody = b;
            if (e.get("file") instanceof String f) filePath = f;
            if (e.get("headers") instanceof dev.irij.interpreter.Values.IrijMap hm) {
                respHeaders = hm.entries();
            }
        } else if (resp instanceof String s) {
            respBody = s;
        }
        for (java.util.Map.Entry<String, Object> h : respHeaders.entrySet()) {
            exchange.getResponseHeaders().set(h.getKey(),
                    dev.irij.interpreter.Values.toIrijString(h.getValue()));
        }
        if (!respHeaders.containsKey("content-type") && !respHeaders.containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        }
        if (filePath != null) {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(filePath));
            exchange.sendResponseHeaders((int) status, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        } else {
            byte[] bytes = respBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                exchange.sendResponseHeaders((int) status, bytes.length);
            }
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    private static String httpGuessMime(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf"))  return "font/ttf";
        if (path.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (path.endsWith(".xml"))  return "application/xml";
        return "application/octet-stream";
    }

    private static java.util.LinkedHashMap<String, Object> httpParseQueryParams(String query) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k, v;
            if (eq < 0) { k = pair; v = ""; }
            else { k = pair.substring(0, eq); v = pair.substring(eq + 1); }
            try {
                k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            out.put(k, v);
        }
        return out;
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

    // ── Effects (14c.2: thread+channel lowering; reuses EffectSystem) ──

    /**
     * Compiled handler value: clause map from op-name to IrijFn.
     * Each clause IrijFn is invoked with arg-array that ends with the resume
     * IrijFn: {@code args..., resume}. Clause returns the value that should
     * be the result of the enclosing `with` block.
     */
    public static final class CompiledHandler {
        public final String name;
        public final String effectName;
        public final java.util.Map<String, IrijFn> clauses;
        public CompiledHandler(String name, String effectName, java.util.Map<String, IrijFn> clauses) {
            this.name = name; this.effectName = effectName; this.clauses = clauses;
        }
    }

    /** Flat ordered list of handlers from a `>>` composition. */
    public static final class CompiledComposedHandler {
        public final java.util.List<CompiledHandler> handlers;
        public CompiledComposedHandler(java.util.List<CompiledHandler> handlers) {
            this.handlers = handlers;
        }
    }

    /** Flatten and build a composed handler from two operand values, OR
     *  build a function composition `(x -> right(left(x)))` when both
     *  operands are callable (lambdas, builtins). Used by the `>>`
     *  operator at the runtime level. */
    public static Object compose(Object left, Object right) {
        boolean leftIsHandler = left instanceof CompiledHandler
                || left instanceof CompiledComposedHandler;
        boolean rightIsHandler = right instanceof CompiledHandler
                || right instanceof CompiledComposedHandler;
        if (leftIsHandler && rightIsHandler) {
            java.util.List<CompiledHandler> all = new java.util.ArrayList<>();
            appendHandlers(left, all);
            appendHandlers(right, all);
            return new CompiledComposedHandler(java.util.List.copyOf(all));
        }
        // Function composition: (f >> g)(x) ≡ g(f(x))
        return new CurriedFn(
                args -> callAny(right, new Object[]{ callAny(left, args) }),
                1, new Object[0]);
    }

    private static void appendHandlers(Object v, java.util.List<CompiledHandler> out) {
        if (v instanceof CompiledHandler h) out.add(h);
        else if (v instanceof CompiledComposedHandler c) out.addAll(c.handlers);
        else throw new dev.irij.IrijRuntimeError(
                ">> requires handler operands, got " + typeTag(v));
    }

    /** Emitted call-site for effect ops. Routes through EffectSystem.fireOp. */
    public static Object perform(String effectName, String opName, Object[] args) {
        return dev.irij.interpreter.EffectSystem.fireOp(
                effectName, opName, java.util.Arrays.asList(args));
    }

    /** `error "msg"` builtin — throws IrijRuntimeError, caught by `on-failure`. */
    public static Object errorBuiltin(Object msg) {
        throw new dev.irij.IrijRuntimeError(
                msg == null ? "error" : dev.irij.interpreter.Values.toIrijString(msg));
    }

    /** Extract message for `on-failure` binding — never null. */
    public static String errorMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /**
     * Run body under a compiled handler: spawns a virtual thread for the body,
     * drives the handler loop on the calling thread, supports one-shot resume.
     */
    public static Object runWith(Object handlerObj, IrijFn body) {
        if (handlerObj instanceof CompiledComposedHandler cc) {
            return runWithComposed(cc.handlers, 0, body);
        }
        if (!(handlerObj instanceof CompiledHandler h)) {
            throw new dev.irij.IrijRuntimeError(
                    "with requires a handler, got " + typeTag(handlerObj));
        }
        var opChannel = new java.util.concurrent.SynchronousQueue<
                dev.irij.interpreter.EffectSystem.EffectMessage>();
        var ctx = new dev.irij.interpreter.EffectSystem.HandlerContext(
                h.effectName, h, opChannel);
        var parentStack = new java.util.ArrayDeque<>(
                dev.irij.interpreter.EffectSystem.STACK.get());

        Thread bodyThread = Thread.startVirtualThread(() -> {
            var bodyStack = dev.irij.interpreter.EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            try {
                Object result = body.apply(new Object[0]);
                opChannel.put(new dev.irij.interpreter.EffectSystem.EffectMessage.Done(result));
            } catch (InterruptedException e) {
                // aborted by handler (no resume)
            } catch (Throwable t) {
                try {
                    opChannel.put(new dev.irij.interpreter.EffectSystem.EffectMessage.Err(t));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            return runHandlerLoop(h, opChannel);
        } finally {
            if (bodyThread.isAlive()) bodyThread.interrupt();
        }
    }

    /** Nested runWith: with (h0 >> h1 >> h2) body ≡ runWith h0 (\ -> runWith h1 (\ -> runWith h2 body)). */
    private static Object runWithComposed(java.util.List<CompiledHandler> handlers, int idx, IrijFn body) {
        if (idx >= handlers.size()) return body.apply(new Object[0]);
        IrijFn nested = (args) -> runWithComposed(handlers, idx + 1, body);
        return runWith(handlers.get(idx), nested);
    }

    private static Object runHandlerLoop(
            CompiledHandler h,
            java.util.concurrent.SynchronousQueue<dev.irij.interpreter.EffectSystem.EffectMessage> opChannel) {
        while (true) {
            dev.irij.interpreter.EffectSystem.EffectMessage msg;
            try { msg = opChannel.take(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new dev.irij.IrijRuntimeError("Handler loop interrupted");
            }
            switch (msg) {
                case dev.irij.interpreter.EffectSystem.EffectMessage.Done d -> {
                    return d.value();
                }
                case dev.irij.interpreter.EffectSystem.EffectMessage.Err e -> {
                    Throwable t = e.error();
                    if (t instanceof dev.irij.IrijRuntimeError ire) throw ire;
                    if (t instanceof RuntimeException re) throw re;
                    throw new dev.irij.IrijRuntimeError(
                            "Effect body error: " + t.getMessage());
                }
                case dev.irij.interpreter.EffectSystem.EffectMessage.Op op -> {
                    IrijFn clause = h.clauses.get(op.opName());
                    if (clause == null) {
                        throw new dev.irij.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + op.opName());
                    }
                    var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    IrijFn resumeFn = (resumeArgs) -> {
                        if (!resumed.compareAndSet(false, true)) {
                            throw new dev.irij.IrijRuntimeError(
                                    "resume called twice (one-shot continuation)");
                        }
                        try {
                            op.resumeChannel().put(resumeArgs.length > 0
                                    ? resumeArgs[0]
                                    : dev.irij.interpreter.Values.UNIT);
                            return runHandlerLoop(h, opChannel);
                        } catch (InterruptedException e2) {
                            Thread.currentThread().interrupt();
                            throw new dev.irij.IrijRuntimeError(
                                    "Interrupted during resume");
                        }
                    };
                    Object[] clauseArgs = new Object[op.args().size() + 1];
                    for (int i = 0; i < op.args().size(); i++) clauseArgs[i] = op.args().get(i);
                    clauseArgs[op.args().size()] = resumeFn;
                    return clause.apply(clauseArgs);
                }
            }
        }
    }

    // ── Effects (14c.3: state-machine lowering — runtime scaffolding) ──
    //
    // Parent design doc: docs/phase-14c3-state-machine.md
    //
    // This section provides the runtime surface that the state-machine lowering
    // pass (step 2+) will target. The emitter is NOT yet wired to emit
    // IrijContinuation subclasses — step 1 just lands the runtime so it can
    // be exercised with hand-written continuations in tests.

    /**
     * State-machine-lowered effect-bearing body or clause.
     *
     * <p>Concrete — not subclassed. The lowering pass emits a {@link IrijFn}
     * "step" closure that implements the switch-on-state; the continuation
     * holds the mutable state ({@code state} label + {@code fields} for
     * locals that cross {@code perform} boundaries).
     *
     * <p>Step contract: {@code step.apply([thisContinuation, resumeValue])}
     * either returns the final body value or throws {@link PerformSignal}.
     * The first entry passes {@code null} as {@code resumeValue}.
     *
     * <p>Lifted locals are stored in {@link #fields} so they survive across
     * state transitions (JVM operand stack does not survive a throw). The
     * lowering pass assigns each lifted local a stable index into this array.
     *
     * <p>Per-{@code with} freshly allocated (see design doc § 14 — pooling
     * deferred as tech debt).
     */
    public static final class IrijContinuation {
        public int state;
        public final Object[] fields;
        public final IrijFn step;

        public IrijContinuation(IrijFn step, int nFields) {
            this.step = step;
            this.fields = nFields == 0 ? EMPTY_FIELDS : new Object[nFields];
        }

        private static final Object[] EMPTY_FIELDS = new Object[0];

        /**
         * Enter or re-enter the state machine. Argument is the value fed in
         * by the handler's {@code resume} call (or {@code null} on first entry).
         * Either returns the body's final value, or throws
         * {@link PerformSignal} to yield to the enclosing handler.
         */
        public Object resume(Object value) {
            return step.apply(new Object[]{this, value});
        }
    }

    /**
     * Pooled, stack-trace-free signal used by state-machine bodies to yield
     * to the nearest enclosing {@link #runWithSM} frame.
     *
     * <p>Allocated via {@link #of}, which reuses a thread-local instance — the
     * hot path does not allocate. Safe because a signal is either consumed by
     * the dispatcher before the next op call, or re-raised past the dispatcher
     * (in which case the outer dispatcher also consumes it synchronously).
     *
     * <p>Overriding {@link Throwable#fillInStackTrace()} to a no-op is the
     * standard trick for control-flow-only exceptions.
     */
    public static final class PerformSignal extends RuntimeException {
        public String effectName;
        public String opName;
        public Object[] args;
        public IrijContinuation continuation;

        private PerformSignal() { super(null, null, false, false); }

        private static final ThreadLocal<PerformSignal> POOL =
                ThreadLocal.withInitial(PerformSignal::new);

        public static PerformSignal of(String effectName, String opName,
                                        Object[] args, IrijContinuation k) {
            PerformSignal s = POOL.get();
            s.effectName = effectName;
            s.opName = opName;
            s.args = args;
            s.continuation = k;
            return s;
        }

        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    /**
     * Tail-resume sentinel — thrown by the synthesised {@code resumeFn} when
     * a clause invokes {@code resume v} so the dispatch loop unwinds the
     * clause's JVM frames and continues iteratively. Pooled, stack-trace-free.
     *
     * <p><b>Semantic note:</b> idiomatic Irij clauses put {@code resume} in
     * tail position ({@code "stmt; stmt; resume v"}). For those, this throw
     * is purely a control-flow shortcut and behaviour is unchanged. For
     * non-tail clauses ({@code "resume v; postStmt"}) the trampoline causes
     * post-resume statements to be skipped — a deliberate trade-off so that
     * tight perform-loops scale beyond the JVM stack. The same shape can be
     * expressed by moving post-resume code outside the clause.
     */
    public static final class TailResume extends RuntimeException {
        public Object value;
        /**
         * The dispatch loop this resume targets — the continuation that
         * threw the original {@link PerformSignal}. Each loop's catch
         * compares against its own expected target and re-throws on
         * mismatch so nested loops don't accidentally consume each other's
         * resumes (relevant for native nested-SM and future tier-c
         * clause-as-SM compilation).
         */
        public Object target;

        private TailResume() { super(null, null, false, false); }

        private static final ThreadLocal<TailResume> POOL =
                ThreadLocal.withInitial(TailResume::new);

        public static TailResume of(Object v, Object target) {
            TailResume r = POOL.get();
            r.value = v;
            r.target = target;
            return r;
        }

        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    /**
     * State-machine runtime for {@code with handler body} — parallel to
     * {@link #runWith}. Not yet selected by the emitter; invoked directly
     * by tests (step 1) and by emitted code in later steps.
     *
     * <p>Enters the body by calling {@code k.resume(null)}. Each
     * {@link PerformSignal} caught here dispatches to the matching clause;
     * the clause's {@code resume v} call re-enters {@code k.resume(v)} via
     * a synthesised one-shot {@link IrijFn}. Abort (clause never resumed)
     * returns the clause's value directly.
     *
     * <p><b>Current limitation:</b> re-entry after a clause's {@code resume}
     * is recursive (one JVM frame per {@code perform}); for deep loops this
     * grows the stack. Trampoline optimisation recorded as tech debt in the
     * design doc (§ 14). Correctness is unaffected.
     */
    /**
     * Call-site overload used by emitted bytecode: allocates a fresh
     * continuation from a step function and field-count, then delegates.
     */
    public static Object runWithSM(Object handlerObj, IrijFn step, int nFields) {
        return runWithSM(handlerObj, new IrijContinuation(step, nFields));
    }

    /**
     * Run a continuation with no own SM handlers — all of its performs
     * fall through SM_STACK to enclosing SM frames (tier-c path: clause
     * body runs as its own SM, but it doesn't define handlers — its
     * own performs target the next-outer with).
     */
    public static Object runWithSMNoHs(IrijContinuation k) {
        return dispatchLoopSM(java.util.List.of(), k, null);
    }

    /** Allocate a fresh continuation — emit-side helper. */
    public static IrijContinuation newCont(IrijFn step, int nFields) {
        return new IrijContinuation(step, nFields);
    }

    /**
     * Sentinel returned by {@link #fireOpToSM} when no SM handler matches
     * — distinct from any legal Irij value so {@link
     * dev.irij.interpreter.EffectSystem#fireOp} can fall through to
     * "Unhandled effect" without ambiguity.
     */
    public static final Object SM_NO_MATCH = new Object();

    /**
     * Synchronous SM-handler dispatch from {@code EffectSystem.fireOp} —
     * lets a fiber spawned inside an SM {@code with} reach the parent's
     * SM handler via the inherited {@link #SM_STACK}.
     *
     * <p>The synthesised resumeFn here just stores + returns the resume
     * value (no trampoline) since the caller is plain Java code, not an
     * SM continuation. For idiomatic tail-position {@code resume v}
     * clauses, behaviour matches the threaded protocol (returns the
     * value back to the perform call site). Non-tail clauses see
     * post-resume statements run on the calling thread before the
     * value is propagated — same trade-off as the on-thread trampoline.
     *
     * <p>Returns {@link #SM_NO_MATCH} if no SM_STACK frame matches.
     */
    public static Object fireOpToSM(String effectName, String opName,
                                     java.util.List<Object> args) {
        var stack = SM_STACK.get();
        for (var hs : stack) {
            for (int i = hs.size() - 1; i >= 0; i--) {
                CompiledHandler h = hs.get(i);
                if (h.effectName.equals(effectName)) {
                    IrijFn clause = h.clauses.get(opName);
                    if (clause == null) {
                        throw new dev.irij.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + opName);
                    }
                    final Object[] resumeBox = {null};
                    final boolean[] resumed = {false};
                    IrijFn resumeFn = (resumeArgs) -> {
                        if (resumed[0]) {
                            throw new dev.irij.IrijRuntimeError(
                                    "resume called twice (one-shot)");
                        }
                        resumed[0] = true;
                        Object v = resumeArgs.length > 0
                                ? resumeArgs[0]
                                : dev.irij.interpreter.Values.UNIT;
                        resumeBox[0] = v;
                        return v;
                    };
                    Object[] clauseArgs = new Object[args.size() + 1];
                    for (int j = 0; j < args.size(); j++) clauseArgs[j] = args.get(j);
                    clauseArgs[args.size()] = resumeFn;
                    Object clauseRet = clause.apply(clauseArgs);
                    if (resumed[0]) return resumeBox[0];
                    // Abort path: clause never resumed. Best we can do
                    // synchronously is propagate the abort value as a
                    // RuntimeException so the calling fn (or fiber)
                    // can decide what to do.
                    throw new dev.irij.IrijRuntimeError(
                            "SM clause aborted from synchronous-perform context: "
                                    + clauseRet);
                }
            }
        }
        return SM_NO_MATCH;
    }

    /**
     * Re-entrant {@code runWithSM}: if {@code k} has already partly executed
     * (state != 0), thread {@code reentryValue} as the resume value rather
     * than starting fresh with null. Used by nested-SM-`with` lowering so
     * an outer-handled signal can hand its resume value back into the inner
     * body's saved continuation.
     */
    public static Object runWithSM(Object handlerObj, IrijContinuation k,
                                    Object reentryValue) {
        java.util.List<CompiledHandler> hs;
        if (handlerObj instanceof CompiledComposedHandler cc) {
            hs = cc.handlers;
        } else if (handlerObj instanceof CompiledHandler h) {
            hs = java.util.List.of(h);
        } else {
            throw new dev.irij.IrijRuntimeError(
                    "with requires a handler, got " + typeTag(handlerObj));
        }
        return dispatchLoopSM(hs, k, reentryValue);
    }

    /**
     * Helper used by nested-with emission: alloc-or-fetch the inner
     * continuation from {@code kOuter.fields[slot]}. First call initialises
     * the slot with a fresh {@link IrijContinuation}; subsequent calls
     * (after the outer trampoline resumed past an inner-leaked perform)
     * return the same continuation with its state preserved.
     */
    public static IrijContinuation getOrAllocInnerCont(IrijContinuation kOuter,
                                                       int slot,
                                                       IrijFn step,
                                                       int nFields) {
        Object existing = kOuter.fields[slot];
        if (existing != null) return (IrijContinuation) existing;
        IrijContinuation kInner = new IrijContinuation(step, nFields);
        kOuter.fields[slot] = kInner;
        return kInner;
    }

    public static Object runWithSM(Object handlerObj, IrijContinuation k) {
        return runWithSM(handlerObj, k, null);
    }

    /**
     * Trampolined dispatch loop — runs the body to completion under a stack
     * of SM handlers without growing the JVM stack per {@code perform}.
     *
     * <p>Each iteration enters the body via {@code k.resume(v)}. If it
     * returns, the body finished and the value bubbles out. If it throws
     * {@link PerformSignal}, look up the matching handler innermost-first;
     * the synthesised {@code resumeFn} unwinds the clause via
     * {@link TailResume} so the loop re-enters with the resume value rather
     * than via a recursive JVM call.
     *
     * <p>Bridges to threaded outer {@code with}: if no SM handler matches,
     * walk {@link dev.irij.interpreter.EffectSystem#STACK}; if a threaded
     * outer handles this effect, route via {@code fireOp} and continue the
     * loop with the result.
     */
    /** Per-thread stack of active SM dispatch frames — innermost on top.
     *  Lets a clause body's `perform` (tier-c) find a matching handler in
     *  any enclosing SM frame even though the inner clause's own dispatch
     *  loop has hs=[]. Existing nested-SM still benefits as a defensive
     *  fallback: if an inner loop sees an unmatched signal, it can dispatch
     *  via an outer frame's hs without unwinding back to that frame. */
    private static final ThreadLocal<java.util.Deque<java.util.List<CompiledHandler>>>
            SM_STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private static Object dispatchLoopSM(java.util.List<CompiledHandler> hs,
                                         IrijContinuation k,
                                         Object reentryValue) {
        var stack = SM_STACK.get();
        stack.push(hs);
        try {
            return dispatchLoopSMImpl(hs, k, reentryValue, stack);
        } finally {
            stack.pop();
        }
    }

    private static Object dispatchLoopSMImpl(java.util.List<CompiledHandler> hs,
                                              IrijContinuation k,
                                              Object reentryValue,
                                              java.util.Deque<java.util.List<CompiledHandler>> stack) {
        // First iteration: pass null if the continuation hasn't yet started
        // (state == 0); otherwise thread the externally-supplied reentry
        // value (used by nested-with re-entry).
        Object resumeArg = (k.state == 0) ? null : reentryValue;
        IrijContinuation currentK = k;
        while (true) {
            PerformSignal sig;
            try {
                Object result = currentK.resume(resumeArg);
                return result; // body finished
            } catch (PerformSignal s) {
                sig = s;
            }

            // Snapshot the pooled signal's fields immediately. The pool is
            // thread-local and shared with any nested dispatch loop spawned
            // by the clause we're about to invoke (e.g. a tier-c clause's
            // own perform reuses the same pool slot, overwriting our sig.*
            // before this iteration consumes them).
            final String sigEffectName = sig.effectName;
            final String sigOpName = sig.opName;
            final Object[] sigArgs = sig.args;
            final IrijContinuation sigContinuation = sig.continuation;

            // Find the innermost matching SM handler — own hs first.
            CompiledHandler h = findHandler(hs, sigEffectName);
            // Fallback: walk other frames in SM_STACK (innermost-first,
            // skipping our own frame which is on top). Lets a tier-c
            // clause's perform reach the next-outer SM with's handler.
            if (h == null) {
                boolean skippedSelf = false;
                for (var frame : stack) {
                    if (!skippedSelf) { skippedSelf = true; continue; }
                    h = findHandler(frame, sigEffectName);
                    if (h != null) break;
                }
            }
            if (h == null) {
                // Bridge to threaded outer (EffectSystem.STACK).
                boolean bridged = false;
                var threadedStack = dev.irij.interpreter.EffectSystem.STACK.get();
                for (var ctx : threadedStack) {
                    if (ctx.effectName().equals(sigEffectName)) {
                        resumeArg = dev.irij.interpreter.EffectSystem.fireOp(
                                sigEffectName, sigOpName,
                                java.util.Arrays.asList(sigArgs));
                        currentK = sigContinuation;
                        bridged = true;
                        break;
                    }
                }
                if (bridged) continue;
                throw sig; // truly unhandled
            }

            IrijFn clause = h.clauses.get(sigOpName);
            if (clause == null) {
                throw new dev.irij.IrijRuntimeError(
                        "Handler " + h.name + " has no clause for " + sigOpName);
            }

            // The TailResume thrown by this iteration's resumeFn must
            // target THIS dispatch loop — pinned via sig.continuation. A
            // nested loop catching a TailResume not addressed to it
            // re-throws so the right loop consumes it (matters for native
            // nested-SM and for future tier-c clause-as-SM compilation).
            final IrijContinuation expectedTarget = sigContinuation;
            final var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
            IrijFn resumeFn = (resumeArgs) -> {
                if (!resumed.compareAndSet(false, true)) {
                    throw new dev.irij.IrijRuntimeError(
                            "resume called twice (one-shot continuation)");
                }
                Object v = resumeArgs.length > 0
                        ? resumeArgs[0]
                        : dev.irij.interpreter.Values.UNIT;
                throw TailResume.of(v, expectedTarget);
            };

            Object[] clauseArgs = new Object[sigArgs.length + 1];
            System.arraycopy(sigArgs, 0, clauseArgs, 0, sigArgs.length);
            clauseArgs[sigArgs.length] = resumeFn;

            try {
                Object clauseReturn = clause.apply(clauseArgs);
                // Clause returned without calling resume — abort path; this
                // value is what the `with` evaluates to.
                return clauseReturn;
            } catch (TailResume tr) {
                if (tr.target != expectedTarget) throw tr; // not for me
                resumeArg = tr.value;
                // Resume the body that yielded — sigContinuation may differ
                // from the original `k` if the signal originated in a nested
                // SM frame and we dispatched on its behalf via SM_STACK.
                currentK = sigContinuation;
            }
        }
    }

    private static CompiledHandler findHandler(java.util.List<CompiledHandler> hs,
                                                String effectName) {
        for (int i = hs.size() - 1; i >= 0; i--) {
            if (hs.get(i).effectName.equals(effectName)) return hs.get(i);
        }
        return null;
    }

    // ── Concurrency ─────────────────────────────────────────────────────

    public static final class Fiber {
        public final java.util.concurrent.CompletableFuture<Object> result;
        public final Thread thread;
        Fiber(java.util.concurrent.CompletableFuture<Object> result, Thread thread) {
            this.result = result;
            this.thread = thread;
        }
    }

    /** Snapshot of both the threaded EffectSystem.STACK and the SM_STACK
     *  taken at fork time so the fiber can re-establish the parent's
     *  effect-handling context (both 14c.2 threaded and 14c.3 SM frames). */
    private record ParentSnapshot(
            java.util.Deque<dev.irij.interpreter.EffectSystem.HandlerContext> effectStack,
            java.util.Deque<java.util.List<CompiledHandler>> smStack,
            java.util.Deque<java.util.Set<String>> effectRow,
            java.util.Map<String, Object> namespace,
            java.io.PrintStream sessionOut) {}

    /** Snapshot the current effect stack + push onto the child fiber's stack. */
    private static void inheritEffectStack(java.util.Deque<
            dev.irij.interpreter.EffectSystem.HandlerContext> parentStack) {
        var fiberStack = dev.irij.interpreter.EffectSystem.STACK.get();
        fiberStack.addAll(parentStack);
    }

    /** Inherit the parent's SM dispatch frames so a fiber's body can find
     *  matching SM handlers via the SM_STACK fallback (concurrency parity). */
    private static void inheritSMStack(java.util.Deque<java.util.List<CompiledHandler>> parentSMStack) {
        var fiberSMStack = SM_STACK.get();
        // Push parent frames in OUTER-first order so the fiber's stack
        // mirrors the parent's innermost-on-top ordering.
        var arr = parentSMStack.toArray(new Object[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            var frame = (java.util.List<CompiledHandler>) arr[i];
            fiberSMStack.push(frame);
        }
    }

    private static ParentSnapshot snapParent() {
        return new ParentSnapshot(
                new java.util.ArrayDeque<>(dev.irij.interpreter.EffectSystem.STACK.get()),
                new java.util.ArrayDeque<>(SM_STACK.get()),
                new java.util.ArrayDeque<>(EFFECT_ROW.get()),
                NS.get(),
                SESSION_OUT.get());
    }

    /** Replace the child fiber's effect-row stack with the parent's
     *  snapshot. Run before any other fiber-side code so {@code perform}
     *  and effect-aware builtins see the inherited row. */
    private static void inheritEffectRow(java.util.Deque<java.util.Set<String>> parentRow) {
        var fiberRow = EFFECT_ROW.get();
        fiberRow.clear();
        // parentRow.iterator() returns top-first; we need bottom-first to
        // push correctly. Reverse via toArray.
        var arr = parentRow.toArray(new Object[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            var frame = (java.util.Set<String>) arr[i];
            fiberRow.push(frame);
        }
    }

    private static java.util.Deque<dev.irij.interpreter.EffectSystem.HandlerContext> snapStack() {
        return new java.util.ArrayDeque<>(
                dev.irij.interpreter.EffectSystem.STACK.get());
    }

    /** Spawn a virtual thread running the thunk (IrijFn or BuiltinFn). */
    private static Fiber forkOne(Object thunk, ParentSnapshot parent) {
        var future = new java.util.concurrent.CompletableFuture<Object>();
        var t = Thread.startVirtualThread(() -> {
            inheritEffectStack(parent.effectStack());
            inheritSMStack(parent.smStack());
            inheritEffectRow(parent.effectRow());
            if (parent.namespace() != null) NS.set(parent.namespace());
            if (parent.sessionOut() != null) SESSION_OUT.set(parent.sessionOut());
            try {
                future.complete(callAny(thunk, new Object[0]));
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });
        return new Fiber(future, t);
    }

    /** `spawn thunk` — fire-and-forget vthread, returns the Thread. */
    public static Object spawn(Object thunk) {
        var parent = snapParent();
        return Thread.startVirtualThread(() -> {
            inheritEffectStack(parent.effectStack());
            inheritSMStack(parent.smStack());
            inheritEffectRow(parent.effectRow());
            if (parent.namespace() != null) NS.set(parent.namespace());
            if (parent.sessionOut() != null) SESSION_OUT.set(parent.sessionOut());
            try { callAny(thunk, new Object[0]); }
            catch (Throwable t) {
                java.io.PrintStream err = parent.sessionOut() != null
                        ? parent.sessionOut() : System.err;
                err.println("[spawn] error: " + t.getMessage());
            }
        });
    }

    /** `sleep ms` — blocks the current thread. */
    public static Object sleep(Object msArg) {
        long ms = (msArg instanceof Long l) ? l
                : (msArg instanceof Number n) ? n.longValue()
                : 0L;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return dev.irij.interpreter.Values.UNIT;
    }

    /** `await fiber` — block until fiber completes. */
    public static Object await(Object fiberArg) {
        if (!(fiberArg instanceof Fiber f)) {
            throw new dev.irij.IrijRuntimeError(
                    "await expects a Fiber, got " + typeTag(fiberArg));
        }
        try { return f.result.join(); }
        catch (java.util.concurrent.CompletionException ce) {
            throw runtimeFrom(ce.getCause(), "Fiber failed");
        }
    }

    /** `par combiner thunk1 thunk2 ...` → combiner result1 result2 ... */
    public static Object par(Object[] args) {
        if (args.length < 2) {
            throw new dev.irij.IrijRuntimeError(
                    "par requires a combiner function and at least one thunk");
        }
        Object combiner = args[0];
        int n = args.length - 1;
        var parent = snapParent();
        var fibers = new Fiber[n];
        for (int i = 0; i < n; i++) fibers[i] = forkOne(args[i + 1], parent);
        var results = new Object[n];
        try {
            for (int i = 0; i < n; i++) results[i] = fibers[i].result.join();
        } catch (java.util.concurrent.CompletionException ce) {
            for (var f : fibers) f.thread.interrupt();
            throw runtimeFrom(ce.getCause(), "par failed");
        }
        return callAny(combiner, results);
    }

    /** `race thunk1 thunk2 ...` — first to succeed wins; others interrupted. */
    public static Object race(Object[] args) {
        if (args.length == 0) {
            throw new dev.irij.IrijRuntimeError(
                    "race requires at least one thunk");
        }
        var parent = snapParent();
        var fibers = new Fiber[args.length];
        for (int i = 0; i < args.length; i++) fibers[i] = forkOne(args[i], parent);

        var winner = new java.util.concurrent.CompletableFuture<Object>();
        var errors = java.util.Collections.synchronizedList(
                new java.util.ArrayList<Throwable>());
        for (Fiber f : fibers) {
            f.result.whenComplete((v, ex) -> {
                if (ex != null) {
                    errors.add(ex);
                    if (errors.size() == fibers.length) {
                        winner.completeExceptionally(errors.get(0));
                    }
                } else if (winner.complete(v)) {
                    for (Fiber other : fibers) {
                        if (other.thread.isAlive()) other.thread.interrupt();
                    }
                }
            });
        }
        try { return winner.join(); }
        catch (java.util.concurrent.CompletionException ce) {
            for (var f : fibers) f.thread.interrupt();
            throw runtimeFrom(ce.getCause(), "race: all thunks failed");
        }
    }

    /** `timeout ms thunk` — run thunk in vthread, interrupt after deadline. */
    public static Object timeout(Object msArg, Object thunk) {
        long ms = (msArg instanceof Long l) ? l
                : (msArg instanceof Number n) ? n.longValue()
                : 0L;
        var parent = snapParent();
        Fiber f = forkOne(thunk, parent);
        try {
            return f.result.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.thread.interrupt();
            throw new dev.irij.IrijRuntimeError(
                    "timeout: operation exceeded " + ms + "ms");
        } catch (java.util.concurrent.ExecutionException e) {
            throw runtimeFrom(e.getCause(), "timeout");
        } catch (InterruptedException e) {
            f.thread.interrupt();
            Thread.currentThread().interrupt();
            throw new dev.irij.IrijRuntimeError("timeout: interrupted");
        }
    }

    // ── Effect-row runtime enforcement ──────────────────────────────
    //
    // Mirrors the interpreter's AVAILABLE_EFFECTS stack so bytecode-mode
    // honors declared fn rows at runtime. Pushed/popped at fn entry
    // and at `with` block entry; checked at every `perform`. An
    // AMBIENT sentinel (identity-comparable, contains-always-true)
    // sits at the bottom so top-level statements see all effects.
    private static final java.util.Set<String> EFFECT_AMBIENT = new java.util.HashSet<>() {
        @Override public boolean contains(Object o) { return true; }
    };

    private static final ThreadLocal<java.util.Deque<java.util.Set<String>>> EFFECT_ROW =
            ThreadLocal.withInitial(() -> {
                var d = new java.util.ArrayDeque<java.util.Set<String>>();
                d.push(EFFECT_AMBIENT);
                return d;
            });

    /** Push a fn's declared effect row. {@code declared==null} means
     *  unannotated → strict pure (empty set). Pass an empty array for
     *  explicit `::: ` (also pure). Use {@link #enterFnAmbient()} when
     *  the row contains {@code Any} or a row-variable. */
    public static void enterFn(String[] declared) {
        var top = EFFECT_ROW.get().peek();
        if (top == EFFECT_AMBIENT && declared != null) {
            // Inside ambient context: still respect the declared row
            // (this is how the interpreter restricts inner fns).
        }
        java.util.Set<String> next = new java.util.HashSet<>();
        if (declared != null) {
            for (String e : declared) next.add(e);
        }
        EFFECT_ROW.get().push(next);
    }

    /** Push an ambient frame — fn body inherits caller's effects. Used
     *  for {@code ::: Any} and parametric row-variables. */
    public static void enterFnAmbient() {
        EFFECT_ROW.get().push(EFFECT_AMBIENT);
    }

    public static void exitFn() {
        EFFECT_ROW.get().pop();
    }

    /** Push a new frame that's the top frame ∪ the named effect. Used
     *  by every {@code with handler} body so its statements see the
     *  effect the handler provides. */
    public static void enterWith(String effectName) {
        var top = EFFECT_ROW.get().peek();
        if (top == EFFECT_AMBIENT) {
            EFFECT_ROW.get().push(EFFECT_AMBIENT);
            return;
        }
        java.util.Set<String> expanded = new java.util.HashSet<>(top);
        if (effectName != null) expanded.add(effectName);
        EFFECT_ROW.get().push(expanded);
    }

    public static void exitWith() {
        EFFECT_ROW.get().pop();
    }

    /** Called at every {@code perform} site. Throws if the effect
     *  isn't in the enclosing fn's declared row. */
    public static void checkPerformEffect(String effectName, String opName) {
        if (effectName == null) return;
        var top = EFFECT_ROW.get().peek();
        if (top.contains(effectName)) return;
        throw new dev.irij.IrijRuntimeError(
                "Effect '" + effectName + "' not declared: '" + opName
                        + "' requires ::: " + effectName
                        + " in enclosing function's effect row");
    }

    /** `try thunk` — return Ok(result) / Err(msg). */
    public static Object tryFn(Object thunk) {
        try {
            Object r = callAny(thunk, new Object[0]);
            return new dev.irij.interpreter.Values.Tagged("Ok", java.util.List.of(r));
        } catch (dev.irij.IrijRuntimeError ex) {
            return new dev.irij.interpreter.Values.Tagged("Err",
                    java.util.List.of(ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    private static dev.irij.IrijRuntimeError runtimeFrom(Throwable cause, String prefix) {
        if (cause instanceof dev.irij.IrijRuntimeError ire) return ire;
        String msg = cause == null ? prefix : (prefix + ": " + cause.getMessage());
        return new dev.irij.IrijRuntimeError(msg);
    }

    /**
     * Compiled scope handle — bound to a name inside a `scope { ... }` block.
     * `handle.fork thunk` spawns a fiber tied to this scope; join semantics
     * run after the block body via {@link #joinByModifier}.
     */
    public static final class CompiledScopeHandle {
        public final String modifier; // null | "race" | "supervised"
        private final java.util.List<Fiber> fibers =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final ParentSnapshot parent;

        public CompiledScopeHandle(String modifier) {
            this.modifier = modifier;
            this.parent = snapParent();
        }

        /** Reflection target for `handle.fork thunk`. */
        public Object fork(Object thunk) {
            Fiber f = forkOne(thunk, parent);
            fibers.add(f);
            return f;
        }

        public Object joinByModifier(Object bodyResult) {
            if (modifier == null) return joinAll(bodyResult);
            return switch (modifier) {
                case "race" -> joinRace(bodyResult);
                case "supervised" -> joinSupervised(bodyResult);
                default -> throw new dev.irij.IrijRuntimeError(
                        "Unknown scope modifier: " + modifier);
            };
        }

        public Object cancelAll() {
            for (Fiber f : fibers) f.thread.interrupt();
            for (Fiber f : fibers) { try { f.result.join(); } catch (Exception ignored) {} }
            return dev.irij.interpreter.Values.UNIT;
        }

        private Object joinAll(Object bodyResult) {
            dev.irij.IrijRuntimeError firstErr = null;
            for (Fiber f : fibers) {
                try { f.result.join(); }
                catch (java.util.concurrent.CompletionException ce) {
                    if (firstErr == null) {
                        for (Fiber g : fibers) g.thread.interrupt();
                        firstErr = runtimeFrom(ce.getCause(), "Fiber failed");
                    }
                }
            }
            if (firstErr != null) throw firstErr;
            return bodyResult;
        }

        private Object joinRace(Object bodyResult) {
            if (fibers.isEmpty()) return bodyResult;
            var winner = new java.util.concurrent.CompletableFuture<Object>();
            var errors = java.util.Collections.synchronizedList(
                    new java.util.ArrayList<Throwable>());
            for (Fiber f : fibers) {
                f.result.whenComplete((v, ex) -> {
                    if (ex != null) {
                        errors.add(ex);
                        if (errors.size() == fibers.size()) {
                            winner.completeExceptionally(errors.get(0));
                        }
                    } else if (winner.complete(v)) {
                        for (Fiber g : fibers) {
                            if (g.thread.isAlive()) g.thread.interrupt();
                        }
                    }
                });
            }
            try { return winner.join(); }
            catch (java.util.concurrent.CompletionException ce) {
                for (Fiber g : fibers) g.thread.interrupt();
                throw runtimeFrom(ce.getCause(), "scope.race: all fibers failed");
            }
        }

        private Object joinSupervised(Object bodyResult) {
            for (Fiber f : fibers) {
                try { f.result.join(); }
                catch (java.util.concurrent.CompletionException ignored) {
                    // per-fiber isolation — siblings keep running
                }
            }
            return bodyResult;
        }
    }

    // ── Hot-redef: invokedynamic + MutableCallSite (Clojure-style) ──────
    //
    // Each top-level `fn` call site emits an `invokedynamic` whose
    // bootstrap returns a {@link MutableCallSite} pointing at the impl
    // method. The REPL (or any embedder) can swap that callsite's target
    // via {@link #redefine}. With {@code --direct-linking} the emitter
    // skips indy and uses plain {@code invokestatic} for max JIT
    // inlinability — same trade-off Clojure exposes.

    /** Registry of mutable call sites keyed by "owner.method:descriptor". */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.lang.invoke.MutableCallSite> REDEF_SITES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String redefKey(Class<?> owner, String name,
                                    java.lang.invoke.MethodType mt) {
        return owner.getName() + "." + name + ":" + mt.toMethodDescriptorString();
    }

    /**
     * Bootstrap method for the hot-redef invokedynamic. The {@code name}
     * is the mangled fn name (already a valid Java identifier); {@code mt}
     * is the method type. The bootstrap looks up the static impl on the
     * caller's class, registers a MutableCallSite for it, and returns it.
     *
     * <p>If the same call site is requested twice (e.g. two source files
     * each calling the same fn), each gets its own MutableCallSite — they
     * happen to share the impl. {@link #redefine} updates them all via the
     * registry's collision list.
     */
    public static java.lang.invoke.CallSite redefBootstrap(
            java.lang.invoke.MethodHandles.Lookup lookup,
            String name,
            java.lang.invoke.MethodType mt) throws NoSuchMethodException,
                                                    IllegalAccessException {
        Class<?> owner = lookup.lookupClass();
        java.lang.invoke.MethodHandle target = lookup.findStatic(owner, name, mt);
        java.lang.invoke.MutableCallSite cs = new java.lang.invoke.MutableCallSite(target);
        REDEF_SITES.put(redefKey(owner, name, mt), cs);
        return cs;
    }

    /**
     * Swap the implementation of a previously-bootstrapped redef site.
     * The {@code key} is the {@code "owner.method:descriptor"} string
     * matching what the bootstrap registered. Subsequent calls through
     * the indy site dispatch to {@code newImpl}.
     *
     * <p>Visible to the nREPL / embedder. Returns {@code true} if a site
     * was found and updated, {@code false} otherwise.
     */
    public static boolean redefine(String key, java.lang.invoke.MethodHandle newImpl) {
        java.lang.invoke.MutableCallSite cs = REDEF_SITES.get(key);
        if (cs == null) return false;
        cs.setTarget(newImpl);
        java.lang.invoke.MutableCallSite.syncAll(new java.lang.invoke.MutableCallSite[]{cs});
        return true;
    }

    /** Test/inspection helper — number of registered redef sites. */
    public static int redefSiteCount() {
        return REDEF_SITES.size();
    }
}
