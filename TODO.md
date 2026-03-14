# Irij — Implementation TODO

Source of truth: `docs/irij-lang-spec.org`

---

## Tooling — Emacs Package

- [ ] **irij-mode** — `editors/emacs/irij-mode.el`
  - [ ] Major mode for `.irj` files
  - [ ] Syntax highlighting (keywords, digraphs, literals, comments)
  - [ ] 2-space indentation enforcement
  - [ ] Comment toggling (`;;`)
  - [ ] REPL integration (send region, send buffer)

---

## Phase 0 — Grammar & Parser ✅

Everything starts from the spec. No interpreter logic yet, just parsing.

- [x] **Lexer (ANTLR4)** — `src/main/antlr/IrijLexer.g4`
  - [x] Identifiers: kebab-case, PascalCase types, `$ROLE` names
  - [x] Literals: int, float, hex, underscore separators, rationals (`2/3`), strings with `${}` interpolation, keywords (`:ok`, `:error`)
  - [x] All digraph operators (`:=`, `:!`, `<-`, `->`, `=>`, `::`, `|>`, `<|`, `>>`, `<<`, `-[`, `]>`, `~>`, `<~`, `~*>`, `~/`, `/+`, `/*`, `/#`, `/&`, `/|`, `\.`, `/?`, `/!`, `@`, `@i`, `..`, `..<`, `++`, `**`, `/=`, `<=`, `>=`, `&&`, `||`, `|`, `...`)
  - [x] `=` as map-field separator (context: inside `{}`)
  - [x] INDENT/DEDENT token generation (strict 2-space, hard error otherwise)
  - [x] Semicolons inside parenthesized expressions only
  - [x] `\` line continuation
  - [x] Comments: `;;` to end of line
  - [x] Reserved words: `fn`, `do`, `if`, `else`, `match`, `type`, `newtype`, `mod`, `use`, `pub`, `with`, `scope`, `effect`, `role`, `cap`, `handler`, `impl`, `proto`, `pre`, `post`, `law`, `contract`, `select`, `enclave`, `forall`, `par-each`, `on-failure`

- [x] **Parser (ANTLR4)** — `src/main/antlr/IrijParser.g4`
  - [x] Top-level declarations: `fn`, `type`, `newtype`, `effect`, `handler`, `cap`, `proto`, `impl`, `role`, `mod`, `use`, `pub`, `match`, `if`, `with`, `scope`
  - [x] Three fn body forms (disambiguation by first token after INDENT):
    - `(` → lambda: `(x y -> expr)`
    - `pattern =>` → match arm(s)
    - `=>` / `=> params` → imperative block
  - [x] Contracts: `pre`, `post`, `contract`/`in`/`out`
  - [x] Laws: `law name = forall x. P x`
  - [x] Expressions: pipeline, composition, arithmetic, comparison, boolean, concat, range, application
  - [x] Collection literals: `#[...]`, `#{...}`, `#(...)`, `{k= v}`
  - [x] Pattern matching: `match expr` with arms, destructuring, guards (`|`), spread (`...`)
  - [x] Record update: `{...record field= val}`
  - [x] `if`/`else` (block-level and inline expression), `with`, `scope`, `select`
  - [x] Mutable bindings: `:!`, `<-`, read by name
  - [x] Type annotations: `:: Type`, type application (`Result a e`), effect arrows `-[E1 E2]> T`
  - [x] Located types: `@$ROLE`
  - [x] Choreography: `~>`, `<~`, `~*>`, `~/`, `par-each`, `forall`, `on-failure`, `enclave`
  - [x] Unit value `()` as expression and pattern
  - [ ] Implicit continuation (more-indented line starting with binary op)

- [x] **Lexer base class** — `src/main/java/dev/irij/parser/IrijLexerBase.java`
  - [x] INDENT/DEDENT post-processing (Python-style indent stack)
  - [x] Strict 2-space enforcement
  - [x] Paren/bracket depth tracking (suppress INDENT/DEDENT inside)
  - [x] Comment-aware indent measurement
  - [x] Line continuation (`\`) support
  - [x] Trailing NEWLINE* before DEDENT handled in grammar

- [x] **Parse driver** — `src/main/java/dev/irij/parser/IrijParseDriver.java`
  - [x] Wire up lexer → parser, error reporting
  - [x] `parse(String)`, `parseFile(Path)`, `tokenize(String)` API

- [x] **Smoke tests** — `src/test/java/dev/irij/parser/ParserSmokeTest.java`
  - [x] 81 tests covering all spec sections (all passing)

---

## Phase 1 — AST & Tree-Walk Interpreter (Core)

Minimal working language: bindings, functions, pattern matching, data types.

- [ ] **AST node types** — `src/main/java/dev/irij/ast/`
  - [ ] Mirror parser rules as sealed interfaces / records
- [ ] **Tree-walk interpreter** — `src/main/java/dev/irij/interpreter/`
  - [ ] Immutable bindings (`:=`)
  - [ ] Mutable bindings (`:!`, `<-`)
  - [ ] Lambda functions, application
  - [ ] Pattern-match arms (fn body form 2)
  - [ ] Imperative blocks (`=>` / `=> params`)
  - [ ] `if`/`else`, `match` expression
  - [ ] Pipeline `|>`, composition `>>` `<<`
  - [ ] Data types: `type` with variants, `newtype`
  - [ ] Destructuring in bindings and patterns
  - [ ] Spread `...` in patterns
  - [ ] Guards in match arms
  - [ ] Record literals `{k= v}`, record update `{...r k= v}`
  - [ ] Collections: vectors `#[...]`, sets `#{...}`, tuples `#(...)`
  - [ ] Range `..` and `..<`
  - [ ] Concat `++`
  - [ ] String interpolation `${}`
  - [ ] Keywords `:ok`, `:error` etc as atoms
- [ ] **Builtins / stdlib core** — `src/main/java/dev/irij/interpreter/Builtin.java`
  - [ ] Arithmetic, comparison, boolean ops
  - [ ] Sequence ops: `@` (map), `/?` (filter), `/!` (find), `/+` `/*` `/#` `/&` `/|` (reductions), `\.` (scan), `@i` (map-indexed)
  - [ ] `head`, `tail`, `length`, `reverse`, `sort`, `concat`
  - [ ] `identity`, `const`, `flip`, `compose`
  - [ ] `print`, `println`, `to-str`, `dbg`
- [ ] **Test suite** — `src/test/java/dev/irij/interpreter/InterpreterTest.java`
  - [ ] One test per language feature, driven by Irij source strings

---

## Phase 2 — REPL & Interactive Runner

The tool to evaluate code and run it interactively. This is the critical feedback loop.

- [ ] **REPL** — `src/main/java/dev/irij/repl/IrijRepl.java`
  - [ ] JLine3 terminal with history, completion
  - [ ] Evaluate expressions, print results
  - [ ] Multi-line input (detect incomplete INDENT blocks)
  - [ ] `:type expr` — show inferred type
  - [ ] `:reset` — clear environment
  - [ ] `:load file.irj` — load and evaluate a file
  - [ ] `:quit`
- [ ] **File runner** — `src/main/java/dev/irij/cli/IrijCli.java`
  - [ ] `irij file.irj` — parse, interpret, run
  - [ ] `irij` (no args) — launch REPL
  - [ ] `--parse-only` flag — just parse, report errors (useful for grammar testing)
  - [ ] `--ast` flag — dump AST (debug)
  - [ ] Error messages with source locations (file:line:col)
- [ ] **Example programs** — `examples/`
  - [ ] `hello.irj` — hello world
  - [ ] `basics.irj` — bindings, functions, patterns
  - [ ] `collections.irj` — vectors, maps, pipelines, seq ops

---

## Phase 3 — Effects & Handlers

The universal joint. Everything interesting depends on this.

- [ ] Effect declarations (`effect E`)
- [ ] Handler definitions (`handler h :: E`)
- [ ] `with handler expr` — install handler, run block
- [ ] Effect rows in types: `-[E1 E2]>`
- [ ] Resume / continuation (one-shot)
- [ ] Handler composition (`>>`)
- [ ] Pure test handlers (mock-console pattern from spec)

---

## Phase 4 — Module System

- [ ] `mod name` declarations
- [ ] `use mod.name` / `use mod.name {specific items}`
- [ ] `pub` visibility
- [ ] Qualified names (`mod.fn`)

---

## Phase 5 — Structured Concurrency

- [ ] `scope` / `fork` / `await` as Async effects
- [ ] `scope.race`, `scope.supervised`
- [ ] `detach!` escape hatch
- [ ] Channels: `send`, `recv`, `select`
- [ ] Backed by Java virtual threads (Loom)

---

## Phase 6 — Contracts & Verification (L0–L3)

- [ ] `pre` / `post` function-level contracts
- [ ] `contract` / `in` / `out` module-boundary contracts with blame
- [ ] `proto` with `law` declarations
- [ ] Property-based test generation from laws

---

## Phase 7 — Choreographic Programming

- [ ] Located types (`@$ROLE`)
- [ ] Communication: `~>`, `<~`, `~*>`, `~/`
- [ ] Endpoint Projection (EPP)
- [ ] `forall` / `par-each` / `census`
- [ ] `on-failure` fault tolerance
- [ ] `enclave`

---

## Phase 8 — Advanced

- [ ] Capabilities (`cap`)
- [ ] Streams & Flows
- [ ] Refinement types (L4)
- [ ] Dependent types / proofs (L5)
- [ ] Content-addressed code (hash-based identity)
- [ ] JVM interop (`use java.*`, `JClass/method`)
- [ ] GraalVM Native Image

---

## Principles

1. **Spec is the source of truth.** Every feature implemented must match `docs/irij-lang-spec.org` exactly.
2. **Slow and correct.** No rushing ahead. Each phase is fully tested before moving on.
3. **Interactive from Phase 2.** The REPL is not an afterthought — it's the primary development tool.
4. **Tests first.** Write the test (Irij source → expected output) before implementing the feature.
5. **TODO.md updates.** Always update TODO.md if you confident in step correct implementation. Also possible to mark incomplete/problematic steps.
6. **Docs after milestone.** When some phase or important steps are implemented — describe everything you created in corresponding files inside `./docs` folder. I need to know how to use or/and test everything implemented.
