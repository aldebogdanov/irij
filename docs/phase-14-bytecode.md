# Phase 14 — Bytecode Compiler (MVP spike)

Status: **14b — in progress on `bytecode-mvp` branch.** 14a MVP + strings/collections/match + ADT constructor dispatch green.

Experimental ahead-of-time compiler that targets JVM bytecode directly (ASM 9.x),
running alongside the interpreter — not replacing it. Shares AST and parser with
the tree-walking interpreter.

## Scope of 14a (implemented)

- Literals: `Int`, `Float`, `Bool`, `Str`, `()`
- Vars: `true`/`false` (as Vars), local slots
- Bindings: `x := expr` (Simple targets only)
- Control flow: `if/else` (expression and statement), boolean coercion via `RuntimeSupport.truthy`
- Binary ops: `+ - * / %` (Long/Double dispatched), `< <= > >= == !=`
- Unary ops: `-`, `!`
- User functions:
  - `fn name` / `pub fn name` with `LambdaBody` or `ImperativeBody`
  - `VarPat` / `WildcardPat` parameters
  - Forward references (two-pass: collect arities, then emit methods)
  - Recursion works (`fact`, `fib` dual-runtime-verified)
- Built-ins: `print` → `RuntimeSupport.print` (no newline), `println` → `RuntimeSupport.println`

## Scope of 14b (implemented so far)

- `match` expression: literal, var, wildcard, unit, keyword, grouped, constructor,
  vector (with spread), tuple, destructure (map) patterns; guards via `|`
- `MatchArmsBody` function form (`fn name\n  pat => body`) — arity-1 dispatch
- Constructor application of spec tags (`Circle 3.0` → `Values.Tagged`)
- `SpecDecl` at top level — collects product-spec field names for record
  destructure; constructors built as `Values.Tagged` with `namedFields` when applicable
- Destructuring binds in `:=` (record/vector/tuple/constructor patterns)
- Self-contained jar bundles all `dev.irij.*` runtime classes (excluding
  `cli`/`repl`/`nrepl`/`mcp`) so compiled programs run standalone
- First-class lambda values via `RuntimeSupport.IrijFn` + `invokedynamic` /
  `LambdaMetafactory`; closures capture enclosing locals; higher-order fns
  (pass lambdas as args, call local fn values) work
- Rest params in lambdas (`(a ...rest -> ...)` → `rest` bound to `IrijVector`
  of remaining args via `RuntimeSupport.restVector`)
- Protocols / impls: `proto` no-op at runtime; each impl binding compiled to
  `impl$method$Type` static method; dispatcher `method(...)` switches on
  `RuntimeSupport.typeTag(arg0)` — covers primitives, collections, and
  spec-tagged values
- Collection literals: `Vector`, `Tuple`, `Set`, `Map` (via interpreter `Values`)
- Keyword literals (`:foo`)
- String/vector `++` concat, `to-str` builtin, `&&` / `||` short-circuit
- `display` delegates to `Values.toIrijString` for interpreter parity

## Not yet supported (14c+)

- Algebraic effects, handlers, `with`, `resume`, `on-failure` (14c)
- Concurrency, modules, Java interop (14d)
- Protocols / `impl` dispatch
- Effects (algebraic effects — 14c plans CPS/state-machine lowering)
- Concurrency, handlers, `with`
- Module system, imports
- Java interop (`Class/method` and dot-access)

Compile errors are explicit (`MVP: unsupported expression: …`) so the escape
hatch is always the interpreter.

## Value representation

All Irij values are boxed Java `Object`:

| Irij     | Java       |
|----------|------------|
| `Int`    | `Long`     |
| `Float`  | `Double`   |
| `Bool`   | `Boolean`  |
| `Str`    | `String`   |
| `()`     | `null`     |

Arithmetic dispatches in `RuntimeSupport`: both-Long → long op; otherwise
double op. Comparison follows the same path.

## Entry points

```sh
irij compile file.irj                 # emits file.class (class irij.Program)
irij compile file.irj -o out.class    # custom class output
irij compile file.irj -o app.jar      # self-contained runnable jar
java -jar app.jar                     # runs the compiled program
```

The `.jar` mode bundles `dev.irij.compiler.RuntimeSupport` so the jar is
self-contained.

## Architecture

```
src/main/java/dev/irij/compiler/
  IrijCompiler.java     — entry point: source → classfile bytes
  ClassEmitter.java     — two-pass emit: register fn arities → emit methods → main
  RuntimeSupport.java   — boxed-object runtime helpers (arith, compare, display, print)

src/main/java/dev/irij/cli/
  CompileCommand.java   — `irij compile` CLI driver, .class + .jar output modes
```

## Tests

- `HelloWorldCompileTest` (17 tests) — end-to-end compile → define → invoke
- `DualRuntimeGoldenTest` (18 programs) — same source runs through interpreter
  and compiler; outputs must match byte-for-byte. Covers arith, bindings, fns,
  recursion, match expressions, guards, collections, string concat, ADT
  constructor dispatch, `MatchArmsBody` fn form.

## Next (14b — patterns & protocols)

Extend bodies to `MatchArmsBody`, constructor-pattern dispatch, protocol
method tables. Introduce a tagged-ADT runtime (probably a `IrijVariant`
record with tag + args) so constructor patterns can match at runtime.

## Next (14c — effects)

Three options on the table (tree-walk semantics are CPS-driven, bytecode needs
to match observable behavior):

1. **Exception-as-effect** — simplest; pure fns stay direct, effectful ops
   raise a typed sentinel exception, handler frames catch-and-resume.
2. **CPS lowering** — full delimited-continuation semantics; heavy.
3. **State-machine** — `async/await`-style rewrite per effectful fn.

Decision deferred; 14b ships first to stress-test the ADT lowering.
