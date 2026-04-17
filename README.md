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
```

## Tests

```sh
./gradlew test                # 693 Java unit tests
irij test                     # 264 integration tests (tests/*.irj)
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

0.1.1 &mdash; Phases 0-8a complete.
