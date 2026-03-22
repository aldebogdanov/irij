# Phase 6b/6c: Module-Boundary Contracts & Law Verification

## Overview

Phase 6b adds `in`/`out` module-boundary contracts with blame tracking alongside existing `pre`/`post` contracts. Phase 6c adds QuickCheck-style law verification for protocols and functions.

Together these phases close the loop on Irij's contract and correctness story: `pre`/`post` express general preconditions and postconditions, `in`/`out` assign blame to caller vs implementor at module boundaries, and `law` declarations let you property-test algebraic invariants.

---

## Phase 6b: Module-Boundary Contracts

### Syntax

`in` and `out` are contract clauses that appear in fn bodies alongside `pre` and `post`:

```
fn checked-divide
  x y =>
    pre (y != 0)
    in  (is-int x) && (is-int y)
    result := x / y
    post (result * y == x)
    out  (is-int result)
    result
```

### Semantics

- **`in`** — validates inputs. Failure is the **caller's fault**. The blame message names the call site (and the caller's module when applicable).
- **`out`** — validates outputs. Failure is the **implementation's fault**. The blame message names the defining module.

When a function is defined inside a module, blame messages include the module name:

```
Contract violation (in): caller of math.checked-divide — (is-int x) && (is-int y)
Contract violation (out): math.checked-divide — (is-int result)
```

### Evaluation Order

All four contract types can coexist. They are checked in this order:

1. `pre` — general precondition (before anything else)
2. `in` — input/caller-blame contract
3. *execute the function body*
4. `post` — general postcondition
5. `out` — output/implementor-blame contract

### Works with All fn Body Forms

Lambda form:

```
fn abs
  x -> if (x < 0) (0 - x) x
  in (is-number x)
  out (result >= 0)
```

Imperative form:

```
fn clamp
  lo hi x =>
    in (lo <= hi)
    result := if (x < lo) lo (if (x > hi) hi x)
    out (result >= lo) && (result <= hi)
    result
```

Match-arms form:

```
fn safe-head
  in (length xs > 0)
  [x ...rest] -> x
```

### Grammar Changes

The `contractClause` rule is extended with two new alternatives:

```
contractClause
  : PRE expr NEWLINE
  | POST expr NEWLINE
  | IN expr NEWLINE
  | OUT expr NEWLINE
  ;
```

### Design Note

The original spec described a `contract` block with nested INDENT/DEDENT grouping all contract clauses. This was simplified to flat syntax consistent with `pre`/`post` because nested INDENT/DEDENT inside fn body creates parser conflicts with the existing block structure. The flat approach is simpler, composes cleanly, and avoids ambiguity.

---

## Phase 6c: Law Verification

### Protocol Laws

Laws are declared inside `proto` declarations using `forall` quantification:

```
proto Monoid
  mempty : a
  mappend : a -> a -> a

  law left-identity  = forall x. mappend mempty x == x
  law right-identity = forall x. mappend x mempty == x
  law associativity  = forall x y z. mappend (mappend x y) z == mappend x (mappend y z)
```

Laws are parsed into `ProtoLaw(name, forallVars, body)` AST nodes. They are not verified at declaration time — verification is explicitly triggered.

### Fn-Level Laws

Functions can also declare laws in their body:

```
fn sort
  xs =>
    law idempotent = forall xs. sort (sort xs) == sort xs
    ;; ... implementation
```

### Verification: `verify-laws`

```
verify-laws Monoid        ;; verify all laws for proto Monoid (100 trials each)
verify-laws Monoid 50     ;; 50 trials per law
verify-laws "sort"        ;; verify fn-level laws for sort
```

### Random Value Generation

The verifier generates random test values for each `forall` variable:

| Type    | Range / Strategy         |
|---------|--------------------------|
| Int     | -100 to 100             |
| Float   | Random doubles           |
| Bool    | true / false             |
| Str     | Random alphanumeric      |
| Vec Int | Vectors of 0-10 integers |

A deterministic random seed (42) is used for reproducible results in tests.

### Protocol Law Verification

For each registered `impl` of a protocol, the verifier:

1. Pre-binds the protocol methods to the concrete implementations for that type.
2. Generates random values of that type.
3. Evaluates each law body with the generated values.
4. Reports PASS/FAIL per law per type.

### Fn-Level Law Verification

1. Generates random values for the `forall` variables.
2. Evaluates the law body.
3. Type errors on invalid inputs are **skipped** (standard QuickCheck filtering — not all random values are valid inputs).

### Return Value

`verify-laws` returns a vector of tagged results:

```
#[Pass "left-identity" | Fail "associativity" "counterexample: x=3 y=5 z=7"]
```

It also prints human-readable lines to stdout:

```
PASS left-identity (Int, 100 trials)
PASS right-identity (Int, 100 trials)
FAIL associativity (Str, trial 23): counterexample x="ab" y="cd" z="ef"
SKIP idempotent (Bool, 12 skipped of 100)
```

---

## Examples

### Protocol with Laws

```
proto Monoid
  mempty : a
  mappend : a -> a -> a

  law left-identity  = forall x. mappend mempty x == x
  law right-identity = forall x. mappend x mempty == x
  law associativity  = forall x y z. mappend (mappend x y) z == mappend x (mappend y z)

impl Monoid for Int
  mempty = 0
  mappend = (+)

impl Monoid for Str
  mempty = ""
  mappend = concat

verify-laws Monoid
;; PASS left-identity (Int, 100 trials)
;; PASS right-identity (Int, 100 trials)
;; PASS associativity (Int, 100 trials)
;; PASS left-identity (Str, 100 trials)
;; PASS right-identity (Str, 100 trials)
;; PASS associativity (Str, 100 trials)
```

### Fn-Level Law (Idempotent Sort)

```
fn sort
  xs =>
    law idempotent = forall xs. sort (sort xs) == sort xs
    ;; ... sorting implementation
    sorted

verify-laws "sort"
;; PASS idempotent (Vec Int, 100 trials)
```

### Failing Law Detection

```
proto BadMonoid
  mempty : a
  mappend : a -> a -> a

  law commutativity = forall x y. mappend x y == mappend y x

impl BadMonoid for Str
  mempty = ""
  mappend = concat

verify-laws BadMonoid
;; FAIL commutativity (Str, trial 1): counterexample x="a" y="b"
```

### In/Out Contracts with Blame

```
mod math
  pub fn checked-divide
    x y =>
      in  (is-int x) && (is-int y)
      in  (y != 0)
      out (is-int result)
      x / y

use math

checked-divide 10 3    ;; => 3
checked-divide 10 0    ;; Contract violation (in): caller of math.checked-divide — (y != 0)
checked-divide 10 2.5  ;; Contract violation (in): caller of math.checked-divide — (is-int x) && (is-int y)
```

---

## Implementation Details

### Grammar

`contractClause` in `IrijParser.g4` extended:

```
contractClause
  : PRE expr NEWLINE
  | POST expr NEWLINE
  | IN expr NEWLINE
  | OUT expr NEWLINE
  ;
```

`IN` and `OUT` added as keyword tokens in `IrijLexer.g4`.

### AST

- `FnDecl` extended with `List<Expr> inContracts`, `List<Expr> outContracts`, `List<FnLaw> fnLaws` fields.
- `ProtoLaw` extended with `List<String> forallVars` field (was body-only before).
- New record: `FnLaw(String name, List<String> forallVars, Expr body)`.

### Interpreter — ContractedFn

`ContractedFn` (the runtime wrapper for functions with contracts) extended with:

- `List<Expr> ins` — in-contract expressions
- `List<Expr> outs` — out-contract expressions
- `String moduleName` — nullable; set when fn is defined inside a module

On invocation, `ContractedFn.apply()` checks pre, then in, then delegates to the wrapped fn, then checks post, then out. Blame messages incorporate `moduleName` when present.

### Interpreter — Law Verification

- `ProtocolDescriptor` extended with `List<ProtoLaw> laws` field.
- `verifyProtoLaws(String protoName, int trials)` — iterates over registered impls, generates values, evaluates laws.
- `verifyFnLaws(String fnName, int trials)` — looks up fn's `FnLaw` list, generates values, evaluates.
- Both methods use `Random(42)` for deterministic reproducibility.
- Results returned as `IrijVector` of `Tagged("Pass", ...)` or `Tagged("Fail", ...)` values.

---

## Test Summary

471 total tests:

- 444 prior (87 parser + 296 interpreter + 14 bencode + 18 nREPL session + 1 nREPL server + 7 VarRedefinition + 21 Phase 6a contracts)
- 10 new interpreter tests for `in`/`out` contracts (blame messages, evaluation order, module attribution)
- 9 new interpreter tests for law verification (proto laws pass/fail, fn laws, skip on type error, deterministic seed)
- 8 new parser tests for `in`/`out`/`law` syntax
