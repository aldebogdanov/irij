# Phase 1 — Design Decisions

These decisions were settled before starting Phase 1 implementation.
They apply to the L0 (fully dynamic) interpreter and inform the grammar.

---

## Numeric System

### Widening

Implicit Int→Float widening. At L0, arithmetic just works:

```
1 + 2       ;; → 3 (Int)
1 + 1.5     ;; → 2.5 (Float)
1.5 + 1     ;; → 2.5 (Float)
2/3 + 1/3   ;; → 1/1 (Rational)
```

Rule: if either operand is Float, result is Float. If both are Rational, result is Rational. Int + Rational → Rational.

### Division

`/` always returns Float (or Rational if both operands are Rational).
`div` and `mod` are builtins for integer division.

```
7 / 2       ;; → 3.5 (Float)
div 7 2     ;; → 3 (Int)
mod 7 2     ;; → 1 (Int)
2/3 / 1/3   ;; → 2/1 (Rational)
```

### Rationale

L0 is dynamic. No type checker exists to reject `1 + 1.5`. Python-style
widening is the least-surprise behavior for LLMs trained overwhelmingly
on Python. The protocol system (Phase 6) can add type-level constraints
later without changing runtime semantics.

---

## Booleans

`true` and `false` are **builtin identifiers**, not lexer keywords.
They resolve to boolean values at runtime. Pattern matching works on
them as values.

```
x := true
if x
  print "yes"
else
  print "no"
```

### Rationale

Keeps the lexer small. At L0, `true`/`false` are just names that happen
to be pre-bound. Consistent with keywords like `:ok` being atoms — booleans
are just another value type.

---

## Sequence Operator Dispatch (`/+`, `@`, `/?`, etc.)

At L0: **duck-typed**. Seq ops work on anything iterable (vectors, sets,
ranges). The operator determines which function to reduce with:

- `/+` reduces with `+`
- `/*` reduces with `*`
- `/#` counts elements
- `/&` reduces with `&&` (all?)
- `/|` reduces with `||` (any?)
- `/?` filters by predicate
- `/!` finds first matching
- `@` maps a function over elements
- `@i` maps with index
- `\.` scan (prefix reductions)

```
#[1 2 3] |> /+          ;; → 6
#[1 2 3] |> @ (* 2)     ;; → #[2 4 6]
#[1 2 3] |> /? (> 1)    ;; → #[2 3]
```

At Phase 6 (protocols), these will dispatch through `Foldable`/`Mappable`
protocols, enabling custom types to support seq ops via `impl`.

---

## Ranges and Lazy Iteration

### Strict evaluation everywhere, lazy ranges

The language is **strictly evaluated**. All expressions evaluate eagerly.
However, ranges and pipeline chains use **lazy iterators** internally:

```
;; range produces a lazy iterator, not a materialized vector
1 .. 1000000

;; pipeline operations chain lazily
1 .. 1000000
  |> @ (* 2)
  |> /? (x -> x % 3 == 0)
  |> take 10              ;; only 10 elements ever computed

;; force into a vector
1 .. 10 |> to-vec          ;; → #[1 2 3 4 5 6 7 8 9 10]

;; inside a vector literal, range is forced
#[1 .. 5]                  ;; → #[1 2 3 4 5]
```

### Rules

- `..` and `..<` produce lazy range objects
- `@`, `/?`, `/!` on a lazy object return a new lazy object (chaining)
- `/+`, `/#`, `/&`, `/|`, `take`, `to-vec` are consumers (force evaluation)
- Vectors, sets, maps are always strict (materialized in memory)
- No infinite ranges in Phase 1 — both endpoints required

### Infinite ranges (deferred to Phase 5)

Infinite iteration requires the Stream effect from the spec (§4.3).
Attempting `1 ..` without an upper bound will be a runtime error in
Phase 1. This is safe to defer because:

1. The spec models infinite sequences as `Stream` effects, not lazy lists
2. `Stream` is built on the effect system (Phase 3) + concurrency (Phase 5)
3. No grammar changes needed — `..` already parses with optional right operand
4. The runtime behavior change (allowing missing upper bound) is additive

---

## Error Handling

At L0: **runtime exceptions** with source locations.

```
1 + "hello"    ;; → RuntimeError: Cannot add Int and Str at line 1:3
div 1 0        ;; → RuntimeError: Division by zero at line 1:1
```

Pattern match failure, wrong argument count, type mismatches — all produce
clear runtime errors with file:line:col.

The effect system (Phase 3) will add `with handler` for structured recovery.

---

## Closures and Mutability

- **Lexical scope** — standard, no dynamic scoping
- **Closures capture mutables by reference** — mutations visible through closure
- **Strict (eager) evaluation** — required by effects, mutable state, imperative blocks

```
x :! 0
inc := (-> x <- x + 1)
inc ()
print x    ;; → 1 (mutation visible)
```

### Rationale

Capture-by-reference matches Python/JS behavior that LLMs expect.
Strict evaluation is non-negotiable — the spec has imperative blocks,
mutable state, and effects, all of which require eager evaluation.
Haskell's laziness is its biggest footgun; LLMs regularly get it wrong.

---

## Value Representation (Runtime)

| Value | Java Type | Notes |
|-------|-----------|-------|
| Int | `long` | 64-bit, no BigInt initially |
| Float | `double` | IEEE 754 |
| Rational | `long num, long den` | Exact, auto-simplified |
| Str | `String` | Immutable, interpolation at parse time |
| Bool | `boolean` | `true`/`false` builtins |
| Keyword | Interned `String` | `:ok`, `:error`, etc. |
| Unit | Singleton | `()` |
| Vector | Persistent list | `#[1 2 3]` |
| Map | Persistent map | `{name= "Jo"}` |
| Set | Persistent set | `#{1 2 3}` |
| Tuple | Fixed-size `Object[]` | `#(1 "a")` |
| Tagged | `record Tagged(String, List<Object>)` | ADT constructors |
| Lambda | Closure object | Env + params + body |
| Range | Lazy iterator | `1 .. 10` |

---

## Summary Table

| Decision | Choice | Phase it matters |
|----------|--------|-----------------|
| Numeric widening | Implicit Int→Float | Phase 1 |
| Integer division | `/` → Float, `div`/`mod` for Int | Phase 1 |
| `true`/`false` | Builtin IDENTs | Phase 1 |
| Seq op dispatch | Duck-typed at L0 | Phase 1 (evolves Phase 6) |
| Ranges | Lazy iterators, both endpoints required | Phase 1 |
| Infinite sequences | Deferred to Phase 5 (Stream effect) | Phase 5 |
| Error handling | Runtime exceptions + source location | Phase 1 (evolves Phase 3) |
| Closures | Capture mutables by reference | Phase 1 |
| Evaluation | Strict (eager) | Phase 1, permanent |
| Rationals | First-class value type | Phase 1 |
