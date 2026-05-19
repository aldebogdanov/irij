# Phase 8a: Specs — Plan

## Overview

`spec` replaces `type` as the unified structural description mechanism.
Every spec declaration provides: structure, constructors, pattern matching,
runtime validation, certification tags, and (future) Arbitrary generation.

No separate type system. No HM. Specs are the single source of truth
for data shape.

## Core Concepts

### Certified Values

When a value passes through a spec constructor, it receives an internal
certification tag — a lightweight marker saying "validated by spec X."

- **Tagged (certified)** values pass through function boundary checks in O(1)
- **Untagged** values (plain maps, JSON data, etc.) get force-validated at boundaries
- Record update (`{...p age= 99}`) strips the tag (field may violate constraints)
- Sum variant construction always certifies (constructor validates args)

### Three validation tiers

| Tier | When | Cost |
|------|------|------|
| Construction | `Person {name= "A" age= 1}`, `Circle 5.0` | Full validation |
| Boundary | Function arg/return with `::` annotation | Tag check (O(1)) or force-validate if untagged |
| Mutation | `<-` on spec-annotated mutable binding | Validate new value (deferred to 8b) |

## Syntax

### Sum specs (variants) — replaces `type` sum types

```irij
spec Shape
  Circle Float
  Rect Float Float

spec Maybe a
  Just a
  Nothing

spec Result ok err
  Ok ok
  Err err

;; Construction — positional (same as current type constructors)
c := Circle 5.0
r := Rect 3.0 4.0
x := Just 42
y := Nothing
z := Ok "hello"
```

### Product specs (records) — replaces `type` product types

```irij
spec Person
  name :: Str
  age  :: Int

spec Point
  x :: Float
  y :: Float

;; Construction — named fields
p := Person {name= "Alice" age= 30}
pt := Point {x= 1.0 y= 2.0}

;; Field access via dot notation (same as current product types)
println p.name
println pt.x
```

### Constrained specs (Phase 8a stretch goal)

```irij
spec Person
  name :: Str
  age  :: (Int :min 0 :max 150)

;; Construction validates constraints
p := Person {name= "Alice" age= 30}   ;; ok
q := Person {name= "" age= -1}        ;; error: age must be >= 0
```

Constraint syntax: `(BaseSpec :constraint-name value ...)`.
Phase 8a supports: `:min`, `:max` for Int/Float; `:min-len`, `:max-len` for Str.
More constraints in later phases.

### Spec annotations on bindings

```irij
;; Explicit validation of untagged data
raw := {name= "Bob" age= 25}   ;; plain map, no tag
p :: Person := raw              ;; validates raw → certifies as Person

;; Constructor already certifies — annotation is optional
p2 := Person {name= "Jo" age= 20}     ;; certified by constructor
p3 :: Person := Person {name= "Jo" age= 20}  ;; redundant but harmless
```

### Spec annotations on functions

```irij
;; Input and output specs (last = output, same rule as discussed)
fn create-person :: Person Person ::: Db
  => input
  db-insert input

;; At call time:
;; - input is tagged as Person? → O(1) tag check, pass
;; - input is untagged? → force validate against Person, certify or error
;; - return value: constructor certifies, or if plain map, validate against Person
```

### Pattern matching (same as current)

```irij
fn area
  (Circle r) => 3.14159 * r * r
  (Rect w h) => w * h

fn show
  (Just x) => "Just " ++ to-str x
  Nothing  => "Nothing"
```

Pattern matching works identically to current `type` — match on variant name,
bind fields positionally.

## Grammar Changes

### Lexer

```
SCHEMA : 'spec' ;   // new keyword (add to reserved words)
// TYPE keyword remains as alias during transition? NO — full replace
```

Decision: Replace `TYPE` token with `SCHEMA` token. The keyword `type` becomes
available as a potential future use or is fully removed from reserved words.

### Parser

```antlr
// Replace typeDecl with specDecl
specDecl
    : SCHEMA typeName typeParams? NEWLINE INDENT specBody NEWLINE* DEDENT
    ;

specBody
    : specVariant (NEWLINE specVariant)*      // sum spec
    | specField (NEWLINE specField)*          // product spec
    ;

specVariant
    : TYPE_NAME typeExpr*                         // same as typeVariant
    ;

specField
    : IDENT TYPE_ANN typeExpr                     // name :: Type
    ;

// In topLevelDecl: replace typeDecl with specDecl
```

### AST

Rename `Decl.TypeDecl` → `Decl.SpecDecl`. Add constraint support:

```java
record SpecDecl(String name, List<String> typeParams,
                  SpecBody body, SourceLoc loc) implements Decl {}

sealed interface SpecBody {
    record SumSpec(List<Variant> variants) implements SpecBody {}
    record ProductSpec(List<SpecField> fields) implements SpecBody {}
}

// SpecField replaces Field — carries constraint info
record SpecField(String name, String typeName,
                   Map<String, Object> constraints) {}
// constraints: {:min 0, :max 150} or null for unconstrained
```

### Interpreter — certification tags

```java
// Tagged record gains a specName field for certification
public record Tagged(String tag, List<Object> fields,
                     Map<String, Object> namedFields,
                     String specName) {
    // specName: "Person", "Shape", null (uncertified)
}

// SpecDescriptor — registered per spec name
public record SpecDescriptor(String name, List<String> typeParams,
                               SpecBody body,
                               Map<String, SpecFieldInfo> fieldConstraints) {}

// SpecFieldInfo — per-field validation info
public record SpecFieldInfo(String fieldName, String typeName,
                              Map<String, Object> constraints) {}
```

### Interpreter — validation

```java
// validate(value, specName) → validated value or throw
// - Sum: check tag matches a variant, check field types
// - Product: check all required fields present, check constraints
// - If already certified with matching spec → return as-is (O(1))

// In apply(): if fn has spec annotations, validate args/return
// In eval(Let with spec annotation): validate RHS
```

## Implementation Steps

### Step 1: Keyword and grammar swap

- Add `SCHEMA` token to lexer (replace or alongside `TYPE`)
- Update parser: `specDecl` rule (same structure as `typeDecl`)
- Update `topLevelDecl` to use `specDecl`
- Update `AstBuilder` to produce `SpecDecl` from parse tree

### Step 2: AST and interpreter rename

- Rename `Decl.TypeDecl` → `Decl.SpecDecl`
- Rename `TypeBody` → `SpecBody`, `SumType` → `SumSpec`, etc.
- Update `registerTypeConstructors` → `registerSpecConstructors`
- All existing behavior preserved — just renamed

### Step 3: Certification tags

- Add `specName` field to `Tagged` record
- `Constructor.apply()` sets `specName` on created `Tagged` values
- Product constructor: `Person {name= "A" age= 1}` → Tagged with specName="Person"
- Sum constructor: `Circle 5.0` → Tagged with specName="Shape"

### Step 4: Spec registry

- `SpecDescriptor` stored in interpreter (like EffectDescriptor, ProtocolDescriptor)
- Maps spec name → field info, variants, constraints
- Used for validation lookups

### Step 5: Binding-level validation

- `p :: Person := raw` — when a binding has a `::` annotation AND the RHS
  value is not certified for that spec, validate and certify
- Implementation: in eval(Stmt.Bind), check if type annotation present,
  call validateAgainstSpec(value, specName)

### Step 6: Function boundary validation

- `fn f :: Person Str ::: Console` — validate first arg as Person at call time
- If arg is certified → O(1) pass
- If arg is not certified → force validate
- Return value: if annotated, validate on exit
- Implementation: extend `apply()` — after existing effect row push, check
  spec annotations on params

### Step 7: Constraint validation (stretch)

- Parse constraint syntax: `(Int :min 0 :max 150)` in spec field types
- Store constraints in SpecFieldInfo
- During validation, check constraints:
  - `:min` / `:max` for numbers
  - `:min-len` / `:max-len` for strings
- Construction rejects values violating constraints

### Step 8: Update all existing code

- Replace all `type` → `spec` in: tests, examples, stdlib, docs, spec
- Update Java tests referencing TypeDecl
- Verify all 529+ tests still pass
- Add new tests for:
  - Spec construction and certification
  - Tag check at function boundaries
  - Force validation of untagged values
  - Constraint validation (if implemented)
  - Record update strips certification

## Test Plan

### Java unit tests (InterpreterTest.java)

- `specSumVariantConstruction` — basic variant creation
- `specProductConstruction` — named field construction
- `specCertificationTag` — constructor sets specName
- `certifiedValuePassesBoundaryCheck` — O(1) pass-through
- `uncertifiedValueForceValidatedAtBoundary` — plain map validated
- `invalidValueRejectedAtBoundary` — missing field → error
- `recordUpdateStripsCertification` — {...p field= val} loses tag
- `constraintValidationAtConstruction` — :min/:max checked
- `constraintViolationAtConstruction` — error on bad value
- `patternMatchingOnSpecVariants` — same as current type matching
- `parametricSpecConstruction` — Maybe a, Result ok err

### Integration tests (tests/test-specs.irj)

- Sum spec construction and matching
- Product spec construction and field access
- Spec annotation on binding validates untagged data
- Spec annotation on function validates args
- Constraint validation
- Certified value passes through without revalidation
- Error messages for validation failures

## Not in Phase 8a (deferred)

- Inline constraint expressions in `::` annotations
- Mutable binding spec validation (`counter :! 0 :: Int`)
- Spec-powered Arbitrary generation (Phase 8b)
- Heavy validation warnings (future)
- Nested/composed spec constraints (Phase 8b)
- `validate` / `validate!` builtins (Phase 8b — 8a uses constructor + annotation)

## Files to Change

### Grammar
- `IrijLexer.g4` — add SCHEMA token
- `IrijParser.g4` — specDecl replaces typeDecl

### AST
- `Decl.java` — SpecDecl, SpecBody, SpecField, Variant (keep)

### Interpreter
- `Values.java` — Tagged gains specName; SpecDescriptor record
- `Interpreter.java` — registerSpecConstructors, validateAgainstSpec,
  spec annotation handling in apply() and eval(Bind)
- `AstBuilder.java` — visitSpecDecl

### All .irj files
- Replace `type` → `spec` in tests, examples, stdlib

### Docs
- `irij-lang-spec.org` — replace type with spec throughout
- `TODO.md` — update phase 8a items
- `phase-8a-specs.md` — implementation doc (after completion)
