# Pipeline

How a `.irj` source file becomes a running program.

```
                  ┌─────────────┐
   source.irj ─→  │  Lexer      │   ANTLR4 + IndentRewriter (indent → INDENT/DEDENT tokens)
                  └─────────────┘
                         │ stream of tokens
                         ▼
                  ┌─────────────┐
                  │  Parser     │   ANTLR4-generated; emits parse tree
                  └─────────────┘
                         │ ParseTree
                         ▼
                  ┌─────────────┐
                  │  AstBuilder │   walks the parse tree, produces typed AST
                  └─────────────┘
                         │ List<Decl>
                         ▼
                  ┌─────────────┐
                  │  Inliner    │   `ModuleInliner` resolves `use mod.X`,
                  │             │   inlines pub decls, rewrites alias refs
                  └─────────────┘
                         │ Combined module tree
                         ▼
              ┌─────────────────────┐
              │      fork           │
              ▼                     ▼
        ┌─────────┐          ┌─────────────┐
        │ Interp  │          │ ClassEmitter│
        └─────────┘          └─────────────┘
              │                     │ ASM bytecode
              │                     ▼
              │              ┌─────────────┐
              │              │  Shadow JAR │
              │              └─────────────┘
              ▼                     │
         output                     ▼
                              `java -jar` / runtime
```

## Two backends from one AST

The AST is the contract between front-end (parser+inliner) and back-ends
(interpreter, bytecode emitter). Both consume the same `List<Decl>`.

This split matters: it forces every language feature to be expressible
*at the AST level*. A feature you can only do "in the interpreter" can't
be compiled — and vice versa. Throughout `ClassEmitter` you'll see hard
errors like `"MVP: unsupported expression: X"` — those are the places the
bytecode path hasn't grown a representation yet. The interpreter path is
the older and more complete one.

## File / package map

| Package | Role |
|---|---|
| `dev.irij.parser` | ANTLR4 lexer + indent rewriter |
| `dev.irij.ast` | AST node definitions + `AstBuilder` |
| `dev.irij.interpreter` | Tree-walking interp + builtins + effect system |
| `dev.irij.compiler` | `ClassEmitter` + `RuntimeSupport` + `IrijCompiler` |
| `dev.irij.cli` | `irij run/build/repl/test` commands |
| `dev.irij.nrepl` | nREPL server (interpreter-backed) |

## Where time goes

For a typical `.irj` script:

| Phase | % | Notes |
|---|---|---|
| Lex + parse | ~5% | ANTLR4 in interpreted mode |
| AST build | ~3% | |
| Module inline | ~10% | every `use` parses the imported module too |
| Interp / emit | ~80% | depends on backend; emit caches via shadow JAR |

For deploy (`irij build`), parse+emit happens once at build time; runtime
is just `java -jar`. JVM startup dominates anything ≤ 100 ms.

## Why this shape

- **One AST, two back-ends:** keeps feature semantics single-sourced.
  Adding effect-rows, specs, etc. happens at AST level, not "per
  back-end."
- **Module inlining (not separate compilation):** avoids cross-class
  linkage at runtime; the whole program is one JVM class. Trade-off:
  no incremental compilation; rebuild is fast enough.
- **ANTLR4 + indent rewriter (no hand-written lexer):** indent-sensitive
  grammars are painful to hand-roll; ANTLR4 with a custom token-stream
  filter handles it cleanly.
- **No middle IR:** AST → bytecode directly. Skipping an IR means some
  optimisations (CSE, escape analysis) we get from the JIT instead of
  doing ourselves. Good trade for a small-team project.
