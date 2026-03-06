# Spec Suggestions from Initial Grammar Implementation

These are observations and suggested clarifications/changes discovered while
implementing the ANTLR 4 grammar for Irij. The spec file itself was NOT modified.

---

## 1. UPPER_ID Ambiguity: Types vs Roles

**Problem:** The spec defines three categories of uppercase identifiers:
- PascalCase for types: `User`, `HttpResponse`, `Result`
- ALL-CAPS for roles: `ALICE`, `BOB`, `DB-PRIMARY`
- Short all-caps for type names: `IO`, `Ok`

Single-word all-caps like `IO` are ambiguous — is it a type or a role?

**Current grammar solution:** Use a single `UPPER_ID` token covering all uppercase
identifiers. The parser disambiguates by context (e.g., `ROLE` keyword precedes role
declarations, `~>` / `<~` operators take role arguments).

**Suggestion:** Consider documenting this explicitly in the spec — that roles and types
share the same lexical form and are disambiguated by context. Alternatively, require
roles to have a `$` prefix or other distinguishing sigil (e.g., `$ALICE`, `@ALICE`),
though this conflicts with the Dense ASCII philosophy.

---

## 2. Colon `:` Conflicts

**Problem:** The colon character is overloaded:
- `:=` — immutable binding
- `:!` — mutable binding
- `::` — type annotation
- `:ok` — keyword literal (Clojure-style atom)
- `:open` — use modifier
- `name: "jo"` — map/record field separator

The lexer must carefully order rules to avoid conflicts. Currently `BIND` (`:=`),
`MUT_BIND` (`:!`), `TYPE_ANN` (`::`), `KEYWORD_LIT` (`:word`), and `OPEN` (`:open`)
are all separate tokens, with a bare `COLON` as fallback for map entries.

**Suggestion:** This works but is fragile. Consider whether map/record field syntax
could use `=` instead of `:` to reduce overloading. E.g., `{name= "jo" age= 30}`.
This would free `:` for its primary roles (binding, type annotation, atoms).

---

## 3. `@` Operator Overloading

**Problem:** `@` is used for three distinct purposes:
- `@ f` — map-over / each (sequence operation)
- `@i` — map-indexed
- `T @ROLE` — located type annotation (choreography)

And `@Something` is used for Java annotations.

**Current grammar solution:** `JAVA_ANNOTATION` is a lexer rule that matches
`@[A-Z]...` as a single token, while bare `@` is the `AT` token. `@i` is `AT_INDEX`.

**Suggestion:** The spec might want to make the Java annotation syntax more explicit
or use a different syntax (e.g., `#[java.Override]` like Rust attributes) to avoid
conflict with the `@` operator. The current approach works but requires careful lexer
rule ordering.

---

## 4. `!` Operator Overloading

**Problem:** `!` is used for:
- `!expr` — boolean NOT
- `xs |> ! pred` — find first match
- `detach!` — escape hatch (fire-and-forget)
- `even?` / `set!` — identifier suffix

**Suggestion:** The `!` as "find first" in pipelines could be confused with boolean NOT.
Consider a digraph like `/?` (find/query) or `/>` (find-first) for the find operation.

---

## 5. `fn` Body Syntax Ambiguity

**Problem:** Function body can be either:
- Multi-clause pattern matching (directly under `fn`):
  ```
  fn fib :: Int -> Int
    0 => 0
    1 => 1
    n => fib (n - 1) + fib (n - 2)
  ```
- Lambda expression:
  ```
  fn add :: Int -> Int -> Int
    (x y -> x + y)
  ```
- Imperative block:
  ```
  fn greet :: Str -[Console]-> ()
    name := read-line ()
    print "Hello, ${name}!"
  ```

**Suggestion:** The grammar handles this via parser alternatives, but the spec could
benefit from a clearer description of when each form is expected. In particular, how
does the parser distinguish between `n => ...` (match arm) and `n := ...` (binding
in an imperative block)? Currently this relies on looking ahead for `=>` vs `:=`.

---

## 6. Semicolons in Inline Expressions

**Problem:** The spec shows `;` used as an expression separator in some inline contexts:
```
s.fork (-> cmd ~> REPLICA; REPLICA.store k v)
```

**Suggestion:** The spec should clarify whether `;` is a general expression separator
(like OCaml) or is only allowed inside parenthesized expressions. The current grammar
defines `SEMI` as a token but doesn't use it in parse rules — this needs to be added
for inline sequencing support.

---

## 7. Record Update Syntax

**Problem:** The spec shows record update with spread:
```
{..account balance: account.balance - amount}
```

The `..` prefix on `account` here is ambiguous with the range operator `..`.

**Suggestion:** Consider using `...account` (spread, 3 dots) consistently for both
pattern destructuring and record update. This would be:
```
{...account balance: account.balance - amount}
```
This is consistent with `#[x ...rest]` patterns.

---

## 8. Effect Arrow Token Sequencing

**Problem:** The effect arrow `-[E1 E2]->` is parsed as individual tokens:
`MINUS LBRACK E1 E2 RBRACK ARROW`. This works but means the lexer emits `-` and `[`
as separate tokens, which could conflict with arithmetic minus and vector access.

**Current solution:** The parser assembles these into an `effectArrow` rule. This is
correct but relies on parser-level disambiguation.

**Suggestion:** Consider making `-[` a single digraph token (`EFFECT_ARROW_OPEN`) and
`]->` another (`EFFECT_ARROW_CLOSE`). This would make lexing unambiguous and is
consistent with the Dense ASCII philosophy of digraph operators.

---

## 9. Indentation: 2-Space Mandate vs Flexibility

**Problem:** The spec mandates 2-space canonical indentation, but production parsers
typically accept any consistent indentation level.

**Current solution:** The `IrijLexerBase` accepts any indentation level for robustness
but the spec says "2-space canonical."

**Suggestion:** Clarify whether non-2-space indentation is:
- A hard error (rejected by parser)
- A warning (accepted but formatter normalizes to 2-space)
- Silent (accepted, no diagnostic)

Recommendation: warning + auto-format, like Python's PEP 8 vs actual parser behavior.

---

## 10. Missing from Spec: Expression Termination

**Problem:** The spec doesn't explicitly address how the parser knows where one
expression ends and another begins in function bodies. In indentation-based languages,
this is usually resolved by newlines (each line is a statement) with continuation
rules for multi-line expressions.

**Suggestion:** Add a section on line continuation. Options:
- Trailing operator continues to next line: `x |>\n  f |>\n  g`
- Explicit continuation marker (e.g., `\` at end of line)
- Indent-based: continuation lines must be more indented than the statement start

The current grammar assumes newlines separate statements in function bodies.
