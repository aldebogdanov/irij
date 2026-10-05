# Concurrency

JDK21+ virtual threads underlie everything. Structured concurrency
primitives are inspired by Missionary (Clojure) and Trio (Python).

## Primitives

| Operator | Shape | Semantics |
|---|---|---|
| `spawn` | `spawn thunk → Fiber` | Start a fiber. Awaitable; safe to discard (failures print to session out either way — awaiting one also rethrows). |
| `await` | `await fiber → result` | Block until done. Accepts only `Fiber` values (from `spawn`, `par`/`race` internals, or `scope` forks). |
| `sleep` | `sleep ms → ()` | Block this thread for ms. |
| `par` | `par combiner t1 t2 ... → combiner r1 r2 ...` | Run all in parallel, combine results. |
| `race` | `race t1 t2 ... → result of first to finish` | Others interrupted. |
| `timeout` | `timeout ms t → result or error` | Cancel after deadline. |
| `try` | `try t → Ok r / Err v` | Catch errors. `v` is the value raised with `error v` (a map stays a map), or the message of a runtime/JVM error. |
| `scope` | `scope { body }` | Structured fork/join — see below. |

## Virtual threads

Every fiber is a `Thread.startVirtualThread(...)`. No native thread
pool, no manual scheduling. The JVM's `ForkJoinPool` carrier multiplexes.

Why virtual threads:

- Cheap (~1 KB per fiber, fast spawn, fast park/unpark).
- Block-friendly — `Thread.sleep`, `SynchronousQueue.put/take`, JDBC,
  HTTP clients all "just work" — the JVM yields the carrier when a
  vthread blocks.
- Plays well with the effect system's SM trampoline — every spawned
  fiber inherits the parent's `SM_STACK` snapshot so its performs
  reach the parent's handlers.

## Structured concurrency: `scope`

```
scope s
  s.fork (-> work-a)
  s.fork (-> work-b)
  ;; auto-joins at block exit
```

Modifiers:

- `scope` (default) — wait for all children; rethrow if any failed.
- `scope.race` — wait for first child; cancel the rest.
- `scope.supervised` — let children fail independently; main body
  result is what scope returns.

Implementation: `CompiledScopeHandle (top-level in dev.irij.compiler)`. `fork(thunk)`
spawns a vthread tracked in the handle's fiber list; block-exit
invokes `joinByModifier(modifier, fibers)`.

## Inheriting effect contexts into fibers

Critical for correctness — a spawned fiber must reach handlers active
at fork time. Two thread-local stacks need to be propagated:

| Stack | Purpose |
|---|---|
| `RuntimeSupport.SM_STACK` (dispatch machinery itself lives in `RtEffects`) | State-machine handler frames (the only effect dispatch path since v0.6.13) |
| `RtEffects.EFFECT_ROW` | Declared effect-row frames (the runtime backstop, `effects.md`) |

`RtConcurrency.snapParent()` snapshots both (via the `ParentSnapshot`
record, along with the session `NS` / `SESSION_OUT`). Spawn / forkOne /
par / race / timeout / scope-fork all use it. The fiber installs them
with `inheritSMStack(...)` and `inheritEffectRow(...)` at the top of its
run. A perform outside any SM body (`RtEffects.perform`) dispatches
synchronously to the innermost matching `SM_STACK` frame.

## Capability callbacks on foreign executor threads

Same inheritance need shows up outside `spawn`: any Java capability
that hands user-supplied IrijFn control to a thread it didn't create
(typically an executor inside the JDK or a third-party lib) sees the
same empty-thread-local trap. Concrete case: `ServeCapability.serve`
hands each request to `IrijHttpServer`, which runs it on a fresh
virtual thread per connection. Empty `EFFECT_ROW` / `SM_STACK` on that thread
means any `perform` in the user's handler body dies with "Unhandled
effect: X.op (no handler on stack)".

The public snapshot API for capabilities:

```java
EffectSnapshot snap = RtEffects.snapshotEffects();
// later, on the worker thread:
Object result = RtEffects.runWithEffectSnapshot(snap,
        () -> RuntimeSupport.callAny(userHandler, new Object[]{arg}));
```

`snapshotEffects()` is `snapParent` exposed as an opaque token.
`runWithEffectSnapshot` installs `SM_STACK`, `EFFECT_ROW`, `NS`, and
`SESSION_OUT` from the snapshot, then
runs the supplied body. No restore step — the worker thread is
assumed to be one-shot (a request handler that dies after the
response, a scheduled callback that fires once).

When to use:
- Capability schedules user code on a JDK executor (HTTP server,
  WebSocket frame dispatcher, file watcher, scheduled task).
- Capability invokes a user callback from a callback-style
  third-party library (DB driver event listener, queue consumer
  loop).

When *not* to use:
- The cap method runs synchronously on the calling thread — no
  thread hop, no need to snapshot.
- The cap intentionally wants an isolated effect context (rare).

## Fiber-side perform dispatch

A fiber spawned inside an SM `with` performs an op:

1. Fiber's body throws `PerformSignal` exactly like any other body.
2. If the fiber is running inside its own `dispatchLoopSMImpl`, that
   loop catches it. Otherwise the signal escapes into the fiber's
   entry-point wrapper which delegates to `fireOpToSM`.
3. `fireOpToSM` walks the inherited `SM_STACK` and dispatches
   synchronously: synthesise a resumeFn that just returns the resume
   value, invoke the clause on the calling (fiber) thread.
4. Returns the resume value to the perform site.

Trade-off: fiber-side performs work with synchronous-resume
semantics (post-resume clause stmts run on the calling thread before
the value propagates). Same property as the on-thread trampoline.
Acceptable for idiomatic tail-position `resume v`; pathological
non-tail clauses might surprise.

## Cancellation

`Thread.interrupt()` is the primary signal. The interrupting code
(race, timeout, scope.race, a cancelled scope, the playground's eval
timeout) collects winners and errors via `CompletableFuture`. The
interrupted fiber stops at its next **cancellation point**, which
throws `IrijRuntimeError("cancelled: the computation was interrupted")`:

- **Loop back-edges.** Every self-tail-call `GOTO` (the only loop
  compiled Irij code has — see `tco.md`) is preceded by
  `RtConcurrency.checkCancelled()`, a `Thread.isInterrupted()` poll.
  Without it a CPU-bound fiber ignored its interrupt and ran on after
  `timeout` had already returned; `scope`'s `cancelAll` (interrupt,
  then join) hung on it outright.
- **`sleep`.** An interrupted sleep throws instead of returning early.
  Returning early turned a cancelled sleep loop into a busy loop: the
  flag was re-set, so every later sleep returned at once.
- **Blocking ops** — effect ops blocked in `SynchronousQueue.put/take`
  (`"Effect operation interrupted: ..."`), `proc` waits, the HTTP
  client.

The check reads the flag without clearing it, so cancellation is
sticky: a `try` that swallows the error is stopped again at the next
poll. Non-tail recursion has no poll; it ends at `StackOverflowError`.
Java-side loops inside builtins (a `fold` over a huge collection) are
not cancellation points.

## Why not channels / actors

Missionary-style flow + scope is a tighter fit than CSP channels —
fewer primitives, clear lifetimes, no risk of dangling fibers. The
language might grow channels later; the building blocks (vthreads,
SynchronousQueue) are already there.

## Failure modes

- **Orphaned fibers** — `spawn` with no `await` and no enclosing
  scope. They run to completion or program-exit. Not currently
  tracked / leaked. Use `scope` to bound lifetime.
- **Deadlock via synchronous fiber dispatch** — `fireOpToSM` runs the
  clause on the calling fiber thread. A clause that blocks waiting for
  its own fiber's progress could deadlock; in practice no idiomatic
  handler shape produces this.
- **Effect-stack drift** — if a fiber spawns *another* fiber, the
  grandchild inherits the parent's snapshot, not the child's current
  stack. By construction, grandchildren see handlers from when their
  immediate parent forked them, which is what `Future`s are supposed
  to do. If you want the grandchild to see post-fork handlers, fork
  from inside the new `with` block.
