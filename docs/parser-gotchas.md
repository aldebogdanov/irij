# Parser Gotchas

Known edge cases and workarounds for the Irij ANTLR4 grammar. Ordered roughly by how often they bite.

## Lexer

- **Keywords cannot be variable names:** `role`, `mod`, `in`, `out`, `contract`, `forall`, `handler`, `if`, `else`, `match`, `with`, `scope`, `fn`, `use`, `pub`, `do`.
- **INDENT/DEDENT:** DEDENT is emitted BEFORE a NEWLINE in the token stream. All `INDENT ... DEDENT` grammar rules require `NEWLINE*` before `DEDENT`.
- **`_foo` doesn't work:** `_` is the `UNDERSCORE` token, so `_foo` lexes as two tokens. Use `raw-foo` for internal-looking names.
- **`!` suffix on IDENT** conflicts with the `NOT` token — avoid `foo!` as a name.
- **Error line numbers drift:** ANTLR error messages sometimes report wrong line/column (off by dozens of lines in large files). Open bug in lexer line tracking.

## Line continuation

- **Backslash continuation:** `\` at end of line suppresses the NEWLINE, letting a function call span multiple lines:
  ```
  println \
      "hello"
  ```
  Useful for long argument lists. Without `\`, a NEWLINE ends the expression and subsequent indented lines start a new statement block.

## Expressions & lambdas

- **Lambda body is an `exprSeq`:** `(x -> body)` accepts only expressions — no `:=`, no statements. For anything with local bindings or control flow, use `fn name => args` imperative form.
- **Chained lambdas must nest:** `(a -> b -> body)` fails. Write `(a -> (b -> body))`.
- **`fn name (x -> ...)` on one line** fails — body must start on the next indented line.
- **Inline `if`:** `if cond-atom then-atom else else-atom` — each part must be an atomic expression. For complex branches use the block-form (now usable as an expression: `x := if cond\n  a\nelse\n  b`).
- **Block-form `with`, `scope`, `if`, `match`** are all valid in expression position (wrapped by the AST builder into an `Expr.Block`). E.g. `n := with default-fs\n  fs-read path` works.

## Handlers

- **Handlers are top-level declarations, not expressions.** `handler name :: Effect ... ` is a statement; there is no `handler :: Effect ...` value form. You cannot:
  - bind a handler to a variable (`h := handler :: Greet ...` ⛔)
  - define a handler inline inside `with` (`with (handler :: Greet ...)` ⛔)
  - pass a handler as a function argument
  - dispatch `with` on a dynamic name (`with (if debug? h1 h2)` ⛔)
- **`with` requires a static handler name.** Branch at the statement level instead:
  ```
  if debug?
    with debug-handler
      app ()
  else
    with prod-handler
      app ()
  ```
  Deferred design question: whether to introduce first-class handler values (would require a handler type, op-name reification, and bigger runtime changes). For now, duplicate the `with` block per branch.
- **`with` is block-form only.** `with h (expr)` on a single line does NOT parse — the body must start on the next indented line (`with h\n  body`).
- **Zero-arg effect ops require `()` at call site.** Bare `op-name` inside a function body returns the builtin value, not the invocation result. Write `op-name ()`. This is consistent with zero-arg builtins generally (`now-ms ()`, `rand-float ()`).

## Vectors and calls

- **Infix operators inside `#[...]`:** `#[a ++ b]` fails. Wrap: `#[(a ++ b)]`.
- **Function calls inside `#[...]`:** `#[route "GET" "/" h]` parses as 4 elements. Wrap: `#[(route "GET" "/" h)]`.

## Maps

- **String keys in map literals:** `{"content-type"= val}` is now supported (as of 0.2.7). Dot access with string keys (`m."content-type"`) is NOT — use `get m "content-type"` or destructuring.
- **Map literals inside `if` branches:** parser sometimes confused by `{k= v}` in a branch. Wrap: `(if cond ({a= 1}) else ({b= 2}))`.
- **`bindTarget` accepts only `IDENT` or `{field= name}`** patterns — vector destructuring in `:=` needs `match`.

## Pipelines & seq-ops

- **Seq-op direct-form:** `@ f xs`, `/? pred xs`, `@i f xs`, `/^ f xs` all parse as `(seq-op f) xs` (the first postfix becomes the seq-op's argument, subsequent postfixes apply to the resulting partial). Both pipe and direct forms work:
  ```
  #[1 2 3] |> @ (x -> x * x)     ;; pipe form
  @ (x -> x * x) #[1 2 3]        ;; direct form — also works
  ```
- **`-` in pipelines:** `-` is excluded from implicit-continuation parsing to avoid ambiguity with unary negation. You cannot pipe `|> -` or start a continuation line with `-`; wrap in parens or use `(-)` as a value.

## Strings

- **`${}` always interpolates** at runtime — no escape. Split if you need a literal: `"$" ++ "{}"`.

## Contracts & types

- **`contractClause`** has five alternatives: `pre`, `post`, `in`, `out`, `law`.
- **`forall binders`** require `.`: `forall x y. P x y`.
- **`|` (BAR)** is used for guards and refinement types — distinct from `||` (logical OR).

## Java interop (0.3.0+)

- **`foo/bar` (no spaces) is always a Java class reference.** The lexer's `JAVA_REF` token (alpha start, contains `/`, alpha continuation) outranks seq-ops and IDENT/SLASH/IDENT. For integer division write `a / b` with spaces, or `(/) a b`.
- **`CAMEL_IDENT` only appears after `.`** — identifiers like `toUpperCase` lex as `CAMEL_IDENT` (at least one internal uppercase) and are grammar-restricted to dot-access positions. Bare `toUpperCase` used as a variable is a parse error.
- **Chained instance method calls need parens or `|>`.** Grammar is `atomExpr (DOT field)*` — each postfix is a single dot-access chain, not an applied-call chain. Write `(("x".trim ()).length ())` or pipeline.
- **`()` means zero args, including to Java.** `obj.m ()` calls the 0-arg overload, not the 1-arg `m(null)` variant. A lone UNIT arg is stripped by `JavaInterop.normalizeArgs` before reflection dispatch.

## Standard library arg order (not parser, but commonly confused)

- `split string sep` (string first, separator second).
- `assoc map key value`.
- `fold (acc next -> ...) init coll` — accumulator FIRST in the lambda
  args, current element second. Mismatching that order silently
  produces nonsense (no type-check to save you).

## Top-level expression statements (App-as-decl)

- A bare top-level `fn-name arg1 arg2` works ONLY for `Var args+`
  shapes the parser disambiguates eagerly. A top-level
  `fold (acc n -> ...) 0 #[1 2 3]` fails: after `fold` the parser
  expects `<EOF>` or `<NEWLINE>` and chokes on the `(`. Wrap the
  expression — either in a binding `total := fold ...` or a call
  `println (fold ...)`. Same gotcha applies to any App whose first
  argument starts with `(`.

## Function body forms — imperative `=>`

Three function body forms; only the imperative one uses `=>`.

```
;; Lambda body:
fn add (a b -> a + b)

;; Match-arms body — `pattern => expr`:
fn describe
  0 => "zero"
  n => "other"

;; Imperative body — `=> args; statements`:
fn greet ::: Console
  => name
  println ("Hello, " ++ name)

;; 0-arity imperative — `=>` alone on its line, NO underscore:
fn boot ::: Console
  =>
  println "ready"
```

**Common mistake (worth re-reading):** writing `_ =>` for a 0-arg
imperative fn:

```
fn boot ::: Console
  _ =>                        ;; WRONG — match-arm body with wildcard
    println "ready"
```

This parses, and `boot ()` happens to call it correctly (the Unit arg
matches the wildcard), so it *looks* like the imperative form works.
It isn't — it's a match-arms body with one arm. Idiomatic Irij:

```
fn boot ::: Console
  =>                          ;; correct — 0-arity imperative body
  println "ready"
```

The semantic difference: `_ =>` makes the fn a 1-arg fn that pattern-
matches its single arg against `_`; `=>` makes it a 0-arity fn whose
body runs imperatively. Both produce the same output for `boot ()`
because Irij allows passing Unit to either, but the body indentation
and the call-site contract are different — and any AI/code-generation
tool that crops up later will write `_ =>` because it looks like
"match anything". Re-write to `=>` on review.

**Indentation:** the body lines after the `=> args` row sit at the
SAME indent as `=>`, NOT one level deeper. The parser uses INDENT to
open the body scope at the `=>` line; subsequent lines must dedent
back to the same column.

## Record specs (0.7.x)

`{field :: spec; field :: spec}` declares an *inline* record (map)
spec. Fields are separated by `;` (NEWLINEs are suppressed inside
braces, so semicolon is the only inline delimiter). Records are
*open* — extra fields on the value are accepted.

Row-vars work inside record specs: a field declared
`action :: (Fn):eff` surfaces `eff` to the enclosing fn signature
so polymorphic dispatchers (router-shaped fns) propagate their
elements' effects to callers.

## Named record specs with row params (0.7.x)

```
pub spec Route ::: eff
  action :: (Fn):eff
```

`spec Name ::: rowParam` declares a *named* record spec carrying
a row parameter. Use-sites bind it via `(Name):rowVar`:

```
pub fn router :: #[(Route):eff] Fn ::: eff
  (routes -> (req -> find-route routes req))
```

The row-var walk inlines the named spec at the use-site,
substitutes the spec's declared `eff` with the use-site's row-var,
then recurses into the result the same way it would for an inline
`{action :: (Fn):eff}`. Refactor benefit: the route shape lives
in one place; all router/find/apply fns share `(Route):eff` in
their signatures.

Mismatch shape:
- spec field declared but absent from value → "missing required
  field" at validation
- value field with wrong inner spec → "field 'X': expected …"

## Fixed in recent versions (0.2.7+)

Previously listed gotchas that no longer apply:

- ~~Multi-line `if` in lambda bodies~~ — block-form `if` is now an expression.
- ~~String keys in map literals~~ — `{"content-type"= val}` parses.
- ~~`with` / `scope` only at statement position~~ — now usable in expression position.
- ~~`else if` chain requires nesting~~ — `else if cond` chains are supported natively (0.2.11+).
