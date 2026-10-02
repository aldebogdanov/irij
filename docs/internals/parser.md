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
  Field positions are the exception: the `fieldName` parser rule
  accepts an IDENT or any keyword token, and is used after `.`, before
  `=` in a map literal (`mapEntry`) and a destructuring pattern
  (`destructureField`), and for a product spec's fields (`specField`).
  None of those positions can start a declaration or statement, so the
  keyword is unambiguous there; `AstBuilder` takes the field name from
  `fieldName().getText()`.
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

## Inline `if` parts are postfix expressions (2026-10)

```
ifExpr : IF postfixExpr postfixExpr ELSE postfixExpr
```

The condition and both branches were `atomExpr`. A field access is not
an atom, so in `if (a) x else s.p` the branch was `s` and the `.p` was
parsed as postfix on the whole `if` — `(if (a) x else s).p` — which
silently returned the wrong value; and `if c.ok "y" else "n"` did not
parse at all. Each part is now a `postfixExpr`, so a field access
belongs to the part it is written in. A parenthesised `if` is still an
atom, so `(if c a else b).p` reads the field of the result as before.

## The `model` declaration desugars in the builder (2026-08)

```
modelDecl : PUB? MODEL fnName SPEC_ANN STRING KEYWORD mapLiteral?
            effectAnnotation? (NEWLINE INDENT modelBody NEWLINE* DEDENT)?
modelClause : IDENT pattern* FAT_ARROW armBody
```

`AstBuilder.visitModelDecl` lowers it to declarations that already
exist — a `Decl.FnDecl` per clause plus a `Decl.BindingDecl` naming
them — so there is **no `Decl.ModelDecl`**, no emitter case, no
effect-checker case, no hot-redef case, and no second representation
of the model to drift from the first. The declaration is exactly the
`std.quint` record it produces.

```
model bank :: "spec/bank.qnt" :pure {main= "bankTest"}
  start                  => {balances= {alice= 0}}
  deposit  st who amount => {...st balances= (credit st who amount)}
```

becomes

```
fn bank$deposit
  => st $picks
  who := get "who" $picks
  amount := get "amount" $picks
  {...st balances= (credit st who amount)}

bank := {spec-file= "spec/bank.qnt" mode= :pure main= "bankTest"
         start= {balances= {alice= 0}} actions= {deposit= bank$deposit}}
```

Three things are load-bearing:

- **Functions, not lambdas.** Only a function can declare an effect
  row, and a live model's clauses are exactly the code that performs.
  `model … :live ::: Bank` puts the row on every hoisted function; a
  lambda would be refused at the perform for having declared nothing.
- **Unwritable names.** `$picks` and `bank$deposit` cannot come out of
  the lexer, so a spec free to name a pick anything cannot collide
  with the binding the desugaring introduces, and two models may both
  have a `deposit` clause.
- **Hoisting.** `build()` drains `AstBuilder.hoisted` before each
  top-level declaration it appends, since the functions must precede
  the binding that references them.

`MODEL` is a **soft keyword** — the first one. The lexer decides:
`MODEL : 'model' {atModelDecl()}?` emits the keyword only where a
declaration starts — first on its line, after an optional `pub`, and
followed by a name and `::` (`IrijLexerBase.atModelDecl`, a
lookbehind to the line start and a lookahead past the name). Anywhere
else `model` lexes as an IDENT, so it is an ordinary binding,
parameter, map field (`{model= "opus"}`) and dot-access field
(`cfg.model`). Reserving it outright broke every app whose state is
called `model` (uzor's `=> model ev`). The general fix for the other
keywords is the soft-keyword work TODO.md tracks under the
`party`/`quo` rename.
