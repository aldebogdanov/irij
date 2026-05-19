# Phase 5 — Structured Concurrency

Inspired by Clojure's [Missionary](https://github.com/leonoel/missionary) library.

## Core Concepts

**Missionary's insight**: Concurrency should be modeled as *computation* (functional composition of values), not *conveyance* (channels, actors). Processes are first-class values with structured cancellation.

**Irij's adaptation**: `scope` blocks provide structured task scopes. Fibers (virtual threads) are first-class `Fiber` values. Combinators (`par`, `race`, `timeout`) compose concurrent operations with automatic cancellation.

## Scope Blocks

### `scope s` — Join All

All forked fibers must complete before the scope exits. First error cancels siblings.

```irj
scope s
  f1 := s.fork (-> fetch-user 1)
  f2 := s.fork (-> fetch-user 2)
  println (await f1)
  println (await f2)
;; scope waits for f1 and f2 even without explicit await
```

### `scope.race s` — First Success Wins

First fiber to succeed returns its value. All other fibers are cancelled.

```irj
scope.race s
  s.fork (-> fetch-from-primary)
  s.fork (-> fetch-from-replica)
  s.fork (-> sleep 5000; error "timeout")
;; fastest response wins, others cancelled
```

### `scope.supervised s` — Isolated Failures

Failed fibers log their error but don't cancel siblings. The scope still waits for all fibers.

```irj
scope.supervised s
  s.fork (-> risky-operation-1)
  s.fork (-> risky-operation-2)
  s.fork (-> always-succeeds)
;; if risky-operation-1 fails, others continue
```

## Fiber API

### `s.fork (-> body)` — Fork a Fiber

Creates a new virtual thread running `body`. Returns a `Fiber` value. The fiber inherits the parent's effect handler stack.

### `await fiber` — Wait for Result

Blocks until the fiber completes. Returns the fiber's result value, or rethrows its error.

```irj
scope s
  f := s.fork (-> expensive-computation)
  ;; ... do other work ...
  result := await f  ;; blocks here until fiber done
```

## Combinators

### `par f thunk1 thunk2 ...` — Parallel Composition

Runs all thunks concurrently in virtual threads. When all complete, applies `f` to their results. If any thunk fails, cancels the rest and propagates the error.

```irj
;; Add two concurrent results
sum := par (+) (-> fetch-count "a") (-> fetch-count "b")

;; Collect three results into a vector
fn vec3 => a b c
  #[a b c]
results := par vec3 (-> task-1) (-> task-2) (-> task-3)
```

Missionary equivalent: `(m/join f task1 task2)`

### `race thunk1 thunk2 ...` — First Success Wins

Runs all thunks concurrently. First to succeed returns its value; others are cancelled. If ALL fail, propagates the first error.

```irj
result := race
  (-> fetch-from-primary)
  (-> fetch-from-replica)
  (-> sleep 5000; error "all sources down")
```

Missionary equivalent: `(m/race task1 task2)`

### `timeout ms thunk` — Deadline

Runs thunk in a virtual thread. If it doesn't complete within `ms` milliseconds, cancels it and throws a timeout error.

```irj
;; Int argument = milliseconds
result := timeout 5000 (-> slow-operation)

;; Float argument = seconds
result := timeout 2.5 (-> slow-operation)

;; Catch timeout errors
result := try (-> timeout 100 (-> sleep 5000))
;; → Err "timeout: operation exceeded 100ms"
```

Missionary equivalent: `(m/timeout task ms)`

## Cancellation Model

Cancellation is **cooperative** via `Thread.interrupt()`:

1. When a fiber is cancelled, its thread is interrupted
2. `sleep` checks for interrupt and throws `"interrupted"` error
3. The error propagates up, unwinding the fiber's call stack
4. Non-blocking code runs to completion (no preemptive kill)

This matches Missionary's philosophy: cancellation is a signal, not a force-stop. Code should be written to respond to cancellation at natural suspension points.

## Effect Handler Inheritance

Fibers inherit the parent thread's effect handler stack. Effects fired inside a fiber are handled by the enclosing `with` block:

```irj
effect Log
  log :: Str -> ()

handler console-log :: Log
  log msg => println msg

with console-log
  scope s
    s.fork (-> log "from fiber")  ;; handled by console-log
```

## Value Types

| Type | Display | Created by |
|------|---------|-----------|
| `Fiber` | `<fiber 42>` | `s.fork (-> body)` |
| `ScopeHandle` | `<scope>` / `<scope.race>` | `scope s` block |

## Relationship to `spawn`

`spawn` (Phase 2.75) is fire-and-forget — no structured guarantees:

| | `spawn` | `scope.fork` |
|---|---------|-------------|
| Returns | `Thread` | `Fiber` (with result) |
| Parent waits? | No | Yes (scope exit) |
| Error propagation | Logged to stdout | Propagated to scope |
| Cancellation | Manual | Automatic (scope/race) |
| Use case | Background daemons | Structured parallel work |

`spawn` remains useful for long-running background tasks. `scope`/`fork` is for structured parallel computation.

## Files Changed

| File | Changes |
|------|---------|
| `Values.java` | Added `Fiber` and `ScopeHandle` records |
| `Interpreter.java` | `execScope`, `joinAll`, `raceAll`, `supervisedJoinAll`; `await`/`timeout`/`par`/`race` builtins |
| `Builtins.java` | `toMillis` helper; `sleep` now throws on interrupt |
| `InterpreterTest.java` | 21 new structured concurrency tests |

## Deferred to Phase 5b

- **Channels**: `send`/`recv`/`select` for inter-fiber communication
- **`detach!`**: Explicit escape hatch from structured concurrency (`spawn` serves this role)
- **Backpressure**: Missionary's pull-based Flow protocol (needs streaming foundation)
