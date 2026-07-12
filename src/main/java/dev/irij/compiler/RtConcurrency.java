package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtConcurrency domain. */
public final class RtConcurrency {

    private RtConcurrency() {}


    /** Snapshot the current effect stack + push onto the child fiber's stack. */
    public static void inheritEffectStack(java.util.Deque<
            dev.irij.runtime.EffectSystem.HandlerContext> parentStack) {
        var fiberStack = dev.irij.runtime.EffectSystem.STACK.get();
        fiberStack.addAll(parentStack);
    }


    /** Inherit the parent's SM dispatch frames so a fiber's body can find
     *  matching SM handlers via the SM_STACK fallback (concurrency parity). */
    public static void inheritSMStack(java.util.Deque<java.util.List<CompiledHandler>> parentSMStack) {
        var fiberSMStack = RuntimeSupport.SM_STACK.get();
        // Push parent frames in OUTER-first order so the fiber's stack
        // mirrors the parent's innermost-on-top ordering.
        var arr = parentSMStack.toArray(new Object[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            var frame = (java.util.List<CompiledHandler>) arr[i];
            fiberSMStack.push(frame);
        }
    }


    public static ParentSnapshot snapParent() {
        return new ParentSnapshot(
                new java.util.ArrayDeque<>(dev.irij.runtime.EffectSystem.STACK.get()),
                new java.util.ArrayDeque<>(RuntimeSupport.SM_STACK.get()),
                new java.util.ArrayDeque<>(RtEffects.EFFECT_ROW.get()),
                RuntimeSupport.NS.get(),
                RuntimeSupport.SESSION_OUT.get());
    }


    /** Replace the child fiber's effect-row stack with the parent's
     *  snapshot. Run before any other fiber-side code so {@code perform}
     *  and effect-aware builtins see the inherited row. */
    public static void inheritEffectRow(java.util.Deque<java.util.Set<String>> parentRow) {
        var fiberRow = RtEffects.EFFECT_ROW.get();
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


    private static java.util.Deque<dev.irij.runtime.EffectSystem.HandlerContext> snapStack() {
        return new java.util.ArrayDeque<>(
                dev.irij.runtime.EffectSystem.STACK.get());
    }


    /** Spawn a virtual thread running the thunk (IrijFn or BuiltinFn). */
    public static Fiber forkOne(Object thunk, ParentSnapshot parent) {
        var future = new java.util.concurrent.CompletableFuture<Object>();
        var t = Thread.startVirtualThread(() -> {
            inheritEffectStack(parent.effectStack());
            inheritSMStack(parent.smStack());
            inheritEffectRow(parent.effectRow());
            if (parent.namespace() != null) RuntimeSupport.NS.set(parent.namespace());
            if (parent.sessionOut() != null) RuntimeSupport.SESSION_OUT.set(parent.sessionOut());
            try {
                future.complete(RuntimeSupport.callAny(thunk, new Object[0]));
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
            if (parent.namespace() != null) RuntimeSupport.NS.set(parent.namespace());
            if (parent.sessionOut() != null) RuntimeSupport.SESSION_OUT.set(parent.sessionOut());
            try { RuntimeSupport.callAny(thunk, new Object[0]); }
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
        return dev.irij.runtime.Values.UNIT;
    }


    /** `await fiber` — block until fiber completes. */
    public static Object await(Object fiberArg) {
        if (!(fiberArg instanceof Fiber f)) {
            throw new dev.irij.IrijRuntimeError(
                    "await expects a Fiber, got " + RuntimeSupport.typeTag(fiberArg));
        }
        try { return f.result.join(); }
        catch (java.util.concurrent.CompletionException ce) {
            throw RuntimeSupport.runtimeFrom(ce.getCause(), "Fiber failed");
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
            throw RuntimeSupport.runtimeFrom(ce.getCause(), "par failed");
        }
        return RuntimeSupport.callAny(combiner, results);
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
            throw RuntimeSupport.runtimeFrom(ce.getCause(), "race: all thunks failed");
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
            throw RuntimeSupport.runtimeFrom(e.getCause(), "timeout");
        } catch (InterruptedException e) {
            f.thread.interrupt();
            Thread.currentThread().interrupt();
            throw new dev.irij.IrijRuntimeError("timeout: interrupted");
        }
    }
}


// ── Concurrency ─────────────────────────────────────────────────────



/** Snapshot of both the threaded EffectSystem.STACK and the SM_STACK
 *  taken at fork time so the fiber can re-establish the parent's
 *  effect-handling context (both 14c.2 threaded and 14c.3 SM frames). */


/**
 * Compiled scope handle — bound to a name inside a `scope { ... }` block.
 * `handle.fork thunk` spawns a fiber tied to this scope; join semantics
 * run after the block body via {@link #joinByModifier}.
 */
