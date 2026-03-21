# Irij — Agent Instructions

## What is this?

Irij (ℑ) is a token-efficient, effect-oriented programming language targeting the JVM. The language specification lives in `docs/irij-lang-spec.org` — that is the single source of truth for all syntax and semantics.

## Project layout

```
docs/irij-lang-spec.org        — language specification (READ THIS FIRST)
TODO.md                        — phased implementation roadmap
build.gradle.kts               — Gradle build (Java 25, ANTLR4, JUnit 5)
src/main/antlr/                — ANTLR4 grammar files (.g4)
src/main/java/dev/irij/        — implementation
src/test/java/dev/irij/        — tests (372 Java tests)
src/main/resources/std/        — stdlib modules (.irj): math, text, collection, func, convert, test
examples/                      — example .irj programs
tests/                         — Irij-native stdlib test suites (89 tests via std.test)
editors/emacs/                 — irij-mode.el (syntax + REPL) + irij-nrepl.el (nREPL client)
```

## Key rules

1. **The spec is the source of truth.** If the spec says X and the code does Y, the code is wrong. Do not invent syntax or semantics not in the spec.
2. **Strict 2-space indentation.** Irij enforces exactly 2 spaces per indent level. The lexer must hard-error on anything else.
3. **Dense ASCII digraphs.** All built-in operators are 1-2 char ASCII. See §1.3.2 of the spec for the full table.
4. **Three fn body forms.** Disambiguated by first token after indent: `(` = lambda, `pattern =>` = match arm, `=>` alone/with params = imperative block.
5. **Effects are the universal joint.** All side effects, concurrency, IO, and choreography are algebraic effects with handlers.
6. **Test-driven.** Write the test (Irij source string → expected output) before implementing a feature.
7. **Slow but confident.** It's better to do it slowly, but with full confidence that everything is correct. Run tests, check code in irij-nrepl. Better many times to iterate but do correct, then return later to fix.
8. **TODO.md updates.** Always update TODO.md if you confident in step correct implementation. Also possible to mark incomplete/problematic steps.
9. **Docs after milestone.** When some phase or important steps are implemented - describe everything you created in corresponding files inside ./docs folder (including architecture.html). I need to know how to use or/and test everything implemented.
10. **Wipe old things.** Completely purge any mentions of removed design patterns. If something changes, then spec, docs and examples must show like it always was so.

## Current implementation status

- **Phase 0**: Grammar & parser (ANTLR4, Python-style INDENT/DEDENT, strict 2-space)
- **Phase 1**: AST & tree-walk interpreter (bindings, functions, pattern matching, data types, builtins)
- **Phase 2**: REPL (JLine3), CLI runner, examples, Emacs irij-mode
- **Phase 2.5**: VarCell hot-redefinition, nREPL server, Emacs nREPL client
- **Phase 2.75**: `spawn`/`sleep` concurrency primitives, `~` apply-to-rest operator
- **Phase 3a/3b**: Algebraic effects & handlers (with/resume/abort, handler state, composition, on-failure)
- **Phase 4**: Module system (`mod`/`use`/`pub`), ~35 new builtins, 6 stdlib modules in Irij
- **Phase 4.5a**: Vector/tuple destructuring in bindings, implicit continuation (multi-line pipelines)

## Build & test

```sh
./gradlew test                                    # run all 372 Java tests
./gradlew run --args="tests/run-all.irj"          # run Irij stdlib tests (35 tests)
./gradlew run --args="file.irj"                   # run a file
./gradlew run --args="--parse-only file.irj"      # parse only
./gradlew run --args="--nrepl-server"             # start nREPL server (port 7888)
```

## Style

- Java 25, records and sealed interfaces for AST nodes
- Package structure: `dev.irij.parser`, `dev.irij.ast`, `dev.irij.interpreter`, `dev.irij.repl`, `dev.irij.cli`
- Test class per component: `ParserSmokeTest`, `InterpreterTest`, etc.
- Test helper: `run(String source)` captures stdout and returns it for assertion
