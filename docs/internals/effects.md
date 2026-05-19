# Effect system

Irij implements algebraic effects with deep handlers. Three back-ends
share the same semantics; they differ in *how* they implement
suspension and resumption.

## Three back-ends

| Back-end | Body runs in | Handler dispatch | Resume mechanism |
|---|---|---|---|
| Interpreter | Virtual thread | Calling thread via `runHandlerLoop` | `SynchronousQueue.put/take` |
| Bytecode threaded (14c.2) | Virtual thread | Calling thread via `runHandlerLoop` | Same as interp |
| Bytecode state-machine (14c.3) | Single thread (caller's) | Caller's stack via `dispatchLoopSM` | Exception-driven trampoline |

The interpreter and threaded bytecode share the protocol — both build
`EffectSystem.HandlerContext` records on a per-thread stack, suspend
the body via `SynchronousQueue`, and resume by writing to a per-op
return channel. The SM bytecode replaces all of that with a
trampoline.

## Threaded protocol (14c.2)

`with X body`:

```
Calling thread                   Body vthread
─────────────                    ────────────
runWith(handler, body)
  push HandlerContext
  spawn vthread:
    body() {
      … some perform p …
        fireOp("E", "p", args):
          new resumeChannel
          opChannel.put(Op p args resumeChannel)  ◄── blocks
                                                   ▶ resumeChannel.take()
  runHandlerLoop:                                   blocks
    msg = opChannel.take()      ◄── unblock
    if Op p args:
      v = clause.apply(args + resumeFn)
      resumeFn(x): resumeChannel.put(x); runHandlerLoop()
    if Done v: return v
```

Two threads ping-pong via two SynchronousQueues. Each perform is
~one vthread context-switch.

Cost per perform: ~µs (cheap because virtual threads, but the queue +
context-switch isn't free).

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
- **Unsupported** — falls back to threaded mode.

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
Both `--mode=bytecode-threaded` and `--mode=bytecode-sm` fail the
build on subsumption violations; no runtime cost. The interpreter
checks the same rule at apply-time. Per-ref JVM capability
propagation (v0.5.0) refines the JVM tag at handle-binding sites.

## Concurrency parity

Spawned fibers inherit both `EffectSystem.STACK` (threaded handlers)
and `SM_STACK` (SM handlers) — see `concurrency.md`. `fireOp` from a
fiber walks the threaded stack first, then falls through to
`fireOpToSM` which walks the SM stack and dispatches synchronously.

## Trade-offs by back-end

- **Threaded**: simpler runtime, but every perform costs a vthread
  context-switch. Body's state survives naturally on the JVM stack.
- **State-machine**: ~10× faster on hot perform loops but emitter
  complexity is higher. Pooled exceptions, lifted locals, target-
  marker TailResume — all to keep the trampoline simple.

The default mode is `bytecode-threaded` for stability; switch to
`bytecode-sm` for hot perform-heavy workloads.
