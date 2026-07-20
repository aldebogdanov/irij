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
| Enum | `(Enum :ok :error)` | a keyword whose name is one of the listed values |
| Wildcard | `_` | accept anything |
| Ref | `MyShape` | named spec defined elsewhere |

Enum specs validate a `Keyword` value. The syntax inside the parens
is the `Enum` head followed by one or more keyword literals:

```
fn classify :: Str (Enum :ok :error)
  (s -> if (s == "ok") :ok else :error)
```

Each Enum value must be a keyword literal (`:foo`). Bare identifiers
in that position are rejected so the source can't drift from the
spec.org documented form. The AST builder strips the leading `:` so
`SpecExpr.Enum(["ok", "error"])` stores the bare names — aligned
with `Keyword.name()` at validate time. Passing a non-keyword or a
keyword outside the listed names raises:
`Spec validation failed: expected Keyword (one of [ok, error]), got …`.

## Compile-time effect-row lint

`dev.irij.compiler.EffectRowChecker` runs after module inlining and
before bytecode emit. It catches three classes of violation at
compile time:

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
| Contains a row variable (lowercase-first token, e.g. `eff`) | ambient (everything OK; precision lives at call sites) |
| Contains `Any` (stdlib-only — rejected in user code) | ambient |
| Otherwise | exactly the listed effects |

`with X body` extends the available set with X's effect for the
body's lexical extent. Handler expressions that are local-bound or
opaque (App, Block, fn returning a handler) — checker treats the
body as AMBIENT; runtime check is the safety net.

`>>`-composed handlers contribute each operand's effect to the set.

Top-level decls run under AMBIENT (not constrained — they ARE the
caller). Handler decls walk under `requiredEffects + ownEffect`.

Errors throw `IrijCompiler.CompileException` with source location.
Builds fail at parse-and-lint time before any runtime.

## Effect-row subsumption at fn-call

The effect row of a fn is a *contract on the caller*, not just a
description of what the body does. At every fn-call site, the
effect-row checker checks:

  required effects of callee ⊆ available effects of caller

If a fn declares `::: Console`, every caller must itself have
Console in its effect row (or be top-level ambient, or carry a row
variable and inherit). Otherwise the call is rejected with:

```
Effect 'Console' not declared: 'call to f' requires ::: Console
  in enclosing function's effect row
```

Checked statically by `EffectRowChecker` (callee row ⊆ available
set at every call site), with a runtime backstop: the emitter calls
`RtEffects.enterFn(declaredRow)` on fn entry and
`RtEffects.checkPerformEffect` at every perform site, which peeks
the top frame of the thread's `EFFECT_ROW` stack.

Without subsumption, `:::` would only constrain inside the callee —
a caller could call `::: Console` fns without declaring Console
itself, defeating the row contract.

## Effect-row polymorphism: row variables (`Any` is stdlib-only)

Higher-order fns whose callback may need effects unknown at the
definition site use **parametric row variables**: any lowercase-first
token in a row position is a row variable, conventionally `eff`.

```
pub fn fold :: (Fn):eff _ _ _ ::: eff
pub fn map  :: (Fn):eff _ _ ::: eff
pub fn router :: #[(Route):eff] Fn ::: eff     ;; binds through Vec elements
```

Two halves of the mechanism:

**Static** (`EffectRowChecker`). Inside a fn whose row contains a
row variable, the body's available set is AMBIENT (`available()`;
`isRowVar` = lowercase first char) — precision is *not* checked
inside. Instead, at each call site of such a fn the checker binds
the variable to the actual argument's row (`hasRowVar` path;
`collectNestedRowVarBindings` walks `(Fn):eff` through Vec/Set/
Tuple/Map/record specs) and requires the bound row of the *caller*.
So `fold (x acc -> println x) 0 v` demands `Console` at the fold
call site.

**Runtime** (`RtEffects`). The emitter compiles row-var fns with
`enterFnAmbient()` — the body pushes an ambient frame and inherits
the caller's effects. The runtime check inside such fns is
permissive by design; enforcement precision is the static half.

A **free row variable** — `::: eff` with no `(Fn):eff` parameter to
bind it — is legal and is the idiom for effect-transparent dispatch
of *stored* lambdas whose rows are statically unknowable (callback
registries, e.g. a reactive library's watcher dispatch). Statically
nothing binds, so the call site is unconstrained; the stored
lambda's performs are then governed by the ambient frame of
whatever row the triggering call chain declared.

**`::: Any` is banned in user code** (Phase 5 of
`EffectRowChecker.check`): a row containing `Any` in any module not
under `std.` fails compilation with

```
`::: Any` is no longer allowed in user code (use a parametric row
variable like `:eff` / `::: eff` instead)
```

The checker exempts `std.*` modules as transitional headroom, but
current stdlib source has fully migrated to row variables — no
`Any` rows remain. Migration for user code is mechanical:
`::: Any` → `::: eff`.

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

## Law verification — REMOVED (v0.6.12)

Earlier versions shipped `law NAME = forall x. P x` syntax plus a
`verify-laws` builtin running QuickCheck-style sampling against a
shared `Arbitrary` registry. That has been removed in v0.6.12. The
property-testing job belongs in a library, not the language proper
(Haskell QuickCheck, Rust proptest, Scala ScalaCheck all sit outside
their host language). Future work: ship `std.quickcheck` as a regular
Irij module — `check "associativity" (a b c -> f (f a b) c == f a (f
b c)) 100` — same machinery, honest framing (random samples, not
proof).

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

The bytecode emitter validates **inputs and outputs** at full
SpecExpr coverage via `SpecValidator`. At each annotated `fn` decl,
`emitInputSpecChecks` walks `fn.specAnnotations()` (last entry =
return spec, earlier = inputs) and for every non-wildcard /
non-type-var spec emits:

```
ALOAD param_i;
LDC <encoded-spec>;
LDC <fnName>;
ICONST i;
INVOKESTATIC SpecValidator.validateEncoded;
ASTORE param_i;
```

The output spec is captured into `currentOutputSpec` on entry to
`emitFn` and consumed by `emitTailReturn`, which prepends the same
`validateEncoded` call (with `argIdx = -1`) before every ARETURN at
fn-body tail positions. Lambda bodies, SM continuations, handler-
build methods and the like emit raw ARETURN — they don't inherit
the outer fn's output spec.

`SpecValidator` covers every `SpecExpr` variant the language has:
primitive Names (`Int Float Bool Str Keyword Rational Vec Map Set
Tuple Fn Any Unit`), `App` (`Vec Set Map Tuple Fn` with parametric
args), `Arrow` (callable check), `Enum` (keyword membership),
`VecSpec` / `SetSpec` / `TupleSpec` (element-wise recursion),
`Wildcard` / `Var` / `Unit`. User-declared product/sum specs go
through `SpecValidator.REGISTRY` (populated by `<clinit>` on each
emitted class).

Encoding (`SpecValidator.encode`):

```
Wildcard   →  _
Var x      →  ?x
Unit       →  ()
Name N     →  N
App H[..]  →  H[a,b,...]
Arrow      →  (a,b->c)
Enum       →  :a|:b|:c
VecSpec    →  Vec[e]
SetSpec    →  Set[e]
TupleSpec  →  Tuple[a,b]
```

`SpecValidator.decode` parses + caches per encoded string in a
`ConcurrentHashMap`, so the hot path after first call is one map
lookup + recursive record-walk.

Blame: input errors read `Spec failure on input N of fn: …`; output
errors read `Spec failure on output of fn: …`.

Pre/post contracts (`pre/post/in/out`) and user-declared
product/sum specs are also enforced in bytecode mode.

### Primitives as sum-spec variants (union types)

A sum spec may mix named constructor variants with primitive spec
names (`Str`, `Int`, `Float`, `Bool`, `Keyword`, `Rational`, `Unit`):

```
spec Node
  Raw Str       ;; constructor variant
  El Str        ;; constructor variant
  Str           ;; primitive — a bare string is a valid Node
```

`SpecValidator.validateSumShape` first tries the Tagged path; a
non-Tagged value then matches any listed primitive variant **by
type**. Primitive values carry no certification tag and pass through
unchanged. The emitter keeps primitive names out of `tagToSpec` so
`Str` stays a spec name and never becomes a nullary constructor.
Motivation: modeling `Node = element | raw | text` honestly (vrata's
HTML children) instead of loosening to `#[_]`.

**Pre/post emission** (`emitPreContractChecks`,
`installPostSlots`, `emitPostChecks`):

- For each `pre` / `in` lambda, emit it once at fn entry (before
  the TCO label so self-tail recursion doesn't re-check), apply to
  the param slots, truthy-check the result, throw an
  `IrijRuntimeError` with the matching blame text on failure
  (`"Pre-condition violated in 'f' (caller's fault)"` or
  `"Input contract violated in 'f' (caller's fault)"`).
- For each `post` / `out` lambda, compile once into a local slot.
  At every tail-return site (via `emitTailReturn`) the result is
  stashed in a temp slot, each post lambda is applied to
  `[result]`, and a falsy result throws with implementation-blame
  text (`Post-condition violated…` / `Output contract violated…`).
  Post checks run before output-spec validation; both run before
  ARETURN.

The blame strings are stable so `tests/test-contracts.irj` assertions
keep passing across releases.

**User-declared product/sum specs** (`SpecValidator.REGISTRY`):

Populated by a generated `<clinit>` on every emitted class. For
each `Decl.SpecDecl` the emitter records the variant arities (sum)
or field names (product) and emits `clinit` calls:

```
SpecValidator.registerProduct("Point", new String[]{"x","y"});
SpecValidator.registerSum("Shape",
        new Object[]{"Circle", 1, "Rect", 2});
```

`SpecValidator.validateNamed` falls through unknown names into
`validateUserDeclared`, which:

1. Fast-paths Tagged values whose `specName` already matches (set
   by `emitConstructorApp` — the bytecode emitter passes the
   parent spec name through to `Values.Tagged`).
2. Otherwise dispatches by descriptor:
   - **Product**: requires `Tagged` with all required named fields,
     or `IrijMap` with those keys (auto-certifies into Tagged).
     Re-certifies the result with the matching `specName`.
   - **Sum**: requires `Tagged` whose tag is a known variant of the
     spec, and whose positional-field count matches the declared
     arity. Re-certifies the result.

This mirrors `Interpreter.validateProduct` /
`Interpreter.validateSum` (including the O(1) tag-match
short-circuit) so both runtimes accept/reject the same values.

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
position is that effect rows + specs + contracts catch enough of the
same bugs to be worth the trade.
