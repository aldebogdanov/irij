# Phase 14c.3 — Tech debt design notes

After steps 1–10 plus the trampoline pass (c547bcd), four substantial items
remain. Each is sized for its own session — sketched here so the next
session can pick up with full context.

## 1. Native tier (c) — clauses that perform foreign effects

**Current state:** detected at emit time via `smCanHandle`; `with`-stmts that
reference such handlers fall back to the threaded path. Correct, but means
those programs don't benefit from SM lowering.

**Why hard:** clauses are compiled as plain `IrijFn` lambdas. Their
`perform` calls go through `RT.perform` → `EffectSystem.fireOp`, which needs
either an `EffectSystem.STACK` entry (threaded) or a continuation to resume
into (SM). Neither exists for an `IrijFn`-style clause.

**Plan:**

1. At `HandlerDecl` emit, run `clausePerformsForeignEffect`. For tier-c
   clauses, emit the clause body as an SM step function (mirror of with-body
   emission), with the clause's params + a `resume` slot becoming the
   continuation's first fields.
2. `CompiledHandler.clauses[opName]` stores a *factory* that takes
   `(args..., outerResumeFn)` and returns an `IrijContinuation` whose
   step is the clause SM. Plain (non-tier-c) clauses keep the simple
   `IrijFn` shape — the dispatcher checks the type.
3. `dispatchLoopSM`, when invoking a tier-c clause, allocates the clause
   continuation and runs it via a *nested* `dispatchLoopSM` whose `hs` is
   the OUTER chain (handlers above the current `h`) — per design doc § 6.
4. The clause's `resume` is its own `TailResume`-based unwind, distinct
   from the body's, but mechanically identical.

**Key invariant:** clause continuation outlives the clause's first
`perform`, so its state survives across the outer resume cycle — same
machinery as nested `with`.

---

## 2. Native nested SM `with`

**Current state:** `containsOpCall(Stmt.With) == true` so outer falls back
to threaded; inner stays SM and signals are bridged via
`EffectSystem.STACK`. Correct, less efficient than dual-SM.

**Why hard:** outer's continuation must resume INSIDE the inner with rather
than at its start. Naive re-execution restarts the inner with from scratch
on every outer resume, losing inner state and producing wrong output.

**Plan A (continuation-on-continuation, the clean design):**

1. Outer EffIR builder treats `Stmt.With(innerHandler, innerBody)` as a
   region with three boundaries: enter-inner, run-inner-body, leave-inner.
2. Allocate `kInner` once; persist it in `kOuter.fields[innerSlot]`.
3. Outer state at the inner-with-region:
   ```
   kInner = (IrijContinuation) k.fields[innerSlot];
   if (kInner == null) {
       kInner = new IrijContinuation(innerStep, innerNFields);
       k.fields[innerSlot] = kInner;
   }
   runWithSM(innerHandler, kInner);
   ```
4. Make `runWithSM` re-entrant: if `k.state != 0` on entry, resume with the
   value supplied externally (read from a side slot or arg).
5. Add an overload `runWithSM(handler, k, externalResumeValue)` so outer can
   thread a value down on re-entry after the outer handler resumes.

**Plan B (handler-stack-on-continuation, the "flat" design):**

1. Single trampoline frame; `k` carries a mutable handler stack.
2. Inner-with-region in outer's body emits `pushHandler` / `popHandler`
   pseudo-states.
3. `dispatchLoopSM` consults `k.handlerStack` (innermost-first) instead of
   a fixed `hs`.

Plan A keeps the runtime simple; plan B keeps emission simple. Either
works — plan A is closer to the existing code. Pick one in the next
session.

---

## 3. Hot-redef via invokedynamic + MutableCallSite

**Current state:** none. Top-level `fn` calls compile to direct
`invokestatic`. REPL redefinitions of compiled fns don't take effect.

**Plan:**

1. Each `pub fn`/`fn` emits a static method holding the implementation
   (call it `f$impl`) plus an `invokedynamic` bootstrap that returns a
   `MutableCallSite` pointing at `f$impl`.
2. Call sites for `f` use the indy site, so a future `redefineCallSite(f,
   newImpl)` can swap the target without re-linking call sites.
3. Add `--direct-linking` flag to `irij build`: when set, emit direct
   `invokestatic` to `f$impl` (no indy), matching Clojure's deploy
   model. The runtime overhead of a non-`--direct-linking` build is one
   indy stub per call (well within JIT inlining range).
4. Wire nREPL redefine to `MutableCallSite.setTarget(newImplHandle)`.
5. New tests: define fn → call → redefine → call returns new impl.

Independent of the effects work — could land in any order.

---

## 4. (Stretch) Fold trampoline cache misses

The current trampoline reallocates an `AtomicBoolean resumed` flag per
perform. Replace with a thread-local pool, or store the flag on `k`. Minor.

---

## Test strategy

For 1 and 2: write the *same* test under all three back-ends (interpreter,
threaded, SM) and assert identical output. Add to `DualRuntimeGoldenTest`
as it stands.

For 3: a small `HotRedefTest` exercising redefine without restart.

## Order of attack (recommended)

1. Native nested SM `with` (Plan A) — biggest correctness win.
2. Native tier-c — depends on continuation machinery from #1.
3. Hot-redef — independent, can run in parallel.
