# Phase 14c.3 — Status & follow-ups

All four major tech-debt items from the original plan have shipped on
`bytecode-mvp`. This doc records the as-built mechanism and the small
remaining tail.

## Shipped

### 1. Trampolining (`c547bcd`)

`runWithSM` no longer recurses through `clause.apply → resumeFn →
k.resume → throw → dispatchSM` per perform. A single `dispatchLoopSM`
while-loop catches `PerformSignal`, dispatches to a clause, and on
`resume v` the synthesised resumeFn throws a pooled `TailResume` that
unwinds the clause's JVM frames back to the loop. Loop iterates,
re-entering `currentK.resume(resumeArg)`. Deep perform loops scale.

Test: `deep_perform_loop_no_stackoverflow` (1000 sequential performs).

### 2. TailResume target marker (`3a7af49`)

`TailResume` carries `target` (the continuation that yielded). Each
loop's catch consumes only its own; mismatches are re-thrown. Required
for clean nested-SM and tier-c routing.

### 3. Native nested SM `with` (`1e1cb67`, `853e66a`, `e6914d4`)

Top-level inner `with` becomes a resumable segment in the outer's SM.
The inner continuation lives in `outerK.fields[innerSlot]` and persists
across outer-resume cycles. New 3-arg `runWithSM(handler, k, reentryValue)`
threads the outer-handled value down into the saved inner continuation
on re-entry.

Bind-RHS form `r := with X body` is also routed natively — the inner
result is stored in `k.fields[bindIdx_of_r]` so subsequent segments can
read `r` via the lifted-locals path. `on-failure` inside an inner
nested-SM `with` is wrapped in try/catch that re-throws PerformSignal
and TailResume so SM control flow keeps propagating; only genuine
failures trigger the on-failure block.

Tests:
- `nested_with_inner_handles_inner_effect`
- `nested_sm_outer_resume_into_kInner_preserves_state` (cross-handler
  greet → log → greet — proves kInner state survives outer-resume)
- `nested_inner_with_as_bind_rhs`
- `nested_inner_with_on_failure_native`

### 4. Hot-redef (`6bd6d71`)

Top-level `fn` calls compile to `invokedynamic` by default; the
bootstrap `RuntimeSupport.redefBootstrap` registers a `MutableCallSite`
keyed by `"owner.method:descriptor"` that the REPL can swap via
`RuntimeSupport.redefine(key, MethodHandle)`. Mirrors Clojure's deploy
model: `--direct-linking` emits plain `invokestatic` for max JIT
inlinability and disables hot-redef.

Tests in `HotRedefTest`: redef swaps a fn at runtime; direct-linking
disables it.

### 5. Shared SM_STACK (`1312f78`) + native tier-c (`151e4ab`)

`RuntimeSupport.SM_STACK` is a thread-local Deque of every active SM
dispatch frame's `hs`. On `PerformSignal`, dispatch first searches its
own frame; on miss it walks the stack innermost-first to find a match,
and dispatches in the *current* loop frame.

This lets tier-c clauses (clause body performs a foreign effect) work
natively. The clause body compiles to its own SM step function via
`emitTierCClauseLambda`; the wrapper `IrijFn` allocates a
continuation, populates `fields[]` with the op args + outer resumeFn,
and calls `runWithSMNoHs(kClause)` (empty hs). The clause body's
foreign perform throws a `PerformSignal` carrying `kClause`; it
escapes the inner empty-hs frame and is caught by the next-outer SM
frame via SM_STACK fallback. The outer's clause resume targets
`kClause`, so the trampoline iterates back to the clause body's saved
state with the foreign-perform's resume value.

Tests:
- `clause_performs_outer_effect_falls_back_threaded` (now the native
  path; name kept for historical context)
- `native_tier_c_clause_resume_value_flows_through` (state mutation +
  foreign perform + clause resume value all round-trip)

---

## Remaining tail

### Concurrency parity

`bodyContainsSpawn` still forces the outer `with` to threaded mode
because SM_STACK is per-thread (push/pop in `dispatchLoopSM`'s
try/finally) — a forked vthread starts with an empty SM_STACK and
can't see its parent's SM frames.

Fix: `RuntimeSupport.spawn` snapshots the current SM_STACK and the
fiber installs that snapshot at the top of its run. Same idea as
`inheritEffectStack` for the threaded path.

Sketched, not implemented.

### Tier-c shape coverage

`tierCClauseCompilable` currently accepts only `Sequence` shape and
rejects clauses whose body has a nested `with`. Could be extended to:
- EffIR shape (clause body has IfStmt-with-perform branches)
- Nested with inside clause (recursion through `getOrAllocInnerCont`)

Both are mechanical; just need the same Segment/EffIR machinery
threaded through `emitTierCClauseLambda`. No fundamental obstacle.

### Composed handlers + tier-c stress test

Tier-c with `>>`-composed handlers should work via SM_STACK fallback
but isn't dedicated-tested. Add a golden where the foreign perform's
matching handler is in the *outer's* composition slot, not just a
direct outer frame.

### Tier-c clause inside on-failure

Untested. Theoretically OK because on-failure runs in the outer SM
context after the inner runWithSM returned/threw — clause-as-SM
machinery doesn't apply.

### Per-perform allocation

Trampoline allocates an `AtomicBoolean resumed` per perform. Could be
folded into `IrijContinuation` (one flag per continuation). Minor.

### Bench expansion

`bench/effects-bump` is 50 inline performs (JVM-startup-dominated).
Add benches that drive 10K+ performs in tight loops to expose the
trampoline's cache effects + JIT speedup.

---

## Post-mortem note on TailResume

Earlier design analysis claimed tier-c needs a way to keep the outer
loop frame alive while clause-body performs propagate. The shipped
solution:

1. SM_STACK lets a clause's empty-hs loop dispatch on behalf of an
   outer frame (without the outer frame having a chance to act).
2. Routed dispatch is in the *clause's* loop frame, but the synthesised
   resumeFn targets `sig.continuation` (`= kClause`), so the resume
   value flows back into the clause's iterative resume.
3. When the clause body eventually calls its own outer-body resume
   (the `resume` parameter), that fires `TailResume(target=kBody)`,
   which propagates through the clause's loop catch (mismatch — re-throw),
   out of `runWithSMNoHs`, out of the wrapper, out of the outer's
   `clause.apply` try, and lands in the outer's TailResume catch which
   matches `kBody`. Consume → resume body.

The two-loop interleaving works because the target marker
distinguishes which loop owns each TailResume. No special "outer loop
stays alive" mechanism is needed — Java exception unwinding is
exactly the right primitive.
