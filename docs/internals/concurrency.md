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
- Plays well with the effect system's threaded protocol (handler
  vthread + body vthread + SynchronousQueue between them).

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

Implementation: `RuntimeSupport.CompiledScopeHandle` (in bytecode
mode) and an equivalent in the interpreter. `fork(thunk)` spawns a
vthread tracked in the handle's fiber list; block-exit invokes
`joinByModifier(modifier, fibers)`.

## Inheriting effect contexts into fibers

Critical for correctness — a spawned fiber must reach handlers active
at fork time. Two thread-local stacks need to be propagated:

| Stack | Purpose |
|---|---|
| `EffectSystem.STACK` | Threaded handler contexts (interp + 14c.2 bytecode) |
| `RuntimeSupport.SM_STACK` | State-machine handler frames (14c.3 bytecode) |

`RuntimeSupport.snapParent()` snapshots both stacks (via
`ParentSnapshot` record). Spawn / forkOne / par / race / timeout /
scope-fork all use it. The fiber installs both with
`inheritEffectStack(...)` and `inheritSMStack(...)` at the top of its
run.

## Cross-mode fiber dispatch

A fiber spawned inside an SM `with` performs an op:

1. Fiber's body code calls `emitPerform` → `RT.perform` →
   `EffectSystem.fireOp`.
2. `fireOp` walks `EffectSystem.STACK` first (threaded handlers).
3. If no threaded match, falls through to `RuntimeSupport.fireOpToSM`.
4. `fireOpToSM` walks the inherited `SM_STACK` and dispatches
   synchronously: synthesise a resumeFn that just returns the resume
   value, invoke the clause on the calling (fiber) thread.
5. Returns the resume value to the fiber's `fireOp` caller.

Trade-off: fiber-side performs work, but with synchronous-resume
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
- **Deadlock between SM and threaded modes** — unlikely (fiber dispatch
  is synchronous via `fireOpToSM`), but a clause that performs a
  threaded effect while holding an SM frame's lock could theoretically
  deadlock. Hasn't surfaced in practice.
- **Effect-stack drift** — if a fiber spawns *another* fiber, the
  grandchild inherits the parent's snapshot, not the child's current
  stack. By construction, grandchildren see handlers from when their
  immediate parent forked them, which is what `Future`s are supposed
  to do. If you want the grandchild to see post-fork handlers, fork
  from inside the new `with` block.
