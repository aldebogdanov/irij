# Bytecode compiler

`dev.irij.compiler.ClassEmitter` + `RuntimeSupport`. Emits a single
JVM class per program. Uses [ASM](https://asm.ow2.io/) for the
low-level bytes.

## Output shape

For `irij.Program`:

- One `public final class irij.Program`.
- `public static main(String[])` — entry point.
- `public static <name>(Object, Object, ...) -> Object` per top-level
  user `fn`.
- `private static synthetic lambda$N(captures..., Object[] args) -> Object`
  per `Expr.Lambda` literal.
- `private static synthetic smstep$N` / `clauseStep$tierC$N` /
  `clauseWrap$tierC$N` / `f$irijfn` / handler builder methods —
  generated as the emitter needs them.
- Per-handler static fields for mutable handler state.
- Per-user-fn static `IrijFn` adapter (`f$irijfn`) only emitted when
  the fn is referenced as a value.

Everything is `static`. No instance state. No subclasses except for
internal helpers (`IrijContinuation` lives in `RuntimeSupport`, not in
the user's class).

## Value model

Same as the interpreter — every value boxes to `java.lang.Object`. No
specialised int/long arrays. Method signatures are
`(Object, Object, ...) -> Object` everywhere except the
`IrijFn.apply([Object[]) -> Object` SAM and a few primitive-typed
arg helpers (e.g. `nth` takes a boxed `Long`).

Trade-off: zero perf gain from JIT-friendly specialised types, but
homogeneous code is much smaller and the JIT does its own boxing-
elimination at hot sites.

## What `emit*` methods cover

| Method | Handles |
|---|---|
| `emitFn` | Top-level `Decl.FnDecl`. Sets up TCO entry label, dispatches by `FnBody` variant. |
| `emitTailExpr` | Expression at tail position — lowers self-tail-calls to GOTO. Falls through to `emitExpr + ARETURN`. |
| `emitExpr` | Big switch over `Expr` variants. The main workhorse. |
| `emitStmt` | Statements: bind, mut-bind, assign, with, scope, if-stmt, match-stmt. |
| `emitApp` | Function calls. Decides between built-in, effect op, constructor, local lambda value, lifted slot, or user-fn `invokestatic`. |
| `emitBuiltinApp` | Hardcoded knowledge of built-in fns (`+`, `++`, `println`, `spawn`, `length`, `head`, `tail`, `nth`, `conj`, `empty?`, ...). |
| `emitLambda` | Lambda literal → private static method + LambdaMetafactory indy at call site. |
| `emitMatchExpr` | Pattern match → if-chain + bind on success. |
| `emitWith` | `Stmt.With` dispatch — picks between threaded and SM lowering. |
| `emitWithSM` / `emitWithThreaded` | The two effect lowerings. See `effects.md`. |
| `emitTierCClauseLambda` | Compile a clause body as an SM continuation. |
| `emitHandlerBuilder` | `handler X :: Eff` → static method building a `CompiledHandler`. |
| `emitScope` | `scope { fork ... }` → `CompiledScopeHandle` allocation + body. |

## Variable resolution at emit time

`emitVarLoad(name)` follows a priority list:

1. `true`/`false` literals → `GETSTATIC Boolean.TRUE/FALSE`.
2. JVM local slot (function param, `:=` bind) → `ALOAD slot`.
3. Lifted-locals map (SM mode) → `ALOAD kSlot` + `GETFIELD fields` +
   `ICONST idx` + `AALOAD`. Used when a local must survive an SM
   perform-throw.
4. Handler-state field → `GETSTATIC internalName.field`.
5. Handler builder → `INVOKESTATIC handlerBuild$name`.
6. User fn by name (referenced as a value) → emit a one-time
   `f$irijfn` adapter + LambdaMetafactory indy returning an `IrijFn`.
7. Otherwise → `CompileException("Unbound variable: " + name)`.

Each level is one fast path. The lifted-locals map is the only one
that's per-emit-context (set by `emitSMSequence` / `emitSMEffIR`
before they invoke recursive emits).

## Function calls

`emitApp(App app)` tree:

```
fn shape?
├── TypeRef Foo                → Tagged-value constructor
├── Var v
│   ├── v is in emitBuiltinApp's table       → INVOKESTATIC RT.builtin
│   ├── v is an effect op                    → emitPerform (throws PerformSignal in SM body emit, fireOp elsewhere)
│   ├── v starts with uppercase              → constructor application
│   ├── v is a JVM local                     → emit as IrijFn invocation
│   ├── v is in currentLiftedLocals          → emit as IrijFn invocation
│   └── otherwise — `fnArity.get(v)`         → arg eval + INVOKESTATIC
│       └── if --direct-linking off          → invokedynamic via RT.redefBootstrap
└── non-Var (lambda, app result, etc.)       → emit as IrijFn invocation
```

The fnArity path is the common case — direct dispatch when the callee
is known statically. Indirect dispatch through `IrijFn` is used for
first-class fns and for lambda-literal calls.

## `--direct-linking` and indy

`CompileOptions.directLinking`:

| Flag | User-fn call shape |
|---|---|
| `false` (default, dev) | `invokedynamic` via `RuntimeSupport.redefBootstrap` → `MutableCallSite` → `f$impl`. REPL can swap target. |
| `true` (deploy) | Plain `invokestatic` to `f$impl`. Max JIT inlinability; no redef. |

See `hot-redef.md` for details.

## Self-TCO

See `tco.md`. Short version: at a tail-position call to the
currently-being-emitted fn, the emitter rebinds param slots and emits
`GOTO methodEntry` instead of `INVOKESTATIC`. Recursion stays in one
JVM frame.

## What's NOT in bytecode mode (gaps)

Honest list of what compiles in the interpreter but not (yet) in
bytecode:

- Spec validation (input/output) — runs in interp via `SpecContractFn`,
  not yet wired through the emitter.
- Contracts (pre/post conditions, laws) — interp-only.
- Match patterns more complex than `Tagged`/literal/var/wildcard — see
  `emitMatchExpr` for the supported shapes.
- Some operator section shapes — only binary infix `(+)`, not partial
  application like `(_ + 1)`.
- `each`, sort, group-by, distinct, flatten — these live as
  interpreter `BuiltinFn`s. They work *transitively* via callAny
  fallthrough but aren't optimised.

The interpreter is the more-complete back-end. Bytecode is more
performant but feature-trails. Both follow the same AST so the gap can
be closed feature-by-feature.

## Adding a new emit case — checklist

1. Identify which `emit*` method owns the new shape (usually
   `emitExpr` or `emitStmt`).
2. If the new operation has a Java implementation, add a static method
   to `RuntimeSupport` and `INVOKESTATIC` to it from the emit case.
3. If it's pure ASM (e.g. a new control structure), emit labels +
   jumps directly.
4. Update `emitTailExpr` if the new shape can host a tail position
   (e.g. a new conditional construct).
5. Test in both interpreter and bytecode modes (both with `--mode=
   bytecode-threaded` and `--mode=bytecode-sm`).
