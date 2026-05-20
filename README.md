# Irij

Token-efficient effect-oriented language.

Runs on JVM (Java 21+). Tree-walk interpreter with nREPL server.

## Quick Start

```sh
./gradlew shadowJar
java -jar build/libs/irij.jar examples/hello.irj
```

Or install to PATH:

```sh
./gradlew install    # installs to ~/.local/bin/irij
irij examples/hello.irj
```

## Usage

```sh
irij                          # launch REPL
irij file.irj                 # run a file
irij test                     # run tests/ directory
irij --nrepl-server            # start nREPL (port 7888)
irij --nrepl-server=9000       # nREPL on custom port
irij --parse-only file.irj    # check syntax only
irij --ast file.irj            # dump AST
```

## Examples

```sh
irij examples/hello.irj       # hello world
irij examples/basics.irj      # bindings, functions, patterns
irij examples/collections.irj # vectors, maps, pipelines
irij examples/effects.irj     # algebraic effects deep dive
irij examples/walkthrough.irj # full language tour (all phases)
irij examples/compiled.irj    # bytecode compiler MVP demo (see: irij compile)
```

## Tests

```sh
./gradlew test                # 770 Java unit tests (incl. 54 bytecode-compiler tests)
irij test                     # 299 integration tests (tests/*.irj)
```

## Language Features

- **Specs** &mdash; `spec` declares structure with certification tags; runtime validation at boundaries
- **Mandatory effect tracking** &mdash; `fn name ::: Console` declares effects; unannotated = pure
- **Algebraic effects & handlers** &mdash; `effect`, `handler`, `with`, `resume`, `on-failure`
- **Contracts** &mdash; `pre`, `post` (function-level), `in`, `out` (module-boundary with blame)
- **Law verification** &mdash; QuickCheck-style property testing: `law name = forall x. P x`
- **Protocols** &mdash; type-dispatched methods: `proto`, `impl`
- **Structured concurrency** &mdash; `scope`, `fork`, `par`, `race`, `timeout`
- **Pattern matching** &mdash; destructuring, guards, spread, ADTs
- **Module system** &mdash; `mod`, `use`, `pub`, qualified names
- **Java interop** &mdash; Clojure-style `Class/method`, `obj.method` (auto-reflection, java.lang auto-imported)
- **Bytecode compiler (experimental, 14a+14b+14c.2+14c.2b+14d)** &mdash; `irij compile file.irj -o out.jar` emits a runnable JVM jar (subset: literals, arith, `if`, `:=`, `fn`, `match`, pattern dispatch, collections, ADT constructors, first-class lambdas with captures, rest params, protocol dispatch, algebraic effects with one-shot `resume` and `on-failure`, modules via compile-time source inlining (`use std.X`), Java interop (`Class/member`, `obj.method` via shared `JavaInterop`), structured concurrency (`scope`/`scope.race`/`scope.supervised`, `spawn`/`par`/`race`/`timeout`/`await` on virtual threads, fibers inherit effect stack); see `docs/phase-14-bytecode.md`)
- **nREPL** &mdash; Emacs integration via `editors/emacs/irij-nrepl.el`

## Documentation

All docs live in `docs/`:

- `irij-lang-spec.org` &mdash; full language specification (source of truth)
- `phase-*.md` &mdash; implementation notes per phase
- `architecture.md` &mdash; file map and code structure
- `parser-gotchas.md` &mdash; known parser edge cases

## Project Structure

```
src/main/antlr/          # ANTLR4 grammar (IrijLexer.g4, IrijParser.g4)
src/main/java/dev/irij/
  ast/                   # AST nodes, AstBuilder
  interpreter/           # Interpreter, Values, Environment, Builtins
  compiler/              # Bytecode compiler (ASM, experimental — Phase 14a)
  parser/                # Parse driver, lexer base
  cli/                   # CLI entry point
  repl/                  # JLine REPL
  nrepl/                 # nREPL server (bencode protocol)
  mcp/                   # MCP server for Claude Code
src/main/resources/std/  # Standard library (Irij source)
tests/                   # Integration tests (test-*.irj)
examples/                # Example programs
editors/emacs/           # irij-mode.el, irij-nrepl.el
docs/                    # Specification and phase docs
```

## Version

0.6.3 &mdash; R4: `irij build` default mode flipped from `interp` to `bytecode-sm`. The interpreter is now reachable via the explicit `--mode=interp` opt-in during the v0.6.x transition. Builtin-vs-user-fn / builtin-vs-effect-op name conflicts now resolve in the documented order **user fn > effect op > builtin** (so `div` resolves to vrata.html's element builder before the math primitive; raw Math.log accessible via `java.lang.Math/log`). Plus 0.6.2's last builtins.
