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
                && args.length == 1 && args[0] == dev.irij.runtime.Values.UNIT) {
            return cf.apply(new Object[0]);
        }
        if (fn instanceof IrijFn f) return f.apply(args);
        if (fn instanceof dev.irij.runtime.Values.BuiltinFn bf) {
            return bf.apply(java.util.Arrays.asList(args));
        }
        throw new IllegalArgumentException("Not callable: " + display(fn));
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
        if (v == null) return dev.irij.runtime.Values.toIrijString(dev.irij.runtime.Values.UNIT);
        return dev.irij.runtime.Values.toIrijString(v);
    }

    public static String toStr(Object v) { return display(v); }

    public static IllegalStateException noMatch(Object v) {
        return new IllegalStateException("No match arm for: " + display(v));
    }

    public static Object typeOf(Object v) {
        return typeTag(v);
    }

    /** `validate spec-name value` — returns Ok(v) on pass, Err(msg)
     *  on failure. Mirrors the interpreter's `validate` builtin. */
    public static Object validate(Object specNameArg, Object value) {
        String name = RtStrings.asStr(specNameArg, "validate");
        try {
            Object result = dev.irij.compiler.SpecValidator.validate(
                    value, new dev.irij.ast.SpecExpr.Name(name));
            return new dev.irij.runtime.Values.Tagged(
                    "Ok", java.util.List.of(result));
        } catch (dev.irij.IrijRuntimeError e) {
            return new dev.irij.runtime.Values.Tagged(
                    "Err", java.util.List.of(e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** `validate! spec-name value` — returns value on pass, throws on fail. */
    /** Start a record-update: clone base IrijMap's entries into a new
     *  LinkedHashMap, returning that. Bytecode emitter then puts each
     *  updated field and wraps in IrijMap. */
    public static java.util.LinkedHashMap<String, Object> recordUpdateBegin(Object base) {
        if (!(base instanceof dev.irij.runtime.Values.IrijMap bm)) {
            throw new dev.irij.IrijRuntimeError(
                    "Record update requires a Map, got " + typeTag(base));
        }
        return new java.util.LinkedHashMap<>(bm.entries());
    }

    public static Object validateBang(Object specNameArg, Object value) {
        String name = RtStrings.asStr(specNameArg, "validate!");
        return dev.irij.compiler.SpecValidator.validate(
                value, new dev.irij.ast.SpecExpr.Name(name));
    }

    // Math constants — boxed once. Bytecode emitVarLoad sees `pi`
    // and `e` Var references and emits GETSTATIC against these
    // fields. Interpreter binds them as raw doubles in globalEnv.
    public static final Object PI_BOXED = Double.valueOf(Math.PI);
    public static final Object E_BOXED  = Double.valueOf(Math.E);

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

    public static final IrijFn LENGTH      = args -> RtCollections.length(args[0]);
    public static final IrijFn HEAD        = args -> RtCollections.head(args[0]);
    public static final IrijFn TAIL        = args -> RtCollections.tail(args[0]);
    public static final IrijFn EMPTY_Q     = args -> RtCollections.isEmpty(args[0]);
    public static final IrijFn TO_STR      = args -> toStr(args[0]);
    public static final IrijFn NOT_FN      = args -> !RtOps.truthy(args[0]);
    public static final IrijFn TYPE_OF     = args -> typeTag(args[0]);
    public static final IrijFn ABS_FN      = args -> RtMath.abs(args[0]);
    public static final IrijFn SQRT_FN     = args -> RtMath.sqrt(args[0]);
    public static final IrijFn FLOOR_FN    = args -> RtMath.floor(args[0]);
    public static final IrijFn CEIL_FN     = args -> RtMath.ceil(args[0]);
    public static final IrijFn ROUND_FN    = args -> RtMath.round(args[0]);
    public static final IrijFn REVERSE_FN  = args -> RtCollections.reverseVal(args[0]);
    public static final IrijFn SORT_FN     = args -> RtCollections.sortVal(args[0]);
    public static final IrijFn PRINTLN_FN  = args -> { println(args[0]); return dev.irij.runtime.Values.UNIT; };
    public static final IrijFn PRINT_FN    = args -> { print(args[0]); return dev.irij.runtime.Values.UNIT; };

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
        dev.irij.runtime.Environment env =
                new dev.irij.runtime.Environment(null);
        dev.irij.runtime.Builtins.install(env, System.out, null);
        for (var entry : env.getBindings().entrySet()) {
            String name = entry.getKey();
            var cell = entry.getValue();
            Object value = unwrapCell(cell);
            if (value instanceof dev.irij.runtime.Values.BuiltinFn bf) {
                out.put(name, args ->
                        bf.apply(java.util.Arrays.asList(args)));
            }
        }
        return out;
    }

    private static Object unwrapCell(dev.irij.runtime.Environment.Cell c) {
        if (c instanceof dev.irij.runtime.Environment.ImmutableCell ic) {
            return ic.value();
        }
        if (c instanceof dev.irij.runtime.Environment.MutableCell mc) {
            return mc.get();
        }
        return null;
    }

    // ── Misc ─────────────────────────────────────────────────────────

    public static void dbg(Object v) {
        System.err.println("[dbg] " + display(v));
    }

    public static Object printlnVal(Object v) {
        println(v);
        return dev.irij.runtime.Values.UNIT;
    }

    // rawHttpRequest removed phase 3b — Http effect now routes through
    // `cap http-client :: Http = "dev.irij.runtime.HttpClientCapability"`
    // in std/http.irj.

    public static Object readLine() {
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        } catch (java.io.IOException e) {
            throw new dev.irij.IrijRuntimeError("read-line: " + e.getMessage());
        }
    }

    // ── SQLite raw-db-* — REMOVED phase 3a ──────────────────────────────
    //
    // The Db effect surface (`db-open`, `db-close`, `db-query`,
    // `db-exec`, `db-transaction`) is now routed through
    // `cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"` in
    // std/db.irj. The provider class owns every JDBC body + the
    // param/connection extraction helpers. The old `rawDb*` statics
    // here, the `raw-db-*` Builtins.install entries, and the
    // matching emit-table entries in ClassEmitter were all deleted —
    // single canonical path through the cap, no more two-paths-to-
    // the-same-op footgun.

    // SSE raw-sse-* methods removed phase 3c — now in ServeCapability.

    // Multipart raw-multipart-* methods removed phase 3d — now in
    // ServeCapability (multipart parsing is request-shaped, same
    // cap as the rest of the server-side ops).

    // ── HTTP server raw-http-serve — REMOVED phase 3c ──────────────────
    //
    // The server loop + static-file dispatch + request/response shaping
    // + SSE writer ops all live in ServeCapability now. std/serve.irj
    // exposes them as effect ops on Serve and routes through the cap.

    /** Runtime type tag used for protocol dispatch. */
    public static String typeTag(Object v) {
        if (v == null || v == dev.irij.runtime.Values.UNIT) return "Unit";
        if (v instanceof Long) return "Int";
        if (v instanceof Double) return "Float";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof String) return "Str";
        if (v instanceof dev.irij.runtime.Values.Keyword) return "Keyword";
        if (v instanceof dev.irij.runtime.Values.IrijVector) return "Vector";
        if (v instanceof dev.irij.runtime.Values.IrijTuple) return "Tuple";
        if (v instanceof dev.irij.runtime.Values.IrijMap) return "Map";
        if (v instanceof dev.irij.runtime.Values.IrijSet) return "Set";
        if (v instanceof dev.irij.runtime.Values.Tagged t) {
            return t.specName() != null ? t.specName() : t.tag();
        }
        return v.getClass().getSimpleName();
    }

    public static IllegalStateException noImpl(String method, Object arg) {
        return new IllegalStateException("No impl of " + method + " for " + typeTag(arg));
    }

    /** `error "msg"` builtin — throws IrijRuntimeError, caught by `on-failure`. */
    public static Object errorBuiltin(Object msg) {
        throw new dev.irij.IrijRuntimeError(
                msg == null ? "error" : dev.irij.runtime.Values.toIrijString(msg));
    }

    /** Extract message for `on-failure` binding — never null. */
    public static String errorMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /**
     * Sentinel returned by {@link #fireOpToSM} when no SM handler matches
     * — distinct from any legal Irij value so {@link
     * dev.irij.runtime.EffectSystem#fireOp} can fall through to
     * "Unhandled effect" without ambiguity.
     */
    public static final Object SM_NO_MATCH = new Object();

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
     * walk {@link dev.irij.runtime.EffectSystem#STACK}; if a threaded
     * outer handles this effect, route via {@code fireOp} and continue the
     * loop with the result.
     */
    /** Per-thread stack of active SM dispatch frames — innermost on top.
     *  Lets a clause body's `perform` (tier-c) find a matching handler in
     *  any enclosing SM frame even though the inner clause's own dispatch
     *  loop has hs=[]. Existing nested-SM still benefits as a defensive
     *  fallback: if an inner loop sees an unmatched signal, it can dispatch
     *  via an outer frame's hs without unwinding back to that frame. */
    public static final ThreadLocal<java.util.Deque<java.util.List<CompiledHandler>>>
            SM_STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /** `try thunk` — return Ok(result) / Err(msg).
     *
     *  <p>Catches every Throwable except {@link InterruptedException}
     *  (must propagate so virtual-thread cancellation still works).
     *  This lets {@code try} trap JVM-level failures like
     *  {@code ArithmeticException} (division by zero),
     *  {@code ClassCastException}, {@code NullPointerException}, etc.,
     *  not just user-thrown {@link dev.irij.IrijRuntimeError}s. */
    public static Object tryFn(Object thunk) {
        try {
            Object r = callAny(thunk, new Object[0]);
            return new dev.irij.runtime.Values.Tagged("Ok", java.util.List.of(r));
        } catch (Throwable ex) {
            // Re-raise thread interruption — `try` mustn't swallow cancellation.
            if (ex instanceof InterruptedException
                    || ex.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw ex instanceof RuntimeException re
                        ? re : new RuntimeException(ex);
            }
            String msg = ex.getMessage();
            if (msg == null || msg.isEmpty()) msg = ex.getClass().getSimpleName();
            return new dev.irij.runtime.Values.Tagged("Err",
                    java.util.List.of(msg));
        }
    }

    public static dev.irij.IrijRuntimeError runtimeFrom(Throwable cause, String prefix) {
        if (cause instanceof dev.irij.IrijRuntimeError ire) return ire;
        String msg = cause == null ? prefix : (prefix + ": " + cause.getMessage());
        return new dev.irij.IrijRuntimeError(msg);
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
        // Legacy 3-arg form: the fn lives on the caller's own class.
        // Retained for any call site emitted before multi-class
        // emission added the owner argument.
        return redefBootstrap(lookup, name, mt, null);
    }

    /** Hot-redef bootstrap with an explicit owner class. Multi-class
     *  emission puts inlined-module fns in their own classes, so a
     *  call from the root program to a module fn can't resolve the
     *  target on the caller's class. {@code ownerInternal} (e.g.
     *  {@code irij/Program$vrata_html}) names the class that actually
     *  holds the method; null means "the caller's class" (root fns). */
    public static java.lang.invoke.CallSite redefBootstrap(
            java.lang.invoke.MethodHandles.Lookup lookup,
            String name,
            java.lang.invoke.MethodType mt,
            String ownerInternal) throws NoSuchMethodException,
                                         IllegalAccessException {
        Class<?> owner;
        if (ownerInternal == null) {
            owner = lookup.lookupClass();
        } else {
            try {
                owner = Class.forName(ownerInternal.replace('/', '.'),
                        false, lookup.lookupClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new NoSuchMethodException(
                        "redef owner class not found: " + ownerInternal);
            }
        }
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
