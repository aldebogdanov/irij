# Phase 1 — AST & Tree-Walk Interpreter

Phase 1 implements a fully dynamic (L0) interpreter for Irij. Type annotations are parsed but ignored at runtime. All language features from the spec are implemented except effects/handlers (Phase 3), modules (Phase 4), concurrency (Phase 5), and choreography (Phase 7), which are parsed into AST but stubbed at runtime.

## What Was Built

### AST Layer (`src/main/java/dev/irij/ast/`)

| File | Description |
|------|-------------|
| `Node.java` | Sealed base interface + `SourceLoc` record (line:col) |
| `Expr.java` | 30+ expression node variants (literals, binary ops, application, lambda, pipeline, seq ops, collections, etc.) |
| `Stmt.java` | Statement nodes (bindings, assignments, if/else, match, with, scope) |
| `Decl.java` | Declaration nodes (fn, type, newtype, effect, handler, module, use, pub) + FnBody variants |
| `Pattern.java` | Pattern nodes (var, wildcard, literal, constructor, keyword, vector, tuple, destructure, spread) |
| `AstBuilder.java` | ANTLR parse tree visitor that produces AST nodes |

**Key AstBuilder decisions:**
- Expression precedence chain (12 levels) is collapsed: each level checks for operators and either passes through or creates a `BinaryOp` node
- `appExpr : postfixExpr+` becomes `App(fn, args)` — first element is function, rest are arguments
- Collection literals (`#[1 2 3]`) use `visitExprListFlat` to separate elements that the grammar treats as function application
- String interpolation: STRING tokens are scanned for `${}`, inner expressions re-parsed via `IrijParseDriver.parse()`

### Interpreter (`src/main/java/dev/irij/interpreter/`)

| File | Description |
|------|-------------|
| `Values.java` | Runtime value types: Rational, Keyword, Tagged, IrijVector/Map/Set/Tuple, IrijRange, Lambda, BuiltinFn, PartialApp, ComposedFn, Constructor |
| `Environment.java` | Lexical scope chain with ImmutableCell/MutableCell (closures capture mutables by reference) |
| `Interpreter.java` | Tree-walk evaluator: `eval(Expr)`, `exec(Stmt)`, `execDecl(Decl)`, `apply()`, `matchPattern()` |
| `Builtins.java` | All builtin functions and global bindings (`true`, `false`, `print`, `div`, `head`, `@`, `/+`, etc.) |
| `IrijRuntimeError.java` | Runtime error with source location |

### Tests (`src/test/java/dev/irij/interpreter/InterpreterTest.java`)

70 tests organized by feature in `@Nested` classes:
- `Arithmetic` — int/float math, precedence, widening, power, unary minus
- `Bindings` — immutable bindings, expressions, multiple bindings
- `Lambdas` — simple, two-arg, zero-arg, closure capture, higher-order
- `FnDecl` — lambda body fn, no annotation, recursive
- `Booleans` — true/false, comparison, equality, logic, combined
- `IfExpressions` — inline if, block if/else
- `PatternMatching` — literal, variable, wildcard, match-arm fn (fibonacci, factorial)
- `ImperativeBlocks` — zero-arg, with params, multi-statement
- `Mutability` — mutable binding, closure capture by reference, immutable assignment error
- `Collections` — vector, set, tuple, map, record update
- `TypeDeclarations` — sum type, zero-arg constructor, pattern match on ADT, Result type
- `Destructuring` — map destructure, vector spread
- `Guards` — match with guard expression
- `Pipelines` — pipe forward, pipe chain, compose forward/backward
- `SeqOps` — reduce sum/product, count, map, filter, all/any
- `Ranges` — inclusive, exclusive, to-vec
- `StringInterpolation` — simple variable, expression interpolation
- `MoreBuiltins` — concat, string concat, head/tail, length, div, mod, dot access, keywords
- `PartialApplication` — partial apply, partial builtin
- `Rationals` — rational addition, display
- `Stubs` — effect declaration, module declaration
- `Errors` — undefined variable, division by zero

## Build & Test

```bash
# Run all tests (81 parser + 70 interpreter = 151 total)
./gradlew test

# Compile only
./gradlew compileJava

# Regenerate ANTLR sources
./gradlew generateGrammarSource
```

## Runtime Value Types

| Irij Type | Java Representation | Example |
|-----------|-------------------|---------|
| Int | `Long` | `42` |
| Float | `Double` | `3.14` |
| Rational | `Values.Rational(num, den)` | `2/3` |
| Bool | `Boolean` | `true`, `false` |
| Str | `String` | `"hello"` |
| Keyword | `Values.Keyword(name)` | `:ok` |
| Unit | `Values.UNIT` | `()` |
| Vector | `Values.IrijVector(List)` | `#[1 2 3]` |
| Map | `Values.IrijMap(Map)` | `{name= "Jo"}` |
| Set | `Values.IrijSet(Set)` | `#{1 2 3}` |
| Tuple | `Values.IrijTuple(Object[])` | `#(1 "a")` |
| Tagged | `Values.Tagged(tag, fields)` | `Some 42`, `None` |
| Lambda | `Values.Lambda(params, body, env)` | `(x -> x + 1)` |
| Range | `Values.IrijRange(from, to, excl)` | `1 .. 10` |

## Design Decisions Applied

Per `docs/phase-1-design-decisions.md`:
- **Implicit widening**: Int + Float → Float
- **Division**: `/` always returns Float (or Rational for Rational/Rational)
- **Booleans**: `true`/`false` are builtin identifiers, not keywords
- **Seq ops**: Duck-typed at L0 — work on any iterable
- **Ranges**: Lazy iterators, both endpoints required
- **Closures**: Capture mutables by reference (shared `MutableCell`)
- **Evaluation**: Strict everywhere except lazy ranges
- **Errors**: Runtime exceptions with source locations

## Known Limitations

1. **`mod` keyword conflict**: `mod` is a keyword in the lexer (module declaration), so it can't be used as a builtin function name. Use `%` operator instead.
2. **Collection literal ambiguity**: `#[f x]` is parsed as function application (`f(x)`) inside the vector, not two elements. Use `#[(f x)]` for clarity, or `#[a b]` only with non-callable atoms.
3. **Lambda body limitations**: Lambda body is `exprSeq` (expressions only). Statements like `x <- expr` must use imperative fn body form instead.
4. **`flip`**: Declared but throws "requires partial application support".
5. **Effects/handlers**: Parse into AST but `with handler` executes body without handler support.
6. **Infinite ranges**: Not supported — both endpoints required.

## Programmatic API

```java
import dev.irij.ast.AstBuilder;
import dev.irij.parser.IrijParseDriver;
import dev.irij.interpreter.Interpreter;

// Parse
var parseResult = IrijParseDriver.parse("println (1 + 2)\n");
assert !parseResult.hasErrors();

// Build AST
var ast = new AstBuilder().build(parseResult.tree());

// Interpret
var interpreter = new Interpreter(); // uses System.out
// or: new Interpreter(myPrintStream) for capturing output
var result = interpreter.run(ast); // returns last value
```
