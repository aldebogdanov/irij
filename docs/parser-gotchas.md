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

## Standard library arg order (not parser, but commonly confused)

- `split string sep` (string first, separator second).
- `assoc map key value`.

## Fixed in recent versions (0.2.7+)

Previously listed gotchas that no longer apply:

- ~~Multi-line `if` in lambda bodies~~ — block-form `if` is now an expression.
- ~~String keys in map literals~~ — `{"content-type"= val}` parses.
- ~~`with` / `scope` only at statement position~~ — now usable in expression position.
