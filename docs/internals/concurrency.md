# Concurrency

JDK21+ virtual threads underlie everything. Structured concurrency
primitives are inspired by Missionary (Clojure) and Trio (Python).

## Primitives

| Operator | Shape | Semantics |
|---|---|---|
| `spawn` | `spawn thunk → Fiber` | Fire-and-forget vthread. |
| `await` | `await fiber → result` | Block until done. |
| `sleep` | `sleep ms → ()` | Block this thread for ms. |
| `par` | `par combiner t1 t2 ... → combiner r1 r2 ...` | Run all in parallel, combine results. |
| `race` | `race t1 t2 ... → result of first to finish` | Others interrupted. |
| `timeout` | `timeout ms t → result or error` | Cancel after deadline. |
| `try` | `try t → Ok r / Err msg` | Catch errors. |
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

Implementation: `RuntimeSupport.CompiledScopeHandle`. `fork(thunk)`
spawns a vthread tracked in the handle's fiber list; block-exit
invokes `joinByModifier(modifier, fibers)`.

## Inheriting effect contexts into fibers

Critical for correctness — a spawned fiber must reach handlers active
at fork time. Two thread-local stacks need to be propagated:

| Stack | Purpose |
|---|---|
| `RuntimeSupport.SM_STACK` | State-machine handler frames (the only effect dispatch path since v0.6.13) |
| `EffectSystem.STACK` | Legacy handler-context stack; still walked as a fallback by `fireOp` for fibers spawned outside any SM `with` |

`RuntimeSupport.snapParent()` snapshots both stacks (via
`ParentSnapshot` record). Spawn / forkOne / par / race / timeout /
scope-fork all use it. The fiber installs both with
`inheritEffectStack(...)` and `inheritSMStack(...)` at the top of its
run.

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

`Thread.interrupt()` is the primary signal. Effect ops that block in
`SynchronousQueue.put/take` propagate `InterruptedException` →
translated to `IrijRuntimeError("Effect operation interrupted: ...")`.
The interrupting code (race, timeout, scope.race) collects winners and
errors via `CompletableFuture`.

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
