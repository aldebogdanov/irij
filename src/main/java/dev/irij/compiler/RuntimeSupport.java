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

    /**
     * Dispatch a call against any runtime "callable": IrijFn (compiled
     * lambdas), interpreter BuiltinFn (Java interop refs from
     * Class/member or obj.method), or interpreter Closure.
     */
    public static Object callAny(Object fn, Object[] args) {
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
        return dev.irij.interpreter.JavaInterop.resolveInstanceRef(recv, member);
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
        throw new dev.irij.interpreter.IrijRuntimeError(
                "len: expected Str/Vector/Map/Tuple, got " + typeTag(v));
    }

    /** `nth coll i` — element at index (works on Vector or String). */
    public static Object nth(Object v, Object iBoxed) {
        int i = (int) ((Long) iBoxed).longValue();
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            return vec.elements().get(i);
        }
        if (v instanceof String s) return String.valueOf(s.charAt(i));
        throw new dev.irij.interpreter.IrijRuntimeError(
                "nth: expected Vector or Str, got " + typeTag(v));
    }

    /** `conj v x` — append x, return new vector (immutable semantics). */
    public static Object conj(Object v, Object x) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var out = new java.util.ArrayList<>(vec.elements());
            out.add(x);
            return new dev.irij.interpreter.Values.IrijVector(out);
        }
        throw new dev.irij.interpreter.IrijRuntimeError(
                "conj: expected Vector, got " + typeTag(v));
    }

    /** `empty? x` — true if String/Vector/Map/Tuple is empty, or null. */
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
        throw new dev.irij.interpreter.IrijRuntimeError(
                "empty?: expected Str/Vector/Map/Tuple, got " + typeTag(v));
    }

    /** `head v` — first element of a non-empty vector. */
    public static Object head(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                throw new dev.irij.interpreter.IrijRuntimeError("head: empty vector");
            }
            return es.get(0);
        }
        throw new dev.irij.interpreter.IrijRuntimeError(
                "head: expected Vector, got " + typeTag(v));
    }

    /** `tail v` — vector without first element (immutable). */
    public static Object tail(Object v) {
        if (v instanceof dev.irij.interpreter.Values.IrijVector vec) {
            var es = vec.elements();
            if (es.isEmpty()) {
                return new dev.irij.interpreter.Values.IrijVector(new java.util.ArrayList<>());
            }
            return new dev.irij.interpreter.Values.IrijVector(
                    new java.util.ArrayList<>(es.subList(1, es.size())));
        }
        throw new dev.irij.interpreter.IrijRuntimeError(
                "tail: expected Vector, got " + typeTag(v));
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

    /** Flatten and build a composed handler from two operand values. */
    public static Object compose(Object left, Object right) {
        java.util.List<CompiledHandler> all = new java.util.ArrayList<>();
        appendHandlers(left, all);
        appendHandlers(right, all);
        return new CompiledComposedHandler(java.util.List.copyOf(all));
    }

    private static void appendHandlers(Object v, java.util.List<CompiledHandler> out) {
        if (v instanceof CompiledHandler h) out.add(h);
        else if (v instanceof CompiledComposedHandler c) out.addAll(c.handlers);
        else throw new dev.irij.interpreter.IrijRuntimeError(
                ">> requires handler operands, got " + typeTag(v));
    }

    /** Emitted call-site for effect ops. Routes through EffectSystem.fireOp. */
    public static Object perform(String effectName, String opName, Object[] args) {
        return dev.irij.interpreter.EffectSystem.fireOp(
                effectName, opName, java.util.Arrays.asList(args));
    }

    /** `error "msg"` builtin — throws IrijRuntimeError, caught by `on-failure`. */
    public static Object errorBuiltin(Object msg) {
        throw new dev.irij.interpreter.IrijRuntimeError(
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
            throw new dev.irij.interpreter.IrijRuntimeError(
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
                throw new dev.irij.interpreter.IrijRuntimeError("Handler loop interrupted");
            }
            switch (msg) {
                case dev.irij.interpreter.EffectSystem.EffectMessage.Done d -> {
                    return d.value();
                }
                case dev.irij.interpreter.EffectSystem.EffectMessage.Err e -> {
                    Throwable t = e.error();
                    if (t instanceof dev.irij.interpreter.IrijRuntimeError ire) throw ire;
                    if (t instanceof RuntimeException re) throw re;
                    throw new dev.irij.interpreter.IrijRuntimeError(
                            "Effect body error: " + t.getMessage());
                }
                case dev.irij.interpreter.EffectSystem.EffectMessage.Op op -> {
                    IrijFn clause = h.clauses.get(op.opName());
                    if (clause == null) {
                        throw new dev.irij.interpreter.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + op.opName());
                    }
                    var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    IrijFn resumeFn = (resumeArgs) -> {
                        if (!resumed.compareAndSet(false, true)) {
                            throw new dev.irij.interpreter.IrijRuntimeError(
                                    "resume called twice (one-shot continuation)");
                        }
                        try {
                            op.resumeChannel().put(resumeArgs.length > 0
                                    ? resumeArgs[0]
                                    : dev.irij.interpreter.Values.UNIT);
                            return runHandlerLoop(h, opChannel);
                        } catch (InterruptedException e2) {
                            Thread.currentThread().interrupt();
                            throw new dev.irij.interpreter.IrijRuntimeError(
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
                        throw new dev.irij.interpreter.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + opName);
                    }
                    final Object[] resumeBox = {null};
                    final boolean[] resumed = {false};
                    IrijFn resumeFn = (resumeArgs) -> {
                        if (resumed[0]) {
                            throw new dev.irij.interpreter.IrijRuntimeError(
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
                    throw new dev.irij.interpreter.IrijRuntimeError(
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
            throw new dev.irij.interpreter.IrijRuntimeError(
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

            // Find the innermost matching SM handler — own hs first.
            CompiledHandler h = findHandler(hs, sig.effectName);
            // Fallback: walk other frames in SM_STACK (innermost-first,
            // skipping our own frame which is on top). Lets a tier-c
            // clause's perform reach the next-outer SM with's handler.
            if (h == null) {
                boolean skippedSelf = false;
                for (var frame : stack) {
                    if (!skippedSelf) { skippedSelf = true; continue; }
                    h = findHandler(frame, sig.effectName);
                    if (h != null) break;
                }
            }
            if (h == null) {
                // Bridge to threaded outer (EffectSystem.STACK).
                boolean bridged = false;
                var threadedStack = dev.irij.interpreter.EffectSystem.STACK.get();
                for (var ctx : threadedStack) {
                    if (ctx.effectName().equals(sig.effectName)) {
                        resumeArg = dev.irij.interpreter.EffectSystem.fireOp(
                                sig.effectName, sig.opName,
                                java.util.Arrays.asList(sig.args));
                        currentK = sig.continuation;
                        bridged = true;
                        break;
                    }
                }
                if (bridged) continue;
                throw sig; // truly unhandled
            }

            IrijFn clause = h.clauses.get(sig.opName);
            if (clause == null) {
                throw new dev.irij.interpreter.IrijRuntimeError(
                        "Handler " + h.name + " has no clause for " + sig.opName);
            }

            // The TailResume thrown by this iteration's resumeFn must
            // target THIS dispatch loop — pinned via sig.continuation. A
            // nested loop catching a TailResume not addressed to it
            // re-throws so the right loop consumes it (matters for native
            // nested-SM and for future tier-c clause-as-SM compilation).
            final IrijContinuation expectedTarget = sig.continuation;
            final var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
            IrijFn resumeFn = (resumeArgs) -> {
                if (!resumed.compareAndSet(false, true)) {
                    throw new dev.irij.interpreter.IrijRuntimeError(
                            "resume called twice (one-shot continuation)");
                }
                Object v = resumeArgs.length > 0
                        ? resumeArgs[0]
                        : dev.irij.interpreter.Values.UNIT;
                throw TailResume.of(v, expectedTarget);
            };

            Object[] clauseArgs = new Object[sig.args.length + 1];
            System.arraycopy(sig.args, 0, clauseArgs, 0, sig.args.length);
            clauseArgs[sig.args.length] = resumeFn;

            try {
                Object clauseReturn = clause.apply(clauseArgs);
                // Clause returned without calling resume — abort path; this
                // value is what the `with` evaluates to.
                return clauseReturn;
            } catch (TailResume tr) {
                if (tr.target != expectedTarget) throw tr; // not for me
                resumeArg = tr.value;
                // Resume the body that yielded — sig.continuation may differ
                // from the original `k` if the signal originated in a nested
                // SM frame and we dispatched on its behalf via SM_STACK.
                currentK = sig.continuation;
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
            java.util.Deque<java.util.List<CompiledHandler>> smStack) {}

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
                new java.util.ArrayDeque<>(SM_STACK.get()));
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
            try { callAny(thunk, new Object[0]); }
            catch (Throwable t) { System.err.println("[spawn] error: " + t.getMessage()); }
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
            throw new dev.irij.interpreter.IrijRuntimeError(
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
            throw new dev.irij.interpreter.IrijRuntimeError(
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
            throw new dev.irij.interpreter.IrijRuntimeError(
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
            throw new dev.irij.interpreter.IrijRuntimeError(
                    "timeout: operation exceeded " + ms + "ms");
        } catch (java.util.concurrent.ExecutionException e) {
            throw runtimeFrom(e.getCause(), "timeout");
        } catch (InterruptedException e) {
            f.thread.interrupt();
            Thread.currentThread().interrupt();
            throw new dev.irij.interpreter.IrijRuntimeError("timeout: interrupted");
        }
    }

    /** `try thunk` — return Ok(result) / Err(msg). */
    public static Object tryFn(Object thunk) {
        try {
            Object r = callAny(thunk, new Object[0]);
            return new dev.irij.interpreter.Values.Tagged("Ok", java.util.List.of(r));
        } catch (dev.irij.interpreter.IrijRuntimeError ex) {
            return new dev.irij.interpreter.Values.Tagged("Err",
                    java.util.List.of(ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    private static dev.irij.interpreter.IrijRuntimeError runtimeFrom(Throwable cause, String prefix) {
        if (cause instanceof dev.irij.interpreter.IrijRuntimeError ire) return ire;
        String msg = cause == null ? prefix : (prefix + ": " + cause.getMessage());
        return new dev.irij.interpreter.IrijRuntimeError(msg);
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
                default -> throw new dev.irij.interpreter.IrijRuntimeError(
                        "Unknown scope modifier: " + modifier);
            };
        }

        public Object cancelAll() {
            for (Fiber f : fibers) f.thread.interrupt();
            for (Fiber f : fibers) { try { f.result.join(); } catch (Exception ignored) {} }
            return dev.irij.interpreter.Values.UNIT;
        }

        private Object joinAll(Object bodyResult) {
            dev.irij.interpreter.IrijRuntimeError firstErr = null;
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
