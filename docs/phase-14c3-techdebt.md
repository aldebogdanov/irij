# Phase 14c.3 — Tech debt design notes

After steps 1–10 plus the trampoline pass (c547bcd), four substantial items
remain. Each is sized for its own session — sketched here so the next
session can pick up with full context.

## 1. Native tier (c) — clauses that perform foreign effects

**Status (after the trampoline + nested-SM + hot-redef session):** the
threaded fallback (`smCanHandle` returns false → emit via threaded path)
remains the correct, working approach. Native tier-c **was attempted in
sketch and the architectural issue is now precise**: see "Why no shortcut"
below.

**Plan (still the right design):**

1. At `HandlerDecl` emit, run `clausePerformsForeignEffect`. For tier-c
   clauses, emit the clause body as an SM step function (mirror of with-body
   emission), with the clause's params + a `resume` slot becoming the
   continuation's fields.
2. `CompiledHandler.clauses[opName]` becomes a factory that returns an
   `IrijContinuation` (stored args + outer-resume-fn in its fields).
   Plain clauses keep the simple `IrijFn` shape — dispatcher checks type.
3. `dispatchLoopSM`, when invoking a tier-c clause, allocates the clause
   continuation and runs it via `runWithSM(NO_HS, kClause)` — empty
   handler stack so any `perform` in the clause body re-throws and is
   caught by the outer-outer dispatch loop.
4. The clause's own `resume` (its own perform-resume) is its own
   `TailResume`-target.

**Why no shortcut exists** (lesson from the attempted sketch this session):

- Tried: have the clause body use the existing `RT.perform` → `EffectSystem.fireOp`
  path with SM handlers also pushed onto `EffectSystem.STACK`.
- Fails because `fireOp` would need to either (a) throw a `PerformSignal`
  carrying a continuation that represents *the rest of the clause body
  after the perform*, or (b) block-wait for an outer handler to dispatch
  and return the resume value. (a) cannot synthesise a continuation for
  arbitrary Java/IrijFn code mid-call without CPS compilation. (b) is
  the threaded-vthread machinery, which doesn't compose with the
  on-stack SM dispatch loop on a single thread.
- Tried: TailResume-with-target marker (so nested dispatch loops re-throw
  signals not addressed to them). This *is* needed and is shipped for
  nested-SM, but it doesn't help here — the missing piece is the
  clause's own continuation, not signal routing.

**TailResume target marker** (small follow-up either way): the trampoline's
`TailResume` should carry the target `IrijContinuation` and the dispatch
loop should re-throw mismatches. With nested-SM `with` shipped, dual-SM
test cases are correct because the inner trampoline's perform-signals
escape to the outer trampoline naturally — but a tier-c clause body that
calls `resume` could land in the wrong loop without the target marker.
Worth adding pre-emptively.

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
