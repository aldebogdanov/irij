package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtEffects domain. */
public final class RtEffects {

    private RtEffects() {}


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
        return new RuntimeSupport.CurriedFn(
                args -> RuntimeSupport.callAny(right, new Object[]{ RuntimeSupport.callAny(left, args) }),
                1, new Object[0]);
    }


    private static void appendHandlers(Object v, java.util.List<CompiledHandler> out) {
        if (v instanceof CompiledHandler h) out.add(h);
        else if (v instanceof CompiledComposedHandler c) out.addAll(c.handlers);
        else throw new dev.irij.IrijRuntimeError(
                ">> requires handler operands, got " + RuntimeSupport.typeTag(v));
    }


    /** Emitted call-site for effect ops. Routes through EffectSystem.fireOp. */
    public static Object perform(String effectName, String opName, Object[] args) {
        return dev.irij.runtime.EffectSystem.fireOp(
                effectName, opName, java.util.Arrays.asList(args));
    }


    /**
     * Run body under a compiled handler: spawns a virtual thread for the body,
     * drives the handler loop on the calling thread, supports one-shot resume.
     */
    public static Object runWith(Object handlerObj, RuntimeSupport.IrijFn body) {
        if (handlerObj instanceof CompiledComposedHandler cc) {
            return runWithComposed(cc.handlers, 0, body);
        }
        if (!(handlerObj instanceof CompiledHandler h)) {
            throw new dev.irij.IrijRuntimeError(
                    "with requires a handler, got " + RuntimeSupport.typeTag(handlerObj));
        }
        var opChannel = new java.util.concurrent.SynchronousQueue<
                dev.irij.runtime.EffectSystem.EffectMessage>();
        var ctx = new dev.irij.runtime.EffectSystem.HandlerContext(
                h.effectName, h, opChannel);
        var parentStack = new java.util.ArrayDeque<>(
                dev.irij.runtime.EffectSystem.STACK.get());

        Thread bodyThread = Thread.startVirtualThread(() -> {
            var bodyStack = dev.irij.runtime.EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            try {
                Object result = body.apply(new Object[0]);
                opChannel.put(new dev.irij.runtime.EffectSystem.EffectMessage.Done(result));
            } catch (InterruptedException e) {
                // aborted by handler (no resume)
            } catch (Throwable t) {
                try {
                    opChannel.put(new dev.irij.runtime.EffectSystem.EffectMessage.Err(t));
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
    private static Object runWithComposed(java.util.List<CompiledHandler> handlers, int idx, RuntimeSupport.IrijFn body) {
        if (idx >= handlers.size()) return body.apply(new Object[0]);
        RuntimeSupport.IrijFn nested = (args) -> runWithComposed(handlers, idx + 1, body);
        return runWith(handlers.get(idx), nested);
    }


    private static Object runHandlerLoop(
            CompiledHandler h,
            java.util.concurrent.SynchronousQueue<dev.irij.runtime.EffectSystem.EffectMessage> opChannel) {
        while (true) {
            dev.irij.runtime.EffectSystem.EffectMessage msg;
            try { msg = opChannel.take(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new dev.irij.IrijRuntimeError("Handler loop interrupted");
            }
            switch (msg) {
                case dev.irij.runtime.EffectSystem.EffectMessage.Done d -> {
                    return d.value();
                }
                case dev.irij.runtime.EffectSystem.EffectMessage.Err e -> {
                    Throwable t = e.error();
                    if (t instanceof dev.irij.IrijRuntimeError ire) throw ire;
                    if (t instanceof RuntimeException re) throw re;
                    throw new dev.irij.IrijRuntimeError(
                            "Effect body error: " + t.getMessage());
                }
                case dev.irij.runtime.EffectSystem.EffectMessage.Op op -> {
                    RuntimeSupport.IrijFn clause = h.clauses.get(op.opName());
                    if (clause == null) {
                        throw new dev.irij.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + op.opName());
                    }
                    var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    RuntimeSupport.IrijFn resumeFn = (resumeArgs) -> {
                        if (!resumed.compareAndSet(false, true)) {
                            throw new dev.irij.IrijRuntimeError(
                                    "resume called twice (one-shot continuation)");
                        }
                        try {
                            op.resumeChannel().put(resumeArgs.length > 0
                                    ? resumeArgs[0]
                                    : dev.irij.runtime.Values.UNIT);
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
    public static Object runWithSM(Object handlerObj, RuntimeSupport.IrijFn step, int nFields) {
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
    public static IrijContinuation newCont(RuntimeSupport.IrijFn step, int nFields) {
        return new IrijContinuation(step, nFields);
    }


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
        var stack = RuntimeSupport.SM_STACK.get();
        for (var hs : stack) {
            for (int i = hs.size() - 1; i >= 0; i--) {
                CompiledHandler h = hs.get(i);
                if (h.effectName.equals(effectName)) {
                    RuntimeSupport.IrijFn clause = h.clauses.get(opName);
                    if (clause == null) {
                        throw new dev.irij.IrijRuntimeError(
                                "Handler " + h.name + " has no clause for " + opName);
                    }
                    final Object[] resumeBox = {null};
                    final boolean[] resumed = {false};
                    RuntimeSupport.IrijFn resumeFn = (resumeArgs) -> {
                        if (resumed[0]) {
                            throw new dev.irij.IrijRuntimeError(
                                    "resume called twice (one-shot)");
                        }
                        resumed[0] = true;
                        Object v = resumeArgs.length > 0
                                ? resumeArgs[0]
                                : dev.irij.runtime.Values.UNIT;
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
        return RuntimeSupport.SM_NO_MATCH;
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
                    "with requires a handler, got " + RuntimeSupport.typeTag(handlerObj));
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
                                                       RuntimeSupport.IrijFn step,
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


    private static Object dispatchLoopSM(java.util.List<CompiledHandler> hs,
                                         IrijContinuation k,
                                         Object reentryValue) {
        var stack = RuntimeSupport.SM_STACK.get();
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
                var threadedStack = dev.irij.runtime.EffectSystem.STACK.get();
                for (var ctx : threadedStack) {
                    if (ctx.effectName().equals(sigEffectName)) {
                        resumeArg = dev.irij.runtime.EffectSystem.fireOp(
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

            RuntimeSupport.IrijFn clause = h.clauses.get(sigOpName);
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
            RuntimeSupport.IrijFn resumeFn = (resumeArgs) -> {
                if (!resumed.compareAndSet(false, true)) {
                    throw new dev.irij.IrijRuntimeError(
                            "resume called twice (one-shot continuation)");
                }
                Object v = resumeArgs.length > 0
                        ? resumeArgs[0]
                        : dev.irij.runtime.Values.UNIT;
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


    /** Snapshot the current thread's effect-handling state. Cheap;
     *  the underlying deques get shallow-copied. */
    public static EffectSnapshot snapshotEffects() {
        return new EffectSnapshot(RtConcurrency.snapParent());
    }


    /** Install {@code snap} onto the current thread's effect-row /
     *  SM-stack / threaded-handler-stack / namespace / session-out,
     *  then run {@code body}. Intended for fresh worker threads
     *  whose thread-locals start empty (HTTP request handlers,
     *  callback executors); the install is additive and the thread
     *  is expected to die after the body returns, so no restore
     *  step is performed. */
    public static Object runWithEffectSnapshot(EffectSnapshot snap,
            java.util.function.Supplier<Object> body) {
        ParentSnapshot p = snap.inner;
        RtConcurrency.inheritEffectStack(p.effectStack());
        RtConcurrency.inheritSMStack(p.smStack());
        RtConcurrency.inheritEffectRow(p.effectRow());
        return RuntimeSupport.callBoundSession(p.namespace(), p.sessionOut(), body);
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


    public static final ThreadLocal<java.util.Deque<java.util.Set<String>>> EFFECT_ROW =
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


    /** v0.8.0b — opaque-handler entry. The emitter doesn't know what
     *  effect a fn-param or computed handler covers, so it can't push
     *  effects at compile time. This helper introspects the runtime
     *  handler value and pushes one effect-row frame for each
     *  CompiledHandler in the value. Returns the number of frames
     *  pushed; the caller passes that count to {@link #exitWithCount}
     *  on exit so we always pop the same number we pushed. */
    public static int enterWithFromValue(Object handlerObj) {
        if (handlerObj instanceof CompiledHandler h) {
            enterWith(h.effectName);
            return 1;
        }
        if (handlerObj instanceof CompiledComposedHandler ch) {
            int n = 0;
            for (CompiledHandler h : ch.handlers) {
                enterWith(h.effectName);
                n++;
            }
            return n;
        }
        // Unknown shape — push an ambient-equivalent guard so the body
        // doesn't blow up before runWithSM has a chance to fail with a
        // clearer message. Pop one frame to match.
        EFFECT_ROW.get().push(EFFECT_AMBIENT);
        return 1;
    }


    public static void exitWithCount(int count) {
        var stack = EFFECT_ROW.get();
        for (int i = 0; i < count; i++) stack.pop();
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
}


// ── Effects (14c.2: thread+channel lowering; reuses EffectSystem) ──

/**
 * Compiled handler value: clause map from op-name to IrijFn.
 * Each clause IrijFn is invoked with arg-array that ends with the resume
 * IrijFn: {@code args..., resume}. Clause returns the value that should
 * be the result of the enclosing `with` block.
 */


/** Flat ordered list of handlers from a `>>` composition. */


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


/** Public, opaque snapshot of effect-handling state taken at the
 *  call site. Capabilities that hand control to a fresh thread
 *  (HTTP request handlers, scheduled callbacks, anything backed
 *  by a Java executor) snapshot here and replay via
 *  {@link #runWithEffectSnapshot} on the new thread so the user's
 *  Irij code finds the same handler chain it would have on the
 *  calling thread.
 *
 *  <p>Without this, fresh executor threads start with empty
 *  {@code EFFECT_ROW} / {@code SM_STACK} thread-locals and any
 *  {@code perform} blows up with "no handler on stack". */
