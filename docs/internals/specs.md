# Spec system

Irij has no static type system. Instead it has *specs* — Malli-inspired
runtime predicates that validate values at function boundaries.

## Layers

The spec system has four levels of strictness, picked by the
declaration form:

| Form | What runs |
|---|---|
| `fn f (...)` | Nothing — no validation. |
| `fn f :: A B (...)` | Input + output validated against `A` (the arg) and `B` (the return) at each call. |
| `fn f :: A B  in pre  out post (...)` | Same as above + pre/post contract clauses. |
| `pub fn f :: ...` | Same as above + spec-lint at compile time: pub fn MUST have a spec annotation. |

## Spec expressions

`SpecExpr` (sealed AST node):

| Kind | Example | Validates |
|---|---|---|
| Primitive | `Int`, `Str`, `Bool`, `Float`, `Any` | type tag |
| Composite | `Vec[Int]`, `Map[Str, Int]` | container + element specs |
| Tuple | `#(Int Str)` | fixed-shape tuple |
| Arrow | `Int Str -> Bool` | a function with that signature |
| Enum | `:: ok :: error` | one of the listed values |
| Wildcard | `_` | accept anything |
| Ref | `MyShape` | named spec defined elsewhere |

## Compile-time effect-row lint

`dev.irij.compiler.EffectRowChecker` runs after module inlining and
before either back-end (interp or bytecode emit). It catches three
classes of violation at compile time:

1. **Call-site subsumption:** caller calls a fn whose declared row
   includes effects the caller doesn't declare.
2. **Perform-site availability:** a fn body performs an effect op
   not declared in its row.
3. **JVM capability:** any Java interop site requires `JVM` in the
   surrounding fn's row.

Available-set semantics inside a fn body:

| Declared row | Body's available set |
|---|---|
| `null` (unannotated) | empty (pure) |
| Contains `Any` | ambient (everything OK; polymorphism marker) |
| Otherwise | exactly the listed effects |

`with X body` extends the available set with X's effect for the
body's lexical extent. Handler expressions that are local-bound or
opaque (App, Block, fn returning a handler) — checker treats the
body as AMBIENT; runtime check is the safety net.

`>>`-composed handlers contribute each operand's effect to the set.

Top-level decls run under AMBIENT (not constrained — they ARE the
caller). Handler decls walk under `requiredEffects + ownEffect`.

Errors throw `IrijCompiler.CompileException` with source location.
Both interp and bytecode builds fail at parse-and-lint time before
any runtime.

## Effect-row subsumption at fn-call

The effect row of a fn is a *contract on the caller*, not just a
description of what the body does. At every fn-call site, the
interpreter checks:

  required effects of callee ⊆ available effects of caller

If a fn declares `::: Console`, every caller must itself have
Console in its effect row (or be top-level ambient, or be declared
`::: Any` and inherit). Otherwise the call is rejected with:

```
Effect 'Console' not declared: 'call to f' requires ::: Console
  in enclosing function's effect row
```

Implemented in `Interpreter.checkCalleeRow(calleeRow, calleeName,
loc)`, invoked from each apply path (Lambda / MatchFn /
ImperativeFn) before the row is pushed.

Without subsumption, `:::` would only constrain inside the callee —
a caller could call `::: Console` fns without declaring Console
itself, defeating the row contract.

## Effect-row polymorphism via `Any`

Higher-order fns whose callback may need effects unknown at
definition site declare `::: Any` in their row:

```
pub fn fold :: _ _ _ _ ::: Any
  (f acc v ->
    if (empty? v) acc
    else (fold f (f acc (head v)) (tail v)))
```

When such a fn is *called*, the effect-row pushed onto
`AVAILABLE_EFFECTS` is the UNION of declared effects (minus `Any`)
and the caller's available set. So `fold (_ x -> println x) () v`
called from a fn declared `::: Console` lets the callback's
`println` see Console — even though `fold` itself doesn't mention
Console.

Implemented in `Interpreter.effectiveRow(declared)`:

- declared contains `Any` AND caller was AMBIENT → push AMBIENT.
- declared contains `Any` AND caller has a finite row → push UNION.
- declared doesn't contain `Any` → push declared verbatim (old
  behaviour).

This is what lets `std.list.fold` (Irij-ported) replace the old
Java BuiltinFn fold. The BuiltinFn was effect-transparent by
construction (no row push); `::: Any` gives Irij-side fns the same
transparency explicitly.

## At what time

Specs are *runtime* validators. They run when a `SpecContractFn`-
wrapped function is called:

1. **Input pass**: each declared input spec checked against the
   corresponding arg. Failure throws `IrijRuntimeError("spec failure
   on arg N of f: expected …, got …")` with blame info.
2. **Body runs**.
3. **Output pass**: declared output spec checked against return value.
   Same blame-rich error on failure.

The wrapping happens at fn-definition time — `Interpreter.evalDecl`
for `FnDecl` instantiates a `SpecContractFn(underlying, specs)` if
specs are present.

## Contracts: pre/post (separate from specs)

`in pre out post` clauses are additional Boolean predicates:

```
fn divide :: Int Int Int
  in (b != 0)            ;; pre — runs before body
  out (out >= 0)         ;; post — runs after body, `out` is the return
  (a b -> a / b)
```

Pre clauses fail with "pre-condition violated in f"; post with
"post-condition violated in f". Failure carries the source location
of the clause for blame.

Pre/post are *truthiness* checks — they evaluate to a value and call
`truthy()`. Distinct from specs (which check structure).

## Module-boundary blame

`pub` declarations export with "blame envelope" wrappers. When a
caller imports `mod.foo` and calls `foo`, a spec failure shows:

```
Spec failure on input 1 of mod.foo:
  expected Int, got Str "abc"
  blamed:  caller-side at <file>:<line>
```

The blame label points at the *caller*, not the callee, when input
specs fail. For output failures it points at the callee. This is
classical higher-order contract blame (Findler/Felleisen 2002).

## Law verification

QuickCheck-style. Declared via:

```
law name :: Int Int
  (a b -> a + b == b + a)
```

Runs N=100 random inputs (via `Arbitrary` instances) and checks the
property. Reports the smallest failing input via shrinking. Run via
`irij test`.

## `Arbitrary` instances

Each primitive spec carries an `arbitrary()` method that generates
random samples. Composites compose: `Vec[Int]` generates a random-
length list of random ints. Custom specs need an explicit
`arbitrary` declaration.

Implementation in `dev.irij.spec` (interp-side; bytecode mode doesn't
yet run law-verification).

## Spec-lint

At parse time, `pub fn` without `:: ...` triggers a warning (or error
under `--strict`). The recommendation:

- All `pub fn` declarations MUST have spec annotations.
- Use `_` for positions where the shape is too complex or
  undetermined.
- `--no-spec-lint` is a human emergency escape hatch; don't use as a
  workaround.

(See `CLAUDE.md` for the project policy.)

## Bytecode-mode spec validation

The bytecode emitter validates **input args** against primitive
type names. At each annotated `fn` decl, `emitInputSpecChecks` walks
`fn.specAnnotations()` (last entry = return spec, earlier = inputs)
and emits a `RuntimeSupport.checkType(value, "T", fnName, idx)` call
per non-wildcard input whose spec is a `SpecExpr.Name`.

`RuntimeSupport.checkType` recognises: `Int`, `Float`, `Bool`, `Str`,
`Vec`, `Map`, `Set`, `Tuple`, `Fn`, `Any`, `Unit`. Unknown spec
names pass through (user-defined product/sum specs aren't yet wired
into bytecode).

Output specs and composite specs (`Vec[Int]`, arrow types, etc.)
remain interp-only. Output checks require single-exit refactoring of
the emit pipeline; composite specs require a per-spec runtime
validator (planned).

Effect-row enforcement runs at compile time via
`EffectRowChecker`; see the "Compile-time effect-row lint" section.

## Why runtime specs and not static types

The project bet:

- **Effect rows want to be runtime-checked anyway** (handlers can be
  swapped at runtime). A static type system that doesn't capture
  effects is half-checked; one that does is Haskell/Koka-complex.
- **Java interop** is inherently dynamically-typed at the boundary.
  Forcing a static type system on Java-returning calls makes the
  interop ergonomics terrible.
- **Specs are values** — you can compute, compose, and store specs at
  runtime. Static types can't be manipulated programmatically.
- **Spec failures point at a value**, not at a type expression. Easier
  to debug.

Trade-off: no compile-time type errors. Some bugs surface at runtime
that would be caught at compile time in a typed lang. The project
position is that effect rows + specs + contracts + law verification
catch enough of the same bugs to be worth the trade.
