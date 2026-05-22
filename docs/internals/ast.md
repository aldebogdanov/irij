# AST

Shape of the Abstract Syntax Tree. The contract between front-end and
back-ends.

## Top level: sealed hierarchies

Each node category is a Java `sealed interface` whose permitted
implementations are `record`s. This gives:

- Value semantics (records auto-derive equals/hashCode).
- Compile-time exhaustiveness — `switch` over the sealed root forces
  every implementor to be handled.
- Zero ceremony — no visitor pattern boilerplate.

## Categories

| Sealed root | File | What it represents |
|---|---|---|
| `Decl` | `Decl.java` | Top-level declarations: `fn`, `effect`, `handler`, `mod`, `use`, `impl`, `proto`, `cap`, `role`, `spec`, bindings, pubs |
| `Stmt` | `Stmt.java` | Statements: `:= bind`, `:! mut-bind`, `<- assign`, `with`, `scope`, expression-as-statement, block-level `if`, block-level `match` |
| `Expr` | `Expr.java` | Expressions: literals, vars, App, Lambda, If, Match, Block, JavaRef, OpSection, Compose, DotAccess, ... |
| `Pattern` | `Pattern.java` | Patterns: VarPat, WildcardPat, UnitPat, LiteralPat, ConstructorPat, VectorPat, TuplePat, DestructurePat, SpreadPat |
| `SpecExpr` | `SpecExpr.java` | Spec annotations: primitives, composites, arrows, enums |

## Some shapes worth highlighting

### `Decl.FnDecl`

```java
record FnDecl(
    String name,
    SpecExpr specAnnotation,        // optional `::` row
    List<String> effectRow,         // `::: Eff1 Eff2`; null = inherit
    FnBody body,                    // lambda / match-arms / imperative
    SourceLoc loc) implements Decl {}
```

`FnBody` is itself a sealed interface with three variants — `LambdaBody`
(single expression), `MatchArmsBody` (multi-arm pattern match),
`ImperativeBody` (statement sequence). The variant is determined at
parse time by the first significant token of the body.

### `Stmt.With`

```java
record With(
    Expr handler,            // resolves to a HandlerValue or composed
    List<Stmt> body,
    List<Stmt> onFailure,    // empty list if no on-failure clause
    SourceLoc loc) implements Stmt {}
```

`with` is a statement, not an expression. To bind a `with`'s value you
write `r := with X body` — that's a `Stmt.Bind` whose `value` is an
`Expr.Block` containing a single `Stmt.With`. The bytecode emitter
recognises this exact shape and lowers it natively under nested-SM.

### `Expr.App`

```java
record App(Expr fn, List<Expr> args, SourceLoc loc) implements Expr {}
```

The `fn` is itself an expression — usually a `Var`, but can be a
`Lambda`, another `App` (currying), a `DotAccess`, or a `JavaRef`.
The emitter dispatches based on the `fn` shape to choose between
`invokestatic` (user fn), `IrijFn.apply` (lambda value), perform
(effect op), or constructor application.

### `Pattern.DestructurePat`

```java
record DestructurePat(
    Map<String, Pattern> fields,
    String spreadName,       // `...rest` or null
    SourceLoc loc) implements Pattern {}
```

Used in `{x= v age= a} := record`. Field order is preserved (LinkedHashMap)
so destructuring round-trips.

## What's NOT in the AST

The AST does not carry:

- **Types** — Irij doesn't have a static type system. Spec annotations
  live as `SpecExpr` nodes but they're for *runtime* validation.
- **Closure capture info** — `Expr.Lambda` doesn't list its free vars;
  the back-end computes them when it needs to (`collectFreeVars` in
  `ClassEmitter`, environment-based capture in `Interpreter.Lambda`).
- **Effect inference** — only explicit `:::` rows are carried. The
  emitter doesn't infer required effects from a fn body.
- **Source spans** — only a `SourceLoc(line, col, file)` per node. No
  ranges. Error messages quote the *start* of the node.

## Why "no type system" colours the AST

Most AST decisions assume runtime checking:

- `Object` is the universal type at code-gen time. Every Irij value
  boxes to `java.lang.Object` in bytecode.
- Patterns carry no type discriminants — match arms test by tag at
  runtime via `instanceof` chains.
- Spec annotations are *runtime* validators. Inputs and outputs go
  through `SpecValidator.validateEncoded` (full `SpecExpr` coverage),
  emitted at fn entry and at every `ARETURN` site.

## Adding an AST node — checklist

1. Add `record X(...) implements Decl/Stmt/Expr/Pattern { }` in the
   right file. Use `SourceLoc loc` as the last field.
2. Decide if the field types are `List<...>` (always non-null), nullable
   single-Expr, or sealed sub-interface.
3. Wire `AstBuilder.visitX(...)` to construct it.
4. Add a `case X(...) ->` in every `switch` over the sealed root —
   the compiler will tell you which ones are missing.
