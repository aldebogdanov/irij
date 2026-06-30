# Effect system

Irij implements algebraic effects with deep handlers. **One back-end**
since v0.6.13: state-machine bytecode lowering. The interpreter and
the threaded (14c.2) handler protocol were both removed — single
execution model, zero ambiguity about which path runs.

## State-machine protocol (14c.3)

Body is compiled to an `IrijFn` step function. Local vars that must
survive a perform are *lifted* to slots in an `IrijContinuation`
(a struct: `int state, Object[] fields, IrijFn step`). At each
perform site the emitter generates:

```
k.state = next;
throw PerformSignal.of(eff, op, args, k);
```

The trampoline `dispatchLoopSM`:

```java
while (true) {
    try { return k.resume(resumeArg); }      // calls step(k, resumeArg)
    catch (PerformSignal s) { sig = s; }
    // find handler in hs (innermost) or SM_STACK fallback
    // dispatch sig.opName: clause.apply(args + resumeFn)
    // resumeFn throws TailResume(value, target=sig.continuation)
    catch (TailResume tr) {
        if (tr.target != expectedTarget) throw tr;
        resumeArg = tr.value;
        currentK = sig.continuation;
    }
}
```

No threads, no queues. A perform = one throw (stack-trace-free,
pooled). A resume = one TailResume throw (also pooled). Both unwind
back to the loop, which iterates.

Cost per perform: ~100ns at JIT steady-state. The throw is amortised
by HotSpot's escape analysis when `PerformSignal` doesn't escape the
catching frame.

## State-machine details

`emitWithSM` classifies the body into one of these shapes:

- **Pure** — no performs at all. Body runs to completion in a single
  step.
- **SingleOp** — exactly one top-level perform with optional bind. Two
  states.
- **Sequence** — multiple top-level performs (and/or nested `with`s).
  N states; lifted locals stored in `k.fields[]`.
- **EffIR** — body contains an `IfStmt` whose branches perform. Full
  CFG: blocks with `Return`/`Perform`/`Branch`/`Jump` terminators.
  Each block has a "header" label (does resume-bind store) and a
  "body" label (intra-step jump target).
- **Unsupported** — compile error. Historic SM-N gaps tracked in
  `StateMachineWithTest.java` (op-call in if-condition, composed
  handler bound to a local, tier-c clauses crossing composed chains,
  tier-c resume-value flow-through) are all closed as of v0.7.0 —
  zero `@Disabled` SM tests remain.

### Tail-position value of a `with` body

A `with` block is an expression: its value is the value of its body's
last statement. The catch is that **block-form** `if`/`match` parse to
`Stmt.IfStmt` / `Stmt.MatchStmt` (statements), not the inline
`IfExpr`/`MatchExpr`. A naive "last statement's value" emitter that only
recognises `ExprStmt` silently returns Unit when the body ends in a
multi-line if/match — exactly what white-screened the irij.online
seed-detail page (`with default-db { row := db-query …; if (empty? row)
… else { rows := db-query …; render … } }` returned an empty body).

Two places enforce the rule now:

- **EffIR** (`EffIRBuilder.lower`): a tail-position `IfStmt`
  (`isLast && exitJump == null`) lowers each branch with a `null`
  exitJump so the branch's tail expression becomes a `Term.Return` —
  **checked before** the op-bearing-if case, because routing a tail if
  through a merge block leaves the merge with `Return(null)` and
  discards the branch value. The merge-block path is only for *non-tail*
  ifs (used for effect, the value thrown away).
- **Pure / SingleOp / Sequence** (`emitBlockStmtsReturning`, and the
  handler-join and `on-failure` block emitters): all route the final
  statement through `emitTailStmtValue`, the single helper mirroring
  `emitBlock` — it emits a tail `ExprStmt`/`With`/`MatchStmt`/`IfStmt`
  as a value and returns `false` only for genuine non-value statements
  (a bare `:=`), where the caller pushes Unit.

For each shape, the emitter:

1. Allocates a static `smstep$N` method holding the state machine.
2. Builds an `IrijFn` wrapper via `LambdaMetafactory`.
3. Calls `RuntimeSupport.runWithSM(handler, step, nFields)` at the
   `with` site.

## Nested `with` (native dual-SM)

Top-level nested `with` is treated specially. Outer body's emit
detects `Stmt.With(inner, ...)` as a *segment terminator*. At that
segment, the emitter:

1. Allocates or fetches `kInner` from `kOuter.fields[innerSlot]` via
   `RuntimeSupport.getOrAllocInnerCont`.
2. Calls `runWithSM(innerHandler, kInner, vSlot)` — a re-entrant
   overload that threads the outer-handled resume value into `kInner`
   on re-entry.
3. Stores the inner result in `vSlot` and (if the form is
   `r := with X body`) also into `kOuter.fields[bindIdx]` for
   subsequent segments.

When inner body performs an effect the inner doesn't handle, the
signal escapes inner `runWithSM` and is caught by the outer
trampoline. The outer dispatches via `SM_STACK`; the synthesised
resumeFn targets `sig.continuation == kInner`. The trampoline iterates
with `currentK = kInner` so resumption returns to the inner body's
saved state.

## Tier (c) — clauses that perform foreign effects

`handler h :: A` whose clause for `a` performs effect `B`. The clause
body is itself compiled as an SM step (via `emitTierCClauseLambda`).
The clause's continuation `kClause` is allocated when the clause is
invoked. The clause runs under `runWithSMNoHs(kClause)` — empty `hs`,
so any perform falls through `SM_STACK` to find a matching outer
handler.

The trampoline target marker (`TailResume.target`) keeps the right
loop catching the right resume. Without it, the clause's own
perform-resume would be confused with the outer body's
perform-resume.

**Pool-snapshot invariant (v0.7.0).** `PerformSignal` and `TailResume`
are pooled per-thread for zero-allocation control flow. The pool slot
is shared across nested dispatch loops, so each iteration of
`dispatchLoopSMImpl` snapshots `sig.effectName`, `sig.opName`,
`sig.args`, and `sig.continuation` into locals immediately after the
catch — before invoking the clause. A tier-c clause that performs its
own effect spawns an inner dispatch loop that reuses the same pool
instance; without the snapshot, the inner perform would overwrite the
outer iteration's `sig.continuation`, causing the outer trampoline to
resume the wrong continuation (and trigger spurious "resume called
twice" errors).

See `phase-14c3-techdebt.md` for the full design walk.

## Handler composition `>>`

`with (h1 >> h2 >> h3) body` is sugar for nested `with h1 (with h2
(with h3 body))`. Native SM dispatch consults a flat list of handlers
in innermost-first order. The list is stored in
`CompiledComposedHandler.handlers`; `runWithSM` unpacks it on entry.

## Effect-row enforcement

`AVAILABLE_EFFECTS` thread-local. Top-level scripts inherit ambient
permissions; annotated fns get a finite set drawn from their `:::`
row. Effect ops and capability-gated calls check that the required
effect is in the current set. Throws `IrijRuntimeError` at the call
site if not — *not* at the handler dispatch.

Bytecode enforces effect rows at **compile time** via
`EffectRowChecker.check(decls)` after module inlining and before
emit (see `docs/internals/specs.md` § Compile-time effect-row lint).
The checker rejects three patterns:

1. **Call-site subsumption** — fn `f ::: A` calling fn `g ::: B` where
   `B ⊄ A ∪ available-handlers`.
2. **User effect-op performs** — `perform 'X.op'` from a fn whose row
   doesn't include `X`.
3. **Builtin call sites** — `println` / `read-file` / `sleep` / `raw-db-*`
   / etc. each carry a declared effect (Console / FileIO / Time / Db /
   …); see `EffectRowChecker.BUILTIN_EFFECTS`. The caller's row must
   contain it. This is what makes `fn pure (x -> println x)` fail to
   compile rather than throwing at runtime — the static check
   subsumes interp's `BuiltinFn.requiredEffects` runtime gate.

The build fails on subsumption violations; no runtime cost. Per-ref
JVM capability propagation refines the JVM tag at handle-binding
sites.

As a defense-in-depth backstop, the bytecode runtime also maintains
an `RT.EFFECT_ROW` thread-local stack (pushed at fn entry, `with`
block entry, and inside handler clauses) and calls
`checkPerformEffect` at every emitted `perform`. This catches the
narrow class of effect flows that the static pass can't see — e.g.
an effect that flows through a dynamically-typed callback dispatched
via `RT.callAny`. In well-typed code the runtime check is a no-op;
when it fires, the same `IrijRuntimeError` shape as the static
checker is raised, just at a later layer. Virtual-thread fibers
inherit the stack via `inheritEffectRow` so the property survives
`spawn` / `fork`.

Test-fixture authoring rule: negative cases ("this code should be
rejected") live in
`src/test/java/dev/irij/compiler/EffectRowLintTest.java` as JUnit
tests that compile a bad source string and assert
`CompileException`. They must **not** live in `.irj` runtime fixtures
— the static checker would reject the fixture itself before any
runtime assertion could observe the error.

## Concurrency parity

Spawned fibers inherit `SM_STACK` (SM handler frames), `EFFECT_ROW`
(declared row stack), the session namespace map (`NS`) and the
per-thread session PrintStream (`SESSION_OUT`) via `ParentSnapshot`
— see `concurrency.md`. `fireOp` from a fiber walks `SM_STACK` and
dispatches synchronously.
