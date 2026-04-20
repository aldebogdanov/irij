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
}
