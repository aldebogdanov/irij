# Irij — Implementation TODO

Source of truth: `docs/irij-lang-spec.org`

---

## Tooling — Emacs Package

- [x] **irij-mode** — `editors/emacs/irij-mode.el`
  - [x] Major mode for `.irj` files
  - [x] Syntax highlighting (keywords, digraphs, literals, comments)
  - [x] 2-space indentation enforcement
  - [x] Comment toggling (`;;`)
  - [x] REPL integration (send region, send buffer)
- [x] **irij-nrepl** — `editors/emacs/irij-nrepl.el`
  - [x] Bencode encoder/decoder (pure Elisp, zero external deps)
  - [x] Async TCP transport (`make-network-process`, partial-frame buffering)
  - [x] Session management (`clone` op, auto-connect from `.nrepl-port`)
  - [x] `irij-nrepl-connect` — auto-reads `.nrepl-port` or prompts for port
  - [x] `irij-nrepl-eval-last-sexp` (`C-x C-e`, `C-c C-e`)
  - [x] `irij-nrepl-eval-defun` — top-level `fn`/`type`/binding (`C-c C-d`)
  - [x] `irij-nrepl-eval-buffer` (`C-c C-k`)
  - [x] `irij-nrepl-eval-region` (`C-c M-r`)
  - [x] Result display: value in minibuffer + `*irij-nrepl*` output buffer
  - [x] Hot redefinition — re-eval `fn` declaration, callers see new version

---

## Phase 0 — Grammar & Parser ✅

Everything starts from the spec. No interpreter logic yet, just parsing.

- [x] **Lexer (ANTLR4)** — `src/main/antlr/IrijLexer.g4`
  - [x] Identifiers: kebab-case, PascalCase types, `$ROLE` names
  - [x] Literals: int, float, hex, underscore separators, rationals (`2/3`), strings with `${}` interpolation, keywords (`:ok`, `:error`)
  - [x] All digraph operators (`:=`, `:!`, `<-`, `->`, `=>`, `::`, `|>`, `<|`, `>>`, `<<`, `-[`, `]>`, `~>`, `<~`, `~*>`, `~/`, `/+`, `/*`, `/#`, `/&`, `/|`, `/^`, `/$`, `/?`, `/!`, `@`, `@i`, `..`, `..<`, `++`, `**`, `/=`, `<=`, `>=`, `&&`, `||`, `|`, `...`)
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

- [x] **AST node types** — `src/main/java/dev/irij/ast/`
  - [x] Sealed interfaces / records: `Node`, `Expr` (30+ variants), `Stmt`, `Decl`, `Pattern`
  - [x] `AstBuilder` — ANTLR parse tree → AST visitor
  - [x] Collection element flattening (grammar ambiguity: `appExpr : postfixExpr+`)
  - [x] String interpolation parsing (`${}` in STRING tokens)
- [x] **Tree-walk interpreter** — `src/main/java/dev/irij/interpreter/`
  - [x] Immutable bindings (`:=`)
  - [x] Mutable bindings (`:!`, `<-`), capture-by-reference in closures
  - [x] Lambda functions, function application (juxtaposition)
  - [x] Pattern-match arms (fn body form 2)
  - [x] Imperative blocks (`=>` / `=> params`)
  - [x] `if`/`else` (block and inline), `match` expression
  - [x] Pipeline `|>` `<|`, composition `>>` `<<`
  - [x] Data types: `type` with variants (Tagged), `newtype`
  - [x] Destructuring in bindings and patterns
  - [x] Spread `...` in vector patterns
  - [x] Guards in match arms
  - [x] Record literals `{k= v}`, record update `{...r k= v}`
  - [x] Collections: vectors `#[...]`, sets `#{...}`, tuples `#(...)`
  - [x] Range `..` and `..<` (lazy iterators)
  - [x] Concat `++`
  - [x] String interpolation `${}`
  - [x] Keywords `:ok`, `:error` etc as atoms
  - [x] Partial application (currying)
  - [x] Rational arithmetic with GCD simplification
  - [x] Implicit numeric widening (Int→Float)
  - [x] Dot access on maps
  - [x] do expression
  - [x] Stub declarations (effect, handler, module, use, role, proto, impl, cap)
  - [x] Runtime errors with source locations
- [x] **Builtins / stdlib core** — `src/main/java/dev/irij/interpreter/Builtins.java`
  - [x] Arithmetic (+, -, *, /, %, **), comparison (==, /=, <, >, <=, >=), boolean (&&, ||, !)
  - [x] Sequence ops: `@` (map), `/?` (filter), `/!` (find), `/+` `/*` `/#` `/&` `/|` (reductions)
  - [x] Operator sections: `(+)`, `(-)`, `(*)`, etc. as first-class functions
  - [x] `head`, `tail`, `length`, `reverse`, `sort`, `take`, `drop`, `to-vec`
  - [x] `nth`, `last` — indexed and terminal access
  - [x] `get` — works on maps, vectors, and tuples
  - [x] `fold` — reduce with explicit initial value
  - [x] `identity`, `const`, `not`, `empty?`, `contains?`, `keys`, `vals`
  - [x] `print`, `println`, `to-str`, `dbg`
  - [x] `div`, `mod`, `abs`, `min`, `max`, `pi`, `e`
- [x] **Test suite** — `src/test/java/dev/irij/interpreter/InterpreterTest.java`
  - [x] 194 tests (64 parser + 130 interpreter, all passing)

---

## Phase 2 — REPL & Interactive Runner ✅

The tool to evaluate code and run it interactively. This is the critical feedback loop.

- [x] **REPL** — `src/main/java/dev/irij/repl/IrijRepl.java`
  - [x] JLine3 terminal with history, completion
  - [x] Evaluate expressions, print results
  - [x] Multi-line input (detect incomplete INDENT blocks)
  - [x] `:type expr` — show inferred type (stub — awaits type inference)
  - [x] `:reset` — clear environment
  - [x] `:load file.irj` — load and evaluate a file
  - [x] `:quit`
- [x] **File runner** — `src/main/java/dev/irij/cli/IrijCli.java`
  - [x] `irij file.irj` — parse, interpret, run
  - [x] `irij` (no args) — launch REPL
  - [x] `--parse-only` flag — just parse, report errors (useful for grammar testing)
  - [x] `--ast` flag — dump AST (debug)
  - [x] Error messages with source locations (file:line:col)
- [x] **Example programs** — `examples/`
  - [x] `hello.irj` — hello world
  - [x] `basics.irj` — bindings, functions, patterns
  - [x] `collections.irj` — vectors, maps, pipelines, seq ops

---

## Phase 2.5 — Interactive Development Foundation ✅

Hot redefinition, nREPL server, environment inspection.

- [x] **VarCell** — `src/main/java/dev/irij/interpreter/Environment.java`
  - [x] `VarCell` cell type for top-level rebindable bindings (Clojure-style Var)
  - [x] `defineVar(name, value)` — in-place update if VarCell exists
  - [x] `Interpreter.defineInScope()` — routes to `defineVar` at global scope
  - [x] Top-level `fn`, `type`, `:=` use VarCell; builtins remain ImmutableCell
  - [x] `<-` assignment still rejects VarCell (immutable semantics preserved)
- [x] **nREPL server** — `src/main/java/dev/irij/nrepl/`
  - [x] `Bencode.java` — bencode encoder/decoder (nREPL wire format)
  - [x] `IndirectOutputStream.java` — swappable output stream for per-eval capture
  - [x] `NReplSession.java` — session with independent interpreter + output capture
  - [x] `NReplServer.java` — TCP server, virtual threads, `.nrepl-port` file
  - [x] Supported ops: `clone`, `eval`, `describe`, `close`
  - [x] `--nrepl-server[=PORT]` CLI flag (default port 7888)
- [x] **`:env` REPL command**
  - [x] `:env` — show user-defined bindings (name, type, preview)
  - [x] `:env all` — include builtins
- [x] **Tests** — 235 total (64 parser + 137 interpreter + 14 bencode + 12 session + 1 server + 7 varCell)

---

## Phase 2.75 — Concurrency Primitives ✅

Minimal spawn/sleep for hot-redefinition demos, not the full structured concurrency system.

- [x] **`sleep` builtin** — `Builtins.java`
  - [x] `sleep 1000` → Int arg = milliseconds
  - [x] `sleep 1.5` → Float arg = seconds (× 1000)
- [x] **`spawn` builtin** — `Interpreter.installInterpreterBuiltins()`
  - [x] `spawn (-> body)` — run thunk in Java virtual thread
  - [x] Shares interpreter's global env (VarCell lookups see hot-redef)
  - [x] Errors logged to `out`, don't crash main thread
  - [x] Returns `Thread` handle (opaque, future: await/cancel)
- [x] **Values.java** — Thread display (`<thread N>`) and typeName
- [x] **`examples/hot-redef.irj`** — demo: spawn loop + redefine fn live
- [x] **`~` operator** (apply-to-rest) — lowest precedence, right-associative
  - [x] `TILDE` token in `IrijLexer.g4`
  - [x] `applyToExpr` rule in `IrijParser.g4` (right-recursive)
  - [x] Desugars to `Expr.App(fn, [rest])` in `AstBuilder`
  - [x] Emacs font-lock highlighting
- [x] **Tests** — 255 total (+6 concurrency + 3 parser tilde + 5 interpreter tilde)

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
