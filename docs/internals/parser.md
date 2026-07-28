# Parser

Grammar in `src/main/antlr/IrijParser.g4` and `IrijLexer.g4`. Generated
parser is built by Gradle into `build/generated-src/antlr/main`.

## Indent-sensitive layout

Irij uses Python-like significant whitespace. ANTLR4 can't handle that
directly, so we run the token stream through `IndentRewriter` between
lexing and parsing. The rewriter:

- emits a synthetic `INDENT` token when a line starts deeper than the
  current indent level
- emits one or more `DEDENT` tokens when it starts shallower
- collapses blank lines to `NEWLINE`
- balances the final indent with closing `DEDENT`s before `EOF`

After rewriting, the grammar treats indentation like any other token —
no special-cased layout rules in `.g4`.

## Lexer quirks

- **Reserved words are minimal but real.** `in`, `out`, `blame` come
  from contract clauses; `match`, `with`, `fn`, `if`, `else`, `mod`,
  `use`, `pub`, `effect`, `handler`, `cap`, `party`, `proto`, `impl`,
  `spec` are language structure. If you try to use one as an
  identifier, the parser will complain in surprising places — it
  often manifests as "expecting `->`" rather than "reserved word."
- **Operators are tokens, not identifiers.** `+`, `-`, `==`, `++`, etc.
  Operator *sections* `(+)` are lifted to `Expr.OpSection` in the AST
  and lowered to runtime constants in the emitter.
- **`::` vs `:::`**: `::` introduces a spec annotation; `:::` introduces
  an effect-row annotation. Different tokens (`SPEC_ANN` vs
  `EFFECT_SEP`).
- **`:=` vs `:!` vs `<-`**: immutable bind, mutable bind, mutable
  assign. All distinct tokens.

## Parse tree → AST

`AstBuilder` walks the parse tree (visitor pattern) and produces nodes
from `dev.irij.ast.{Decl,Stmt,Expr,Pattern}`. The AST is a *sealed*
hierarchy — every node kind is enumerated, so consumers use exhaustive
pattern matching with the compiler checking we covered every case.

Why sealed:

- Type-system pressure for completeness — adding a new node kind makes
  every existing `switch` light up.
- No "default" trap door — forces explicit decisions when extending.
- Records give value semantics + free equality.

## What's parsed but not yet implemented

The grammar accepts more than the back-ends implement. Examples:

- `cap` declarations parse but are mostly stubs.
- Operator sections `(+)` parse; bytecode emitter ships them now (Phase
  2.5) but other forms like `(_ + 1)` (one-side section) aren't lifted.
- Multi-clause `if` with the second `else` introducing a new block on a
  new line — sometimes parses, sometimes fails depending on indent.
  The grammar is stricter than necessary here; usually fixable with
  explicit grouping `(if c1 a else (if c2 b else c))`.

## Adding a new syntax form — checklist

1. Update `IrijLexer.g4` / `IrijParser.g4`.
2. Run `./gradlew generateGrammarSource` to regenerate.
3. Add an AST record in the right file (`Decl`, `Stmt`, `Expr`, etc.).
4. Add the `visit*` method in `AstBuilder.java`.
5. Add handling in `Interpreter.eval` (or the closest existing case).
6. Add handling in `ClassEmitter.emit*` — or document why bytecode mode
   doesn't support it yet (and ensure it surfaces a clean error).
7. Tests in both modes.

## Curried lambda chains (PR4, 2026-07)

`lambdaChain : lambdaParams ARROW (lambdaChain | exprSeq)` — so
`(a -> b -> body)` parses as `(a -> (b -> body))` without explicit
nesting. ANTLR's adaptive prediction disambiguates: after an ARROW it
first tries another chain link (`pattern* ARROW …`) and falls back to
`exprSeq` when no second ARROW follows. Both `lambdaExpr` and fn-decl
`lambdaBody` share the rule; in a fn decl the outermost params stay fn
params and the rest of the chain becomes a nested `Expr.Lambda`.

## Dynamic map keys (PR4, 2026-07)

`mapEntry` gained `LPAREN expr RPAREN EQUALS expr` — `{(k)= v}`
evaluates the key at runtime (`Expr.MapEntry.DynField`); it must
produce a Str (`RtCollections.asMapKey` throws otherwise). Works in
map literals and `{...base (k)= v}` record updates. Dynamic keys are
skipped by row-var inference over record specs (key unknowable at
compile time).
