# Interpreter

`dev.irij.interpreter.Interpreter` — tree-walking evaluator. Spec
authority during development; bytecode follows the same semantics but
some checks (specs, effect rows) currently live only here.

## Core loop

`Interpreter.eval(Expr, Environment) → Object` is a single big `switch`
dispatching on the sealed `Expr` hierarchy. Each case produces a value
or calls back into `eval` for sub-expressions.

Statements go through `Interpreter.execStmt(Stmt, Environment)` and may
mutate the environment (`Stmt.Bind`, `Stmt.MutBind`, `Stmt.Assign`) or
have other effects (`Stmt.With`, `Stmt.Scope`, etc.).

`apply(Object fn, List<Object> args, SourceLoc)` is the universal call
operator — it dispatches over the runtime value of the callee
(`Lambda`, `MatchFn`, `ImperativeFn`, `BuiltinFn`, contracted/spec-
wrapped variants).

## Environments

`Environment` is a chained mutable map with a parent pointer.

- `globalEnv` lives on the Interpreter instance. It holds builtins,
  user-defined fns, top-level bindings.
- Each function call creates a `child()` env whose parent is the
  closure's captured env.
- `Environment.define`, `lookup`, `assign` are the three operations.
  `assign` walks the parent chain looking for an existing binding;
  `define` always creates a new binding in the current scope.

`Environment.isDefined(name)` is used in some places to test without
throwing — note it walks the chain like `lookup`.

## Value representation

| Irij type | Java type |
|---|---|
| Int | `Long` |
| Float | `Double` |
| Bool | `Boolean` |
| Str | `String` |
| Unit | `Values.UNIT` sentinel |
| Vector | `Values.IrijVector` (wraps `List<Object>`) |
| Tuple | `Values.IrijTuple` (wraps `Object[]`) |
| Map | `Values.IrijMap` (wraps `LinkedHashMap<String,Object>`) |
| Set | `Values.IrijSet` (wraps `LinkedHashSet<Object>`) |
| Tagged (constructor) | `Values.Tagged(name, args)` |
| Keyword | `Values.Keyword` |
| Range | `Values.IrijRange` (lazy bounds) |
| Function (compiled) | `RuntimeSupport.IrijFn` |
| Function (interp) | `Lambda` / `MatchFn` / `ImperativeFn` / `BuiltinFn` |
| Handler | `Values.HandlerValue` |
| Java ref | the underlying Java `Object` |

Everything boxes to `java.lang.Object`. No primitive optimisations
inside the interpreter — Long arithmetic goes through `RuntimeSupport.add`
etc. and unboxes.

## Effect stack — two thread-locals

```java
ThreadLocal<Deque<Set<String>>>            AVAILABLE_EFFECTS
ThreadLocal<Deque<EffectSystem.HandlerContext>> EffectSystem.STACK
```

`AVAILABLE_EFFECTS` is the static check: every `perform` or capability-
gated call consults `AVAILABLE_EFFECTS.peek()` and rejects if the
required effect isn't present. Top-level scripts get a special
`AMBIENT_EFFECTS` set whose `contains` always returns true; annotated
`fn`s get a finite set drawn from their `effectRow`.

`EffectSystem.STACK` is the dynamic dispatch: each `with` block pushes a
`HandlerContext` recording (effect name, handler value, op channel).
`fireOp` walks the stack innermost-first.

Both stacks are propagated into spawned vthreads via
`Interpreter.installInterpreterBuiltins` — `spawn`, `await`, scope/fork
all snapshot the parent's stacks and re-push them in the child.

## Handler dispatch (threaded)

`with X body`:

1. Resolve `X` to a `HandlerValue` (or composed chain).
2. Create a fresh `SynchronousQueue<EffectMessage>` (the op channel).
3. Push a `HandlerContext` onto `EffectSystem.STACK`.
4. Spawn a vthread to run the body. The vthread inherits the parent's
   stacks then pushes its own context.
5. Body runs. When body calls a perform, it ends up in
   `EffectSystem.fireOp(eff, op, args)`. That puts a message on the
   queue, then blocks on a per-op `resumeChannel`.
6. Meanwhile the calling thread runs `runHandlerLoop` — reads from the
   op channel, dispatches to the matching clause, when the clause calls
   `resume v` it puts `v` to the resumeChannel and re-loops.
7. Body completes → puts `Done(value)` to the op channel, handler loop
   exits, returns the value to `with`'s call site.

This is the same model as 14c.2-era bytecode mode. The bytecode "SM"
mode does NOT use this — see `effects.md`.

## When checks run

Order matters when something fails — these are the layers:

| Check | When |
|---|---|
| Effect-row enforcement | At every effect op call site (`fireOp`), at every Java interop site, at every capability-gated call. Throws `IrijRuntimeError`. |
| Spec validation (input) | On `apply` for a `SpecContractFn` — validates args against declared `::` spec before invoking. |
| Spec validation (output) | After body returns, before unwinding. |
| Contracts (pre/post) | Pre-conditions before body, post after — distinct from specs. |
| Module boundary checks | At `use mod.X` — pub-only access. |
| One-shot resume | Throws if a clause calls its `resume` twice. |

## Why tree-walking and not bytecode in dev

The interpreter exists for:

- **Spec & contract validation** that the bytecode path doesn't (yet)
  enforce — running a script through the interpreter catches more.
- **Hot-redef** via the REPL — bytecode hot-redef needs invokedynamic
  + MutableCallSite plumbing (done, but only at top-level fn calls;
  the interpreter does it for everything).
- **Effect-row inheritance for higher-order built-ins** — the
  `BuiltinFn` shape lets `fold`'s callback run in the caller's effect
  row, which is how idiomatic effect-using callbacks compose. The Irij-
  ported `std.list.fold` has a fixed row and so doesn't compose the
  same way. (Documented gap; needs effect-row polymorphism to fix.)
- **Speed of dev iteration** — no JAR build between every edit.

The bytecode path is for deploy. Production: `irij build --mode=bytecode-sm
--direct-linking`.

## TCO

The interpreter does NOT do tail-call optimization. Deep self-recursion
overflows around 5–10K frames. Bytecode mode does TCO (see `tco.md`).

A future trampoline pass in the interpreter would parallel the bytecode
work — same idea, different mechanism (return a sentinel instead of
GOTO). Not done because dev workloads are small.
