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
| `emitWith` | `Stmt.With` — runs `smCanHandle` + `classifyWithBody`, then `emitWithSM`. Unsupported shapes are compile-time errors. |
| `emitWithSM` | The (sole) effect lowering. See `effects.md`. |
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

## Namespace mode (nREPL)

`CompileOptions.namespaceMode = true` (only nREPL `eval-bytecode`
sets this) extends the var-load fallback and bind emit:

| Site | Emit when namespaceMode |
|---|---|
| `emitVarLoad`, after all lookups fail | `LDC name; INVOKESTATIC RT.nsGet` |
| `emitTopLevel` for `Stmt.Bind` with Simple target | (after the local ASTORE) `LDC name; ALOAD slot; INVOKESTATIC RT.nsPut; POP` |

`RT.NS` is a ThreadLocal map; the nREPL session installs it before
invoking each compiled eval. Cross-eval state for top-level data
binds works; cross-eval fn defs do not yet (see `nrepl.md`).

## Self-TCO

See `tco.md`. Short version: at a tail-position call to the
currently-being-emitted fn, the emitter rebinds param slots and emits
`GOTO methodEntry` instead of `INVOKESTATIC`. Recursion stays in one
JVM frame.

## What's NOT in bytecode mode (gaps)

Honest list, as of v0.5.0 — most early gaps closed; what remains:

- **Module-boundary blame** for `in`/`out` contracts. Bytecode emits
  the generic "Input contract violated in 'f'" message; the
  interpreter's caller-side / module-prefixed variant requires
  call-site provenance the emitter doesn't yet thread through.
- **Match patterns more complex than `Tagged`/literal/var/wildcard** —
  see `emitMatchExpr` for the supported shapes.
- **Operator-section partials** (e.g. `(_ + 1)`) — only the binary
  infix form `(+)` is emitted.
- **Builtins not in the emit table** — ~44 of the 91 interpreter
  builtins lack a fast path. They still work transitively via stdlib
  inlining + `RuntimeSupport.callAny` fallthrough, just not optimised.

### R5a fixes (v0.6.7)

R5a groundwork closed three gaps that previously blocked flipping
`irij run` to bytecode:

- **Top-level `MutBind`** — `mut x := …` at module scope now hoists
  to a static field; `emitAssign` routes top-level mutation through
  `PUTSTATIC` (mirrors top-level `Bind`).
- **`IrijRange` accepted by HOF runtime helpers** — `asListAny`
  (used by every `seq*` op) and `length` / `nth` materialise a
  `Range` into a `List<Object>` on demand. Stdlib HOFs that take a
  range (`zip`, `zip-with`, `enumerate`, `partition`, `interleave`,
  `window`) work in bytecode without a `to-vec` coercion.
- **Module-export dedupe on emit** — `ClassEmitter` collects
  top-level `FnDecl`s into a `LinkedHashMap` keyed by name before
  emitting JVM methods. When `:open` brings in a name the opener
  also defines (e.g. `std.collection` re-exporting `sum` from
  `std.list`), the last definition wins and only one method is
  emitted, avoiding `ClassFormatError: Duplicate method name`.

Already at parity: input + output spec validation, user-declared
product/sum specs (clinit-registered), pre/post + in/out contracts,
effect-row subsumption (compile-time `EffectRowChecker`), per-ref
JVM capability, hot redef via invokedynamic, structured concurrency,
Java interop, modules. See `docs/STATE-2026-05-18.md` for full list.

## Adding a new emit case — checklist

1. Identify which `emit*` method owns the new shape (usually
   `emitExpr` or `emitStmt`).
2. If the new operation has a Java implementation, add a static method
   to `RuntimeSupport` and `INVOKESTATIC` to it from the emit case.
3. If it's pure ASM (e.g. a new control structure), emit labels +
   jumps directly.
4. Update `emitTailExpr` if the new shape can host a tail position
   (e.g. a new conditional construct).
5. Test via integration tests (`irij test`) — bytecode-SM is the only
   execution path since v0.6.13.
