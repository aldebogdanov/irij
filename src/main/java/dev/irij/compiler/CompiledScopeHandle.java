package dev.irij.compiler;

public final class CompiledScopeHandle {
    public final String modifier; // null | "race" | "supervised"
    public final java.util.List<Fiber> fibers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public final ParentSnapshot parent;

    public CompiledScopeHandle(String modifier) {
        this.modifier = modifier;
        this.parent = RtConcurrency.snapParent();
    }

    /** Reflection target for `handle.fork thunk`. */
    public Object fork(Object thunk) {
        Fiber f = RtConcurrency.forkOne(thunk, parent);
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
        return dev.irij.runtime.Values.UNIT;
    }

    public Object joinAll(Object bodyResult) {
        dev.irij.IrijRuntimeError firstErr = null;
        for (Fiber f : fibers) {
            try { f.result.join(); }
            catch (java.util.concurrent.CompletionException ce) {
                if (firstErr == null) {
                    for (Fiber g : fibers) g.thread.interrupt();
                    firstErr = RuntimeSupport.runtimeFrom(ce.getCause(), "Fiber failed");
                }
            }
        }
        if (firstErr != null) throw firstErr;
        return bodyResult;
    }

    public Object joinRace(Object bodyResult) {
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
            throw RuntimeSupport.runtimeFrom(ce.getCause(), "scope.race: all fibers failed");
        }
    }

    public Object joinSupervised(Object bodyResult) {
        for (Fiber f : fibers) {
            try { f.result.join(); }
            catch (java.util.concurrent.CompletionException ignored) {
                // per-fiber isolation — siblings keep running
            }
        }
        return bodyResult;
    }
}
