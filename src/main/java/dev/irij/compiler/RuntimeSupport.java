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

    public static Object runWithSM(Object handlerObj, IrijContinuation k) {
        if (handlerObj instanceof CompiledComposedHandler cc) {
            return runWithSMComposed(cc.handlers, 0, k);
        }
        if (!(handlerObj instanceof CompiledHandler h)) {
            throw new dev.irij.interpreter.IrijRuntimeError(
                    "with requires a handler, got " + typeTag(handlerObj));
        }
        try {
            return k.resume(null);
        } catch (PerformSignal s) {
            return dispatchSM(h, s);
        }
    }

    /** Composed handler path — nested runWithSM frames, outermost first. */
    private static Object runWithSMComposed(
            java.util.List<CompiledHandler> handlers, int idx, IrijContinuation k) {
        if (idx >= handlers.size()) return k.resume(null);
        IrijFn step = args -> runWithSMComposed(handlers, idx + 1, k);
        IrijContinuation nested = new IrijContinuation(step, 0);
        return runWithSM(handlers.get(idx), nested);
    }

    /** Dispatch a single signal to the right clause; recurse if the clause resumes. */
    private static Object dispatchSM(CompiledHandler h, PerformSignal s) {
        if (!h.effectName.equals(s.effectName)) {
            // Outer handler's responsibility; re-raise (fields intact).
            throw s;
        }
        IrijFn clause = h.clauses.get(s.opName);
        if (clause == null) {
            throw new dev.irij.interpreter.IrijRuntimeError(
                    "Handler " + h.name + " has no clause for " + s.opName);
        }
        final IrijContinuation k = s.continuation;
        final var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);

        IrijFn resumeFn = (resumeArgs) -> {
            if (!resumed.compareAndSet(false, true)) {
                throw new dev.irij.interpreter.IrijRuntimeError(
                        "resume called twice (one-shot continuation)");
            }
            Object v = resumeArgs.length > 0
                    ? resumeArgs[0]
                    : dev.irij.interpreter.Values.UNIT;
            // Re-enter the body state machine. May return (body done) or
            // throw another PerformSignal (next op) which this same handler
            // (or an outer one) must catch.
            try {
                return k.resume(v);
            } catch (PerformSignal next) {
                return dispatchSM(h, next);
            }
        };

        Object[] clauseArgs = new Object[s.args.length + 1];
        System.arraycopy(s.args, 0, clauseArgs, 0, s.args.length);
        clauseArgs[s.args.length] = resumeFn;
        return clause.apply(clauseArgs);
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

    /** Snapshot the current effect stack + push onto the child fiber's stack. */
    private static void inheritEffectStack(java.util.Deque<
            dev.irij.interpreter.EffectSystem.HandlerContext> parentStack) {
        var fiberStack = dev.irij.interpreter.EffectSystem.STACK.get();
        fiberStack.addAll(parentStack);
    }

    private static java.util.Deque<dev.irij.interpreter.EffectSystem.HandlerContext> snapStack() {
        return new java.util.ArrayDeque<>(
                dev.irij.interpreter.EffectSystem.STACK.get());
    }

    /** Spawn a virtual thread running the thunk (IrijFn or BuiltinFn). */
    private static Fiber forkOne(Object thunk,
            java.util.Deque<dev.irij.interpreter.EffectSystem.HandlerContext> parentStack) {
        var future = new java.util.concurrent.CompletableFuture<Object>();
        var t = Thread.startVirtualThread(() -> {
            inheritEffectStack(parentStack);
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
        var parentStack = snapStack();
        return Thread.startVirtualThread(() -> {
            inheritEffectStack(parentStack);
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
        var parentStack = snapStack();
        var fibers = new Fiber[n];
        for (int i = 0; i < n; i++) fibers[i] = forkOne(args[i + 1], parentStack);
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
        var parentStack = snapStack();
        var fibers = new Fiber[args.length];
        for (int i = 0; i < args.length; i++) fibers[i] = forkOne(args[i], parentStack);

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
        var parentStack = snapStack();
        Fiber f = forkOne(thunk, parentStack);
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
        private final java.util.Deque<dev.irij.interpreter.EffectSystem.HandlerContext> parentStack;

        public CompiledScopeHandle(String modifier) {
            this.modifier = modifier;
            this.parentStack = snapStack();
        }

        /** Reflection target for `handle.fork thunk`. */
        public Object fork(Object thunk) {
            Fiber f = forkOne(thunk, parentStack);
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
}
