# Phase 3 — Algebraic Effects & Handlers

## Overview

Irij's effect system is the core mechanism for controlled side effects. Functions declare *what* operations they need (e.g., "I need to print"); handlers provide *how* those operations work. This enables pure testing, dependency injection, and structured concurrency — all through one composable mechanism.

**Continuation model**: One-shot, explicit resume, deep handlers.
**Implementation**: VirtualThread + SynchronousQueue per `with` block.

## Files

| File | Purpose |
|------|---------|
| `EffectSystem.java` | ThreadLocal handler stack, `EffectMessage` types, `fireOp()` |
| `Values.java` | `EffectDescriptor`, `HandlerValue`, `ComposedHandler` records |
| `Decl.java` | `EffectDecl`, `HandlerDecl` (with `stateBindings`), `HandlerClause` |
| `AstBuilder.java` | `visitEffectDecl`, `visitHandlerDecl` (processes binding clauses) |
| `Interpreter.java` | `evalEffectDecl`, `evalHandlerDecl`, `execWith`, `runHandlerLoop`, `execComposedWith` |
| `InterpreterTest.java` | `Effects` nested class (24 tests) |

## Effect Declarations

```irj
effect Console
  print :: Str -> ()
  read-line :: () -> Str
```

- Registers an `EffectDescriptor` in the environment under the effect name (`Console`)
- Registers each op as a `BuiltinFn` with arity `-1` (variadic, type signatures not checked yet)
- The op function calls `EffectSystem.fireOp(effectName, opName, args)` when invoked
- **Important**: Effect ops shadow builtins with the same name. Declaring `effect Console` with `print` replaces the builtin `print`. Use `println` for direct output.

## Handler Declarations

```irj
handler mock-console :: Console
  state :! #[]                         ;; handler-local mutable state
  print msg =>
    state <- state ++ #[msg]
    resume ()
  read-line () =>
    result := head state
    state <- tail state
    resume result
```

- Creates a `HandlerValue` (first-class, storable, composable)
- State bindings (`:!` lines) are executed into a closure environment
- Handler clauses map op names to pattern-match arms with bodies
- `resume` is injected as a one-shot `BuiltinFn` into each arm's environment

## `with` Blocks

```irj
with handler
  body statements
on-failure
  fallback statements
```

### Execution Flow

1. Handler expression evaluated → `HandlerValue`
2. `SynchronousQueue<EffectMessage>` created for body→handler communication
3. Body runs in a **VirtualThread** with the handler pushed onto its ThreadLocal stack
4. Main thread enters `runHandlerLoop` — blocks on the queue
5. When body performs an effect op → `fireOp` sends `Op` message, blocks on resume channel
6. Handler loop receives `Op`, dispatches to arm, evaluates with `resume` injected
7. When body finishes → `Done(result)` message → handler loop returns

### Resume Semantics (Explicit)

Handler arms must call `resume v` to continue the paused body:

```irj
;; Resume: body continues, print returns ()
handler console :: Console
  print msg =>
    println msg          ;; actual IO
    resume ()            ;; continue body

;; Abort: body killed, arm's value is the with block result
handler catch :: Exn
  throw msg => "caught: " ++ msg    ;; no resume → abort
```

- `resume(v)` sends `v` to the body thread, then enters a **recursive** handler loop
- The recursive loop handles any further effect ops from the body
- When the body finishes, `resume` returns the body's final value
- One-shot enforced via `AtomicBoolean` — double resume throws error

### on-failure Clause

```irj
with handler
  risky-operation ()
on-failure
  println ~ "Error: " ++ error      ;; `error` is bound to the error message
  "fallback value"
```

- Runs when the `with` body raises an unhandled error
- Not triggered on success
- `error` variable bound to the error message string
- Return value of `on-failure` block becomes the `with` block result

## Handler Composition

```irj
with (print-log >> const-state >> http-cache)
  log "hello"
  x := get-val ()
  fetch url
```

- `h1 >> h2` creates a `ComposedHandler` value
- Flattens nested compositions: `(h1 >> h2) >> h3` = three handlers, not nested pairs
- `with` decomposes into nested handler installations via `execComposedWith`
- Equivalent to: `with h1 (with h2 (with h3 body))`

## Handler Dot-Access

```irj
handler counting :: Counter
  state :! 0
  inc () =>
    state <- state + 1
    resume ()

with counting
  inc ()
  inc ()

counting.state    ;; → 2 (reads from handler's closure env)
```

- `handler.field` reads from the handler's closure environment
- Useful for inspecting handler state after a `with` block completes

## Nested Handlers

```irj
with outer-handler
  with inner-handler
    ;; inner handler takes precedence for its effect
    ;; outer handler handles different effects
```

- Body thread's handler stack is **copied** from parent thread + new handler pushed
- Stack scanned top-down (LIFO) — innermost handler matched first
- Each handler's opChannel connects to the thread running its handler loop

## Limitations

1. **No multi-shot continuations**: `resume` can only be called once per handler arm
2. **Effect ops shadow builtins**: declaring an effect op with a builtin's name replaces it
3. **No effect rows in types**: `-[E1 E2]>` is parsed but not enforced (needs type checker)
4. **INDENT/DEDENT in parens**: `match` expressions in handler arms work, but not inside lambda `(x -> match ...)`
5. **Spawned threads**: have no handler stack — effect ops in spawned threads fail with "unhandled effect"

## Test Examples

```irj
;; Basic effect + resume
effect Ask
  ask :: () -> Int
handler answer :: Ask
  ask () => resume 42
with answer
  x := ask ()
  println x              ;; 42

;; Abort (exception pattern)
effect Exn
  throw :: Str -> ()
handler catch-all :: Exn
  throw msg => "caught: " ++ msg
result := with catch-all
  throw "boom"
  99                       ;; never runs
;; result = "caught: boom"

;; Mock-console (pure testing)
effect Console
  print :: Str -> ()
  get-output :: () -> ()
handler mock-console :: Console
  state :! #[]
  print msg =>
    state <- state ++ #[msg]
    resume ()
  get-output () => resume state
with mock-console
  print "Hello!"
  print "Done"
  println ~ get-output ()  ;; #[Hello! Done]
```
