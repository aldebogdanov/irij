# Phase 14c.3 — State-Machine Lowering for Effect Handlers

Status: **design, pre-implementation.** Target: full semantic parity with 14c.2
(including multi-level handler clauses that themselves `perform`), but with
no virtual thread and no `SynchronousQueue` per `with`.

Selected by `CompileOptions.HandlerStrategy.STATE_MACHINE` (aliases
`--mode=bytecode-sm`, currently a stub that rejects).

Parent doc: `docs/phase-14-bytecode.md` (§ 14c.2).

---

## 1. Why

14c.2 lowering per `with handler body`:

1. Spawn a **virtual thread** for `body`.
2. Allocate a `SynchronousQueue<Object>` per `perform` call.
3. Body thread blocks on the queue inside `perform`.
4. Calling thread runs `runHandlerLoop`, dispatching each op to the matching
   clause; `resume v` unblocks the body's queue.

Correct. Heavy. For tight inner loops that touch an effect (e.g. logging, a
pluggable RNG, state), every `perform` costs at minimum:

- one `SynchronousQueue` alloc + handoff (~200 ns on modern JVM)
- two thread context switches
- GC pressure from continuation-ish closure allocation

14c.3 lowers effect-bearing bodies to **state machines** — plain method calls,
no threads, no queues. Each `perform` becomes a state transition; `resume`
becomes a method call that re-enters the state machine.

Target: `perform` on the hot path becomes roughly "push args, throw a
pooled signal, catch in dispatcher, call clause, re-enter method at label" —
all on one thread, all stack-local.

---

## 2. Semantic target (what must still hold)

Non-negotiables — 14c.2 and 14c.3 must be indistinguishable to user code:

1. **One-shot `resume`**: calling `resume` more than once from a clause is an
   error. (Matches 14c.2, matches interpreter.)
2. **Abort semantics**: clause that doesn't call `resume` → the body is
   discarded, the `with` evaluates to whatever the clause returned.
3. **`on-failure`**: non-effect `RuntimeException` thrown from `body` is caught,
   binds `error`, runs the handler block.
4. **Handler state** (`state :! init`, `<-`, dot-access): persists across `with`
   invocations (static field, as today).
5. **Handler composition** (`h1 >> h2`): as today, layered `with`s; outer owns
   `on-failure`.
6. **Required-effect rows** (`handler h ::: E1 E2`): clause bodies that
   `perform` outer effects resolve against the enclosing handler stack.
7. **Nested `with`**: lexical stack; `perform` walks out to the nearest handler.
8. **Tier (c) — handler clauses that themselves `perform`**: must work (see §5).
9. **Concurrency**: a fiber forked under a `with` sees the handler stack
   snapshot at fork time; this is runtime-level and unaffected by 14c.3.

---

## 3. Where to transform

Three options considered. Decision: **option C — new IR layer**.

| option | pros | cons |
|---|---|---|
| A. AST rewrite | Debuggable by printing. ClassEmitter mostly unchanged. | AST nodes become mixed: some pre-CPS, some post. Confuses downstream passes. |
| B. ASM post-pass | No AST changes. Catches everything that compiles. | Must track JVM operand stack per-instruction across suspend points. Exception tables need rewriting. Stack-map verification. This is the approach Kotlin/Loom use — they have teams on it. |
| **C. New IR layer** | Clear split: lowering understands effects, emitter understands states. Fully testable without bytecode. | One more lowering stage. |

IR name: **`EffIR`**. AST → `EffIR` → bytecode. Pure (non-effectful) functions
bypass `EffIR` entirely and go AST → bytecode exactly as today — `EffIR` only
materialises around bodies that contain `perform`.

---

## 4. EffIR shape

`EffIR` is a small CFG. Each node belongs to a **region** (one region per
state-machine class).

```
EffBlock        = sequence of EffStmt ending in EffTerminator
EffStmt         = Pure(Expr)                   // arbitrary pure AST expr, no effects
                | Assign(local, Expr)
                | Call(local, fn, args)        // pure fn call
EffTerminator   = Return(Expr)
                | Branch(cond, thenBlock, elseBlock)
                | Jump(blockId, args)          // phi-ish
                | Perform(op, args, resumeInto) // <-- suspend point
                | Raise(Expr)
Region          = {
                    id,
                    locals: [name, slotType],
                    blocks: [EffBlock],
                    entryBlock,
                  }
```

Key invariants:
- Every `Perform` node is followed only by labelled entry points
  (`resumeInto` is a `blockId` — the state to re-enter on `resume v`).
- All cross-block data flow is via `locals` (lifted to state-machine fields).
  Expression-level data flow within a block stays on the JVM operand stack
  (never suspended across a `Perform`).
- CPS-like SSA is **not** required; we lift across `Perform` boundaries only.

### AST → EffIR rules (sketch)

- Pure expression: emitted into the current block as `Pure`/`Assign`/`Call`.
- Op call `op args` (call-site of a declared effect op): flush operand stack
  into locals, emit `Perform(op, args, nextBlock)`, switch to `nextBlock`.
- `if cond t e` where `t`/`e` contain `Perform`: split into `Branch` with two
  sub-regions; their join block takes the result via `Jump(join, [resultLocal])`.
- `with H body`: body is an `IrijContinuation` subclass compiled from its own
  region; `with` itself lowers to `RuntimeSupport.runWithSM(handler, k)`.
- `resume v` inside a clause: tier (c) — see §5.

Lowering is syntax-directed; no fixed-point needed.

---

## 5. State-machine class layout

For each effect-bearing body (the `body` of a `with`, and each handler clause
that itself `perform`s), the compiler emits:

```
class Program$k$<n> extends RuntimeSupport.IrijContinuation {
    private int state;
    private Object local$<name>_<ix>;   // one field per lifted local
    private Object resumeValue;         // set by dispatcher before re-entry

    Program$k$<n>(Object[] captured) { /* init free vars */ }

    @Override public Object resume(Object v) {
        resumeValue = v;
        for (;;) {
            switch (state) {
                case 0:  /* entry block */
                    // ... pure stmts ...
                    // Perform(log, [msg], 1):
                    state = 1;
                    throw PerformSignal.of("Log", "log", new Object[]{msg}, this);

                case 1:  /* resumeInto block */
                    Object x = resumeValue;
                    // ... continue ...
            }
        }
    }
}
```

`PerformSignal` is a pooled, stack-trace-free `RuntimeException` subclass
carrying `effectName, opName, args, continuation`. Pooled (thread-local
reusable instance) so the hot path doesn't allocate — matches what Loom does
internally.

**Why throw instead of return?** Because an op call can happen inside arbitrarily
deep pure calls (`fn helper (x -> log x)` where `log` is an op). Returning would require
rewriting every caller up to the `with`. Throwing escapes them all in one hop.
Handlers catch at the `runWith` frame.

Pure functions do NOT catch `PerformSignal` — it propagates up through them
unmodified.

### Dispatcher (`runWithSM`)

```
Object runWithSM(CompiledHandler h, IrijContinuation k) {
    try {
        return k.resume(null);                 // enter
    } catch (PerformSignal s) {
        if (!h.handles(s.effectName)) throw s; // re-raise to outer handler
        IrijFn clause = h.clause(s.opName);
        Object result = callAny(clause, append(s.args, s.continuation));
        // result = whatever the clause returned (abort) OR the value the body
        // produced after resume — see §6.
        return result;
    } catch (RuntimeException e) {
        if (h.hasOnFailure()) return h.onFailure(e.getMessage());
        throw e;
    }
}
```

`resume v` inside the clause is a call to `s.continuation.resume(v)` — which
re-enters the body's switch at the saved state. That call may itself throw
another `PerformSignal` (next op) or return (body done). The clause is free to
loop on this.

### Pool for `PerformSignal`

```
final class PerformSignal extends RuntimeException {
    String effectName, opName;
    Object[] args;
    IrijContinuation k;
    static final ThreadLocal<PerformSignal> POOL = …;
    static PerformSignal of(...) { … reuses POOL instance, populates fields … }
    @Override public Throwable fillInStackTrace() { return this; } // no stack
}
```

Fiber (vthread) friendly: `ThreadLocal` per carrier thread is fine because the
signal is fully consumed (or rethrown) before the next `perform`.

---

## 6. Tier (c) — clauses that themselves `perform`

Example:

```
effect Log
  log :: Str -> ()

effect DB
  save :: Data -> ()

handler logged-db :: DB
  save d =>
    log ("saving " ++ (to-str d))   ;; <-- calls Log op from clause body
    resume (db-write d)
```

`logged-db`'s `save` clause body calls `log`. That `log` is handled by an
outer handler (lexically outside the `with logged-db …`). So the clause body
is itself an effectful computation — must become its own state-machine class.

Lowering:
- Every handler clause that syntactically contains `perform` is compiled as
  `IrijContinuation` (just like bodies).
- A clause's `resume v` takes `v`, stores it in a field on the parent
  continuation, and re-enters that continuation (§5).
- A clause's internal `perform` throws `PerformSignal` from inside its own
  `resume()`; this escapes out of `runWithSM(logged-db, …)` and is caught by
  the enclosing `runWithSM(log-handler, …)`. That outer handler dispatches,
  and its clause's eventual return value re-enters the `logged-db` clause at
  its next state.
- Because `PerformSignal` carries `k`, and each layer's catch block calls
  `k.resume(v)`, the **continuation stack is reified entirely in
  `IrijContinuation` fields, not the JVM stack**. Nesting works.

### Nested `with` without `perform` in the middle

When `with H body` is syntactically inside another `with G …`, and neither
body's region suspends across the inner `with`, the inner `runWithSM` is just
a nested call — both handlers coexist naturally on the JVM stack. This is the
common case and takes the fast path (no new continuation class for the outer).

### Interaction with handler composition `>>`

`h1 >> h2` stays as today (`RuntimeSupport.CompiledComposedHandler`):
`runWithSM` detects a composed handler and iterates layered `runWithSM` calls.
No extra work — composition is already outside the per-`with` hot path.

---

## 7. Handler state

Unchanged from 14c.2b: `state :! init` → private static field on the program
class. `state <- v` → `PUTSTATIC`. `handler.state` dot-access → `GETSTATIC`.
State is thread-safe only insofar as it was already — no new concurrency
surface.

---

## 8. Required-effect rows (`handler h ::: E1 E2`)

Clause bodies that `perform` an outer effect simply do so — their `perform`
throws `PerformSignal`, which escapes `runWithSM(h, …)` (because `h` doesn't
claim that effect) and is caught by the outer handler's `runWithSM`. Same
mechanism as tier (c).

The declarative `:::` row remains a declaration-time concern (spec-lint /
effect-check layer), not enforced at codegen — same as 14c.2.

---

## 9. Fiber / `spawn` interaction

`RuntimeSupport.spawn` already snapshots `EffectSystem.STACK` at fork time so
forked fibers see the right handlers. Under 14c.3 the "handler stack" is just
the set of active `runWithSM` frames on the fiber's JVM stack — natural and
correct. `EffectSystem.STACK` is no longer consulted on the hot path, but we
**keep** populating it for parity with the interpreter (mixed-mode debugging)
and for the concurrency snapshot mechanism.

---

## 10. Phasing (implementation steps)

1. **Scaffolding**
   - `IrijContinuation` abstract class + `PerformSignal` + pool — `RuntimeSupport`.
   - `runWithSM` entry (single-handler path, no composition, no on-failure).
   - `CompileOptions.HandlerStrategy.STATE_MACHINE` routes `with` emission to
     an `SMHandlerEmitter` (parallel to today's `ThreadedHandlerEmitter`).

2. **EffIR + lowering — pure + single `perform`**
   - `ast/eff/` package: `EffRegion`, `EffBlock`, `EffStmt`, `EffTerminator`.
   - AST→EffIR pass for `with` bodies.
   - EffIR→bytecode: emit `IrijContinuation` subclass, `switch` on state,
     `Perform` → store state + throw pooled signal.

3. **Multiple `perform`s + branching inside body**
   - Split on `if` where branches suspend; `Jump` blocks for join.
   - Verify: goldens for straight-line N-perform, if-each-arm-performs,
     while-like recursion with `perform`.

4. **Abort + `on-failure`**
   - Clause that doesn't call `resume` → dispatcher returns clause's value
     without re-entering continuation. `try/catch RuntimeException` for
     `on-failure`.

5. **Handler state + dot-access**
   - Reuse 14c.2b static-field machinery; `SMHandlerEmitter` emits identical
     `GETSTATIC`/`PUTSTATIC`.

6. **Handler composition `>>`**
   - `runWithSM` detects `CompiledComposedHandler` and iterates. Semantics
     match 14c.2.

7. **Tier (c): clauses that `perform`**
   - Compile effectful clauses as continuations. Dispatcher threads signals
     through nested `runWithSM` frames.
   - Goldens: `logged-db` example, nested state-performing clauses.

8. **Required-effect rows + nested `with`**
   - End-to-end tests: handler `h1 ::: E2`, outer `with h2 (with h1 …)`.

9. **Concurrency parity**
   - `spawn` inside `with` sees handlers. Reuse existing fiber snapshot.

10. **Bench + flip default**
    - `bench/effects/` benches: per-`perform` cost, handler-heavy workload.
    - Once 14c.2 ≈ 14c.3 on correctness goldens AND 14c.3 ≥ 2× faster on
      effect-heavy benches, flip default to `BYTECODE_SM`.

Each step committable, green-tests on the way.

---

## 11. Test strategy

- **Golden parity**: every existing 14c.2 / 14c.2b golden test runs under
  both `THREADED` and `STATE_MACHINE` modes, asserts identical output.
  (Reuse the integration test runner; parametrise over `HandlerStrategy`.)
- **Property tests** (QuickCheck-ish, reuse Phase 6c infra): randomly generated
  effect programs; run interpreter + threaded + SM; all three must agree.
- **Micro-benches** in `bench/effects/` comparing the three for:
  - 1M `perform`s in a loop (hot path)
  - deep handler stacks (10+ nested `with`s)
  - composed handlers
  - tier (c) programs

---

## 12. Resolved design calls

1. **Continuation allocation**: fresh `IrijContinuation` instance per `with`
   invocation. Simple, thread-safe, GC handles it. Instance pooling deferred
   (see § 14 tech debt).
2. **Multi-shot `resume`**: out of scope for 14c.3. SM design does not
   preclude it — a cloned continuation (deep-copy of locals + state) would
   admit `resume` called N times. Hooks left in `IrijContinuation` for a
   future `clone()`; no code path materialised now (see § 14 tech debt).
3. **Debuggability — REQUIRED.** Stack traces and step-debugging through the
   state machine must land on the correct source lines. Implementation:
   - `EffIR` nodes carry the originating AST node's source position.
   - Emitter writes a `LineNumberTable` attribute per state-machine class:
     for each `switch` case, map to the source line of the block's first
     statement; for each `Perform`, map to the op-call source line.
   - Emit `LocalVariableTable` entries for lifted locals, scoped to the
     states in which they're live, with their original AST names.
   - Synthesised methods named `resume$<origFn>$<with-index>` so JVM traces
     are readable.
   - Golden test: a deliberately-throwing program under SM mode produces
     a stack trace whose top user frame points at the source line of the
     throwing expression (byte-for-byte parity with interpreter where
     possible, or a documented structural match).

---

## 13. Non-goals (14c.3)

- Loom replacement on the concurrency side. `spawn`/`par`/`race`/`scope` keep
  using virtual threads — 14c.3 only kills the vthread for `with` bodies.
- Multi-shot `resume` (deferred — see § 14).
- Whole-program escape analysis to avoid the `PerformSignal` throw entirely.
  The JIT is very good at patterns like "throw immediately caught in parent
  frame"; we measure first.

---

## 14. Tech debt / future optimisations

Recorded now so 14c.3 lands with a clean foundation and known next steps.

1. **`IrijContinuation` instance pooling.** Today: fresh alloc per `with`.
   Future: per-call-site `ThreadLocal` free-list (similar to `PerformSignal`
   pool). Hot paths that create many short-lived `with` frames would win.
   Prereq: prove it on a benchmark before adding complexity.
2. **Multi-shot `resume`.** Requires `IrijContinuation.clone()` that
   deep-copies `state` + all lifted locals. Design keeps this possible:
   no cross-instance coupling, all per-invocation state lives in fields.
   Expose `resume` as a first-class value that, when invoked twice, clones
   the snapshot. Will unlock backtracking handlers (nondet search,
   probability, parsers-as-effects).
3. **Hoist `PerformSignal` throw under JIT-friendly shape.** If profiling
   shows the throw/catch still dominates, emit a bespoke "suspend-return"
   marker object that the caller frame explicitly checks, for inner loops
   where the throw isn't being elided. Trade-off: caller-frame pollution.
4. **Escape analysis / specialisation.** For `with` bodies whose static
   analysis proves a bounded number of `perform` calls, inline the
   state-machine into the caller (skip class allocation).
5. **Shared pure-code paths.** Two bodies with identical pure sections
   shouldn't emit duplicate bytecode — dedupe via IR hashing.
6. **Debugger integration beyond line-tables.** Full JDWP support for
   stepping through state transitions (ask the JVM to report "now in state N
   of continuation X"). Needs agent-level work; deep work.
7. **Reify the handler stack for introspection.** Today: JVM stack only.
   Tool-friendly alternative: a thin per-fiber `HandlerFrame[]` that
   matches. Useful for debugging + for crash-report context.
