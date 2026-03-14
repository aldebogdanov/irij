# Irij — Agent Instructions

## What is this?

Irij (ℑ) is an AI-first, effect-oriented programming language targeting the JVM. The language specification lives in `docs/irij-lang-spec.org` — that is the single source of truth for all syntax and semantics.

## Project layout

```
docs/irij-lang-spec.org    — language specification (READ THIS FIRST)
TODO.md                    — phased implementation roadmap
build.gradle.kts           — Gradle build (Java 25, ANTLR4, JUnit 5)
src/main/antlr/            — ANTLR4 grammar files (.g4)
src/main/java/dev/irij/    — implementation
src/test/java/dev/irij/    — tests
examples/                  — example .irj programs
```

## Key rules

1. **The spec is the source of truth.** If the spec says X and the code does Y, the code is wrong. Do not invent syntax or semantics not in the spec.
2. **Strict 2-space indentation.** Irij enforces exactly 2 spaces per indent level. The lexer must hard-error on anything else.
3. **Dense ASCII digraphs.** All built-in operators are 1-2 char ASCII. See §1.3.2 of the spec for the full table.
4. **Three fn body forms.** Disambiguated by first token after indent: `(` = lambda, `pattern =>` = match arm, `=>` alone/with params = imperative block.
5. **Effects are the universal joint.** All side effects, concurrency, IO, and choreography are algebraic effects with handlers.
6. **Test-driven.** Write the test (Irij source string → expected output) before implementing a feature.
7. **Slow but confident.** Everything must be covered by tests. Run suitable tests often and all tests for regression testing at milestones. Do not start next step if not everything in current one is proven correct.
8. **TODO.md updates.** Always update TODO.md if you confident in step correct implementation. Also possible to mark incomplete/problematic steps.
9. **Docs after milestone.** When some phase or important steps are implemented - describe everything you created in corresponding files inside ./docs folder. I need to know how to use or/and test everything implemented.

## Build & test

```sh
./gradlew test                                    # run all tests
./gradlew run --args="file.irj"                   # run a file
./gradlew run --args="--parse-only file.irj"      # parse only (when implemented)
```

## Style

- Java 25, records and sealed interfaces for AST nodes
- Package structure: `dev.irij.parser`, `dev.irij.ast`, `dev.irij.interpreter`, `dev.irij.repl`, `dev.irij.cli`
- Test class per component: `ParserSmokeTest`, `InterpreterTest`, etc.
- Test helper: `run(String source)` captures stdout and returns it for assertion
