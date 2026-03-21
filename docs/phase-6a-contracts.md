# Phase 6a — Pre/Post Contracts

## Overview

Irij supports Design by Contract (DbC) on functions, inspired by Eiffel and Racket's contract system. Functions can declare **pre-conditions** (caller obligations) and **post-conditions** (implementation guarantees). Violations produce clear error messages that assign blame to the appropriate party.

Pre/post contracts complement the algebraic effect system and protocols by catching logic errors early, at function boundaries, without requiring a full type checker.

## Syntax

Contract clauses appear between the `fn` declaration line and the function body:

```irj
fn divide
  pre (a b -> b != 0)
  post (result -> true)
  a b -> a / b
```

Multiple contracts are allowed — all must pass:

```irj
fn clamp
  pre (lo hi x -> lo <= hi)
  pre (lo hi x -> true)
  post (result -> result >= lo)
  post (result -> result <= hi)
  lo hi x -> max lo (min hi x)
```

### Grammar

```
contractClause
  : 'pre'  parenExpr
  | 'post' parenExpr
  ;

fnBodyContent
  : contractClause* (lambdaBody | imperativeBody | matchArms)
  ;
```

The parenthesized expression after `pre`/`post` must be a lambda (or any callable) that returns a boolean.

## Semantics

### Pre-conditions

Pre-conditions are checked **before** the function body executes. The lambda receives the same arguments as the function:

```irj
fn sqrt
  pre (n -> n >= 0)
  n -> math.sqrt n
```

Calling `sqrt (-1)` produces:

```
Error: pre-condition violated in 'sqrt': (n -> n >= 0) with args [-1]
```

**Blame**: the caller supplied invalid arguments.

### Post-conditions

Post-conditions are checked **after** the function body returns. The lambda receives the return value as its single argument:

```irj
fn abs
  post (result -> result >= 0)
  n -> if (n >= 0) n else (0 - n)
```

If the implementation were buggy and returned a negative number:

```
Error: post-condition violated in 'abs': (result -> result >= 0) with result -5
```

**Blame**: the function implementation failed to uphold its guarantee.

### Evaluation Order

1. Caller invokes the function with arguments
2. Each `pre` lambda is applied to the arguments, in declaration order
3. If any pre-condition returns a falsy value, raise an error (do not execute body)
4. The function body executes normally
5. Each `post` lambda is applied to the return value, in declaration order
6. If any post-condition returns a falsy value, raise an error
7. Return the result to the caller

### Partial Application

Contracts are **deferred** when a function is partially applied. Pre-conditions only fire once all arguments are supplied. This means curried usage works naturally:

```irj
fn add-positive
  pre (a b -> a > 0)
  pre (a b -> b > 0)
  a b -> a + b

inc := add-positive 1    ;; no check yet — partially applied
inc 5                    ;; pre-conditions checked now (a=1, b=5)
```

## Implementation Details

### `ContractedFn` Wrapper

A new record in `Values.java` (or directly in the interpreter) wraps the underlying callable:

```
ContractedFn(String name, Object inner, List<Object> preConditions, List<Object> postConditions)
```

- `inner` is the actual function value (Lambda, MatchFn, ImperativeFn, or BuiltinFn)
- `preConditions` and `postConditions` are lists of callable objects (closures)
- When `apply()` encounters a `ContractedFn`, it checks pre-conditions, delegates to `inner`, then checks post-conditions
- If `inner` returns a partial application (not all args consumed), the result is re-wrapped in a new `ContractedFn` with the same contracts

### AST Changes

`FnDecl` extended with two new fields:

```java
record FnDecl(String name, List<Object> preConditions, List<Object> postConditions,
              Object body, SourceLoc loc) implements Decl { ... }
```

`AstBuilder.visitFnBodyContent` extracts `contractClause` nodes before processing the function body form. Each `pre`/`post` expression is evaluated at declaration time in the enclosing environment, capturing closures as needed.

### Works With All Fn Body Forms

Contracts are orthogonal to the body form — they sit between the `fn` line and whatever body follows:

**Lambda body:**
```irj
fn positive-sqrt
  pre (n -> n >= 0)
  post (r -> r >= 0)
  n -> math.sqrt n
```

**Imperative body (`=> params`):**
```irj
fn safe-divide
  pre (a b -> b != 0)
  => a b
  result := a / b
  result
```

**Match arms:**
```irj
fn factorial
  pre (n -> n >= 0)
  post (r -> r > 0)
  0 -> 1
  n -> n * factorial (n - 1)
```

## Examples

### Basic Pre-condition

```irj
fn withdraw
  pre (balance amount -> amount > 0)
  pre (balance amount -> amount <= balance)
  balance amount -> balance - amount

withdraw 100 50    ;; => 50
withdraw 100 150   ;; Error: pre-condition violated in 'withdraw'
```

### Basic Post-condition

```irj
fn make-nonempty-list
  post (result -> len result > 0)
  items -> items

make-nonempty-list #[1 2 3]   ;; => #[1 2 3]
make-nonempty-list #[]         ;; Error: post-condition violated
```

### Combined Pre and Post

```irj
fn binary-search
  pre (vec target -> len vec > 0)
  post (idx -> idx >= -1)
  vec target -> search-impl vec target 0 (len vec - 1)
```

### Catching Violations with `try`

Contract violations are regular errors, catchable with `try`:

```irj
fn guarded-sqrt
  pre (n -> n >= 0)
  n -> math.sqrt n

result := try (-> guarded-sqrt (-4))
match result
  Ok v  -> println v
  Err e -> println (str "Caught: " e)
;; Caught: pre-condition violated in 'guarded-sqrt': ...
```

## Test Summary

- 6 parser tests: contract clause parsing, multiple pre/post, all body forms
- 15 interpreter tests: pre-condition pass/fail, post-condition pass/fail, combined contracts, partial application deferral, `try` catch, imperative body, match body, closure capture in contract lambdas, multiple contracts
- **21 new tests** (444 total, up from 423)
