# Phase 8b: Primitive, Composite & Runtime Specs

Malli-style runtime spec validation. No Hindley-Milner type inference — validates concrete shapes at runtime.

## Design Principles

1. **Specs validate data shapes, not parametric relationships.** `(a -> a)` is documentation, not enforced.
2. **Collection specs mirror collection literals.** `#[Int]` is the spec for `#[1 2 3]`.
3. **Unknown spec names error.** If you write `:: Foo` and `Foo` isn't defined, it's an error.
4. **Arrow specs wrap with contracts.** `(Int -> Str)` wraps the function argument in a validating proxy.

## Spec Syntax

### Primitive specs

```irj
x := 42 :: Int
y := "hello" :: Str
z := 3.14 :: Float
b := true :: Bool
k := :ok :: Keyword
u := () :: Unit
```

### Collection specs (literal style)

```irj
fn process :: #[Int] #[Str]        ;; vector of Int -> vector of Str
fn pairs :: #(Int Str) #(Int Str)  ;; tuple(Int, Str)
fn ids :: #{Int} #{Int}            ;; set of Int
fn lookup :: (Map Str Int) Str     ;; map(Str->Int)
```

Application style also works:

```irj
fn process :: (Vec Int) (Vec Str)
fn pairs :: (Tuple Int Str) (Tuple Int Str)
fn ids :: (Set Int) (Set Int)
```

**Rule:** In fn annotations, composite specs must be parenthesized or use literal syntax. `Vec Int Int` means three separate specs (Vec, Int, Int), not "Vec of Int returning Int".

### Fn spec

```irj
fn apply :: Fn Int Int             ;; any callable
fn apply2 :: (Fn 2) Int            ;; callable with arity 2
```

### Enum spec

```irj
fn check-role :: (Enum admin user guest) Str
  :admin => "full access"
  :user => "limited"
  :guest => "read only"
```

### Arrow spec (concrete function wrapping)

```irj
fn my-map :: (Int -> Str) #[Int] #[Str]
  (f xs -> xs |> @ f)
```

When a function argument has a concrete arrow spec, the passed function is wrapped in a `SpecContractFn` that validates every call:
- Each argument is validated against the arrow's input specs
- The return value is validated against the output spec
- Non-concrete arrows (with type variables like `a -> b`) are documentation only

### Wildcard

```irj
fn identity :: _ _                 ;; no validation at all
fn get-name :: Person _            ;; validate input only
```

## validate / validate!

```irj
;; validate returns Ok/Err
result := validate "Int" 42        ;; => Ok 42
result := validate "Int" "hello"   ;; => Err "Spec validation failed: ..."

;; validate! returns or throws
x := validate! "Int" 42            ;; => 42
x := validate! "Int" "hello"       ;; throws IrijRuntimeError
```

Works with user-declared specs too:

```irj
spec Person
  name :: Str
  age :: Int

validate "Person" (Person "Jo" 30)  ;; => Ok (Person ...)
validate "Person" {name= "Jo"}      ;; => Err "... requires field 'age'"
```

## Spec-aware Arbitrary Generation

`verify-laws` now generates valid random instances of user-declared specs:

```irj
spec Color
  Red
  Green
  Blue

fn display-color
  Red => "red"
  Green => "green"
  Blue => "blue"

proto Displayable a
  display :: a -> Str
  law non-empty = forall x. length (display x) >= 0

impl Displayable for Color
  display := display-color

verify-laws Displayable
;; PASS Displayable/Color: non-empty (100 trials)
```

Before Phase 8b, `verify-laws` would generate random Ints/Strs for Color, causing match failures. Now it generates `Red`, `Green`, or `Blue`.

## SpecExpr AST

New file: `src/main/java/dev/irij/ast/SpecExpr.java`

```
sealed interface SpecExpr
  Name(name)           -- Int, Person, Str
  Wildcard()           -- _
  Var(name)            -- a, b (type variables, doc only)
  Unit()               -- ()
  App(head, args)      -- (Vec Int), (Map Str Int), (Fn 2)
  Arrow(inputs, output) -- (Int -> Str)
  Enum(values)         -- (Enum admin user guest)
  VecSpec(elemSpec)    -- #[Int]
  SetSpec(elemSpec)    -- #{Str}
  TupleSpec(elemSpecs) -- #(Int Str)
```

## Key Files Changed

- `src/main/java/dev/irij/ast/SpecExpr.java` (NEW)
- `src/main/java/dev/irij/ast/Decl.java` — `FnDecl.specAnnotations` now `List<SpecExpr>`
- `src/main/java/dev/irij/ast/Stmt.java` — `Bind.specAnnotation` now `SpecExpr`
- `src/main/java/dev/irij/ast/AstBuilder.java` — rich spec parsing
- `src/main/java/dev/irij/interpreter/Values.java` — `Lambda.specAnnotations` now `List<SpecExpr>`, `SpecContractFn` in typeName
- `src/main/java/dev/irij/interpreter/Interpreter.java` — `validateAgainstSpecExpr()`, `SpecContractFn`, `validateByName()`, `generateRandomForSpec()`, `validate`/`validate!` builtins
- `tests/test-specs-8b.irj` (NEW) — 35 integration tests

## Test Counts

- 604 Java unit tests (was 564, +40)
- 253 integration tests in 16 files (was 218 in 15 files, +35)
