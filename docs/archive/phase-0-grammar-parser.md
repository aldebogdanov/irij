# Phase 0 — Grammar & Parser

Status: **Complete** (64/64 parser smoke tests passing; 194 total including interpreter tests)

---

## What Was Built

Phase 0 establishes the full Irij parser — lexer, grammar, indent/dedent engine, and smoke tests. No interpreter or runtime yet; this phase only validates that source code can be parsed into a tree without errors.

### Files

| File | Purpose |
|------|---------|
| `src/main/antlr/IrijLexer.g4` | ANTLR4 lexer — all tokens (keywords, digraphs, literals, operators) |
| `src/main/antlr/IrijParser.g4` | ANTLR4 parser — full grammar rules for all Irij syntax |
| `src/main/java/dev/irij/parser/IrijLexerBase.java` | Python-style INDENT/DEDENT injection, line continuation, paren-depth tracking |
| `src/main/java/dev/irij/parser/IrijParseDriver.java` | Convenience API: `parse(String)`, `parseFile(Path)`, `tokenize(String)` |
| `src/test/java/dev/irij/parser/ParserSmokeTest.java` | 64 smoke tests organized by spec section |

---

## How to Build & Test

```bash
# Run all parser smoke tests
./gradlew test

# Regenerate ANTLR sources (happens automatically on build)
./gradlew generateGrammarSource

# Compile everything
./gradlew compileJava
```

All 64 parser smoke tests (194 total with interpreter tests) should pass with `BUILD SUCCESSFUL`.

---

## How to Use the Parser Programmatically

### Parse a source string

```java
import dev.irij.parser.IrijParseDriver;

var result = IrijParseDriver.parse("x := 42\n");

if (result.hasErrors()) {
    System.out.println("Errors: " + result.errors());
} else {
    // result.tree() is the ANTLR parse tree (CompilationUnitContext)
    System.out.println("Parsed OK");
}
```

### Parse a file

```java
var result = IrijParseDriver.parseFile(Path.of("examples/hello.irj"));
```

### Tokenize only (for debugging)

```java
List<Token> tokens = IrijParseDriver.tokenize("x := 1 + 2\n");
// Returns all tokens including synthetic INDENT/DEDENT/NEWLINE
```

### ParseResult record

```java
record ParseResult(
    CompilationUnitContext tree,   // ANTLR parse tree
    List<String> errors,          // "line:col message" format
    CommonTokenStream tokenStream // full token stream
) {
    boolean hasErrors();
}
```

---

## Grammar Overview

### Lexer Tokens

**Keywords (30):** `fn`, `do`, `if`, `else`, `match`, `type`, `newtype`, `mod`, `use`, `pub`, `with`, `scope`, `effect`, `role`, `cap`, `handler`, `impl`, `proto`, `pre`, `post`, `law`, `contract`, `select`, `enclave`, `forall`, `par-each`, `on-failure`, `in`, `out`, `for`, `proof`

**Digraph operators (35+):** `:=`, `:!`, `<-`, `->`, `=>`, `::`, `:::`, `|>`, `<|`, `>>`, `<<`, `~>`, `<~`, `~*>`, `~/`, `==`, `/=`, `<=`, `>=`, `&&`, `||`, `|`, `**`, `++`, `..`, `..<`, `...`, `/+`, `/*`, `/#`, `/&`, `/|`, `/?`, `/!`, `/^`, `/$`, `@`, `@i`

**Literals:** `INT_LIT`, `FLOAT_LIT`, `HEX_LIT`, `RATIONAL`, `STRING` (with `${}` interpolation), `KEYWORD` (`:ok`, `:error`), `ROLE_NAME` (`$BUYER`), `TYPE_NAME` (`Int`), `IDENT` (kebab-case)

**Synthetic tokens:** `INDENT`, `DEDENT`, `NEWLINE` — injected by `IrijLexerBase`

### Parser Structure

**Top-level declarations:** `fn`, `type`, `newtype`, `effect`, `handler`, `cap`, `proto`, `impl`, `role`, `mod`, `use`, `pub`, `match`, `if`, `with`, `scope`, bindings, expressions

**Function body forms (3):**
1. Lambda: `(x y -> expr)`
2. Match arms: `pattern => expr` (one per line)
3. Imperative block: `=> params` followed by statements

**Expression precedence (lowest → highest):**
apply-to (`~`) → choreography → pipeline → composition → or → and → equality → comparison → concat → range → add → mul → power → unary → seq-ops → application → postfix → atom

**Patterns:** constructors, keywords, variables, wildcards, literals, unit `()`, grouped `(pat)`, vectors `#[x ...rest]`, tuples `#(a b)`, destructure `{name= n}`, spread `...rest`

**Types:** type application (`Result a e`), function arrows (`A -> B`), effect declarations (`::: E1 E2`), located types (`T @$ROLE`), unit `()`, collections

---

## INDENT/DEDENT Engine

`IrijLexerBase.java` intercepts every token from the raw ANTLR lexer and injects synthetic `INDENT`, `DEDENT`, `NEWLINE` tokens based on leading whitespace.

**Rules:**
- Strict 2-space indentation (odd spaces = hard error, tabs = forbidden)
- Deeper indent → `NEWLINE` + `INDENT`
- Same indent → `NEWLINE`
- Shallower indent → `DEDENT`(s) + `NEWLINE`
- Inside parens/brackets/braces: newlines suppressed (no INDENT/DEDENT)
- Trailing `\` suppresses the next newline (line continuation)
- Comments at start of indented block: consumed during indent measurement (treated as blank lines)
- At EOF: remaining `DEDENT`s emitted automatically

**Grammar convention:** All rules with `INDENT ... DEDENT` include `NEWLINE*` before `DEDENT` to handle trailing newlines.

---

## Test Coverage

64 tests organized by spec section (counts below reflect original coverage; suite was reorganized during Phase 1):

| Section | Tests | Status |
|---------|-------|--------|
| Canonical Form | 1 | ✅ |
| Bindings & Functions | 7 | ✅ |
| Data Types | 5 | ✅ |
| Pattern Matching | 4 | ✅ |
| Module System | 5 | ✅ |
| Effects | 3 | ✅ |
| Handlers | 2 | ✅ |
| Concurrency | 1 | ✅ |
| Choreography | 2 | ✅ |
| Expressions | 16 | ✅ |
| Comments | 2 | ✅ |
| Lexer Tests | 6 | ✅ |

---

## Known Limitations

- **Implicit continuation** — implemented in Phase 4.5a. A more-indented line starting with a binary operator (`|>`, `+`, `++`, `&&`, `==`, `..`, etc.) is automatically joined to the previous line. Handled at the lexer level in `IrijLexerBase.nextLineStartsWithBinaryOp()`. Does not trigger for `-` (ambiguous with unary negation) or `/` (ambiguous with seq ops like `/?`, `/+`).
- **No AST or interpreter** — Phase 0 only validates parse correctness; the tree is an ANTLR `ParseTree`, not a custom AST.
- **No error recovery** — a single parse error stops meaningful parsing of the rest.
