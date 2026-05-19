# Phase 4.5b — Protocols & Implementations

## Overview

Irij protocols provide ad-hoc polymorphism (like Haskell type classes or Clojure protocols). A `proto` declaration defines a set of method signatures. An `impl` declaration provides concrete implementations for a specific type. Method dispatch is dynamic, based on the runtime type of the first argument.

## Protocol Declarations

```irj
proto Show a
  show :: a -> Str

proto Monoid a
  empty :: a
  append :: a -> a -> a
  law identity = forall x. append empty x == x
```

A `proto` declaration:
1. Registers a `ProtocolDescriptor` in the interpreter's protocol registry
2. Installs a **dispatch function** for each method as a global binding
3. Laws are parsed but not verified at runtime (deferred to Phase 6)

## Implementation Declarations

```irj
impl Show for Int
  show := (n -> to-str n)

impl Show for Str
  show := (s -> s)

impl Monoid for Int
  empty := 0
  append := (+)
```

An `impl` declaration:
1. Evaluates each binding expression in the current environment
2. Registers the bindings in the protocol's type dispatch table
3. Errors if the protocol doesn't exist

## Dispatch Mechanism

Protocol methods use **first-argument type dispatch** (Clojure-style):

```irj
show 42          ;; Values.typeName(42) = "Int" → finds impl Show for Int
show "hello"     ;; Values.typeName("hello") = "Str" → finds impl Show for Str
```

The dispatch function:
1. Takes the first argument
2. Computes `Values.typeName(firstArg)` to get the runtime type name
3. Looks up the matching impl in the protocol's dispatch table
4. If the impl binding is callable (lambda, fn, operator section) → applies it to all args
5. If the impl binding is a plain value (e.g., `empty := 0`) → returns it directly

### Type Name Mapping

| Value | `typeName()` | Impl target |
|-------|-------------|-------------|
| `42` | `"Int"` | `impl P for Int` |
| `3.14` | `"Float"` | `impl P for Float` |
| `"hi"` | `"Str"` | `impl P for Str` |
| `true` | `"Bool"` | `impl P for Bool` |
| `#[1 2]` | `"Vector"` | `impl P for Vector` |
| `{a= 1}` | `"Map"` | `impl P for Map` |
| `Point 1 2` | `"Point"` | `impl P for Point` |
| `Ok 42` | `"Ok"` | `impl P for Ok` |

Tagged values use the constructor name as the type name, so sum type variants can have individual implementations.

## Value Methods vs Function Methods

Methods can be either values or functions:

```irj
proto Monoid a
  empty :: a                  ;; a value of type a
  append :: a -> a -> a       ;; a function

impl Monoid for Int
  empty := 0                  ;; plain value — returned directly
  append := (+)               ;; function — applied to args
```

For value methods like `empty`, the caller still needs to provide a "type hint" argument for dispatch:

```irj
empty 0          ;; dispatch on Int → returns 0
empty ""         ;; dispatch on Str → returns ""
```

## Multiple Implementations

The same protocol can be implemented for different types:

```irj
proto Combine a
  combine :: a -> a -> a

impl Combine for Int
  combine := (+)

impl Combine for Str
  combine := (a b -> a ++ " " ++ b)

combine 3 7          ;; → 10
combine "hi" "world" ;; → "hi world"
```

## Overriding

If a second `impl` is declared for the same protocol and type, it replaces the first:

```irj
impl Process for Int
  process := (x -> x + 1)

impl Process for Int
  process := (x -> x * 2)    ;; this one wins

process 5  ;; → 10
```

## Using with Data Types

```irj
spec Person
  name :: Str
  age :: Int

proto Describe a
  describe :: a -> Str

impl Describe for Person
  describe := (p -> "Person: " ++ p.name ++ " age " ++ to-str p.age)

describe (Person "Alice" 30)  ;; → "Person: Alice age 30"
```

## Errors

- **Unknown protocol**: `impl Nonexistent for Int` → runtime error
- **No implementation**: `show #[1 2 3]` (if no impl Show for Vector) → "No implementation of protocol 'Show' for type 'Vector'"
- **Missing argument**: Calling a dispatch function with no arguments → error

## Limitations

1. **No law verification** — `law` declarations are parsed but not checked. Deferred to Phase 6 (property-based testing).
2. **No multi-parameter protocols** — Dispatch is on first argument only.
3. **No default methods** — Every impl must provide all methods.
4. **No protocol inheritance** — No `proto A extends B`.
5. **Type dispatch granularity** — Uses `Values.typeName()` which returns constructor names for Tagged values. Can't dispatch on `type Option` as a whole, only on `Some`/`None` separately.

## Files

| File | Changes |
|------|---------|
| `Values.java` | Added `ProtocolDescriptor` record with dispatch table |
| `Interpreter.java` | Added `evalProtoDecl`, `evalImplDecl`, `isCallable`, protocol registry |
| `Decl.java` | Added `ProtoDecl`, `ImplDecl`, `ProtoMethod`, `ProtoLaw`, `ImplBinding` records |
| `AstBuilder.java` | `visitProtoDecl`, `visitImplDecl` — proper parse tree → AST conversion |
| `InterpreterTest.java` | 15 new protocol tests |
| `ParserSmokeTest.java` | 6 new protocol/impl parse tests |
