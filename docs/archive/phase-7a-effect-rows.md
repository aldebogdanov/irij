# Phase 7a: Effect Row Declarations + Checking

## Overview

Functions and handlers must declare which effects they perform via effect row
annotations. The runtime enforces that effectful builtins (`println`, `print`,
`dbg`, `read-line`) and user-defined effect operations can only be called from
contexts that declare the appropriate effects.

## Syntax

### Functions

```irij
;; Pure function (unannotated = pure, no effects allowed)
fn add
  (a b -> a + b)

;; Effectful function
fn greet ::: Console
  (name -> println ("Hello, " ++ name))

;; Multiple effects
fn sync ::: Console FileIO Async
  => x
  println x
```

### Handlers

Handlers can also declare effects their clause bodies need:

```irij
;; Handler that needs Console to implement its clauses
handler console-log :: Logger ::: Console
  log msg =>
    println ("[LOG] " ++ msg)
    resume ()

;; Pure handler (unannotated = pure, no effects in clauses)
handler friendly :: Greet
  greet name => resume ("Hello, " ++ name)
```

### Full type annotation with effects

```irij
fn greet :: Str () ::: Console
  (name -> println ("Hello, " ++ name))
```

Uses `:::` to separate effect declarations from the type signature.
`::` for data types (last = output), `:::` for effects.
Empty effect rows (`:::` with no names) are a parse error — just omit
the annotation for pure functions/handlers.

## Rules

| Context | Effect checking |
|---------|----------------|
| Top-level statements | Ambient — all effects available |
| `fn` with no annotation | Pure — NO effects allowed |
| `fn name ::: Console` | Only Console effect ops allowed |
| `fn name ::: A B C` | Only A, B, C effect ops allowed |
| `handler h :: E` (no annotation) | Pure — clause bodies have no effects |
| `handler h :: E ::: Console` | Clause bodies can use Console |
| `with handler` block | Adds handler's effect to body's available set |
| Anonymous lambda `(x -> ...)` | Inherits parent context |

## Effect Checking Semantics

Each function establishes its own effect context:

- When a function is called, its effect row is pushed onto a per-thread
  `AVAILABLE_EFFECTS` stack (empty set for unannotated functions)
- Effectful builtins (`println`, `print`, `dbg`, `read-line`) check that their
  required effect (`Console`) is in the current available set
- User-defined effect operations (from `effect` declarations) check that their
  effect name is in the current available set
- When the function returns, its effect row is popped

This means a pure function CAN call an annotated effectful function — each
function carries its own context. The check prevents bare `println` calls
from pure functions.

**Unannotated functions are pure**: Functions without an effect annotation are
treated as pure (empty effect row). All functions must explicitly declare their
effects. Only anonymous lambdas (`(x -> ...)`) inherit the parent context.

**Handler clauses run with the handler's declared effects**: Handler clause
bodies (e.g., `log-it msg => resume (println msg)`) execute with the effect
set declared on the handler (`::: Console` in this case). Handlers that need
to call effectful builtins must declare those effects. Unannotated handlers
have pure clause bodies.

## Built-in Effect Tags

| Builtin | Required Effect |
|---------|----------------|
| `println` | Console |
| `print` | Console |
| `dbg` | Console |
| `read-line` | Console |

User-defined effect operations (from `effect E` declarations) automatically
require their parent effect name.

## New Builtin: `read-line`

```irij
fn ask ::: Console
  => prompt
  print prompt
  read-line ()
```

`read-line` reads a line from stdin. Requires Console effect.

## Examples

```irij
;; Pure function — no annotation needed
fn add
  (a b -> a + b)

;; Effectful function — must declare Console
fn greet ::: Console
  (name -> println ("Hello, " ++ name))

;; User-defined effect with checking
effect Log
  log :: Str -> ()

;; Handler needs Console to implement via println
handler console-log :: Log ::: Console
  log msg =>
    println msg
    resume ()

fn do-work ::: Log
  (msg -> log msg)

;; Top-level — all effects available (ambient)
greet "world"
println (add 3 4)
```

## Test Coverage

- 26 Java unit tests (`InterpreterTest.EffectRows`)
- 12 parser smoke tests (`ParserSmokeTest.EffectRowAnnotations`)
- 16 integration tests (`tests/test-effect-rows.irj`)

## Files Changed

- `IrijParser.g4` — `effectAnnotation` rule (requires `typeName+`, not `*`);
  added optional `effectAnnotation` to `handlerDecl`
- `Decl.java` — `effectRow` field in `FnDecl`; `requiredEffects` field in
  `HandlerDecl`
- `AstBuilder.java` — extracts effect rows from fn and handler declarations
- `Values.java` — `effectRow` in `Lambda`; `requiredEffects` in `BuiltinFn`
  and `HandlerValue`
- `Interpreter.java` — `AVAILABLE_EFFECTS` stack, push/pop in `apply()` and
  `runHandlerLoop`, effect checking, propagation to threads
- `Builtins.java` — tagged `println`/`print`/`dbg` with Console, added `read-line`
