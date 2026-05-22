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
                ┌────────────────────┐
                │  EffectRowChecker  │   static effect-row lint
                └────────────────────┘
                         │
                         ▼
                  ┌─────────────┐
                  │ ClassEmitter│   ASM bytecode (state-machine effect lowering)
                  └─────────────┘
                         │ bytes
                         ▼
                  ┌─────────────┐
                  │  Shadow JAR │   for `irij build`; in-process for run/repl/test
                  └─────────────┘
                         │
                         ▼
                  `java -jar` / runtime
```

## One back-end since v0.6.13

Bytecode is the only execution path. The tree-walking interpreter
(`dev.irij.interpreter.Interpreter`) was removed in v0.6.20 / R5d;
the threaded handler protocol (14c.2) was removed in v0.6.13. The
state-machine bytecode lowering (14c.3) handles every `with` —
single execution model, single contract.

The runtime-support shapes the emitter depends on (`Values`,
`Builtins`, `EffectSystem`, `JavaInterop`, `Environment`) live in
`dev.irij.runtime` (renamed from `dev.irij.interpreter` in v0.7.0
once it became clear the old name was misleading). They are *not*
an interpreter.

## File / package map

| Package | Role |
|---|---|
| `dev.irij.parser` | ANTLR4 lexer + indent rewriter |
| `dev.irij.ast` | AST node definitions + `AstBuilder` |
| `dev.irij.runtime` | Runtime value reps, builtin registry, effect-system frame stack |
| `dev.irij.compiler` | `ClassEmitter` + `RuntimeSupport` + `IrijCompiler` + `EffectRowChecker` |
| `dev.irij.cli` | `irij run/build/repl/test/install` commands |
| `dev.irij.nrepl` | nREPL server (bytecode-backed via `BytecodeSession`) |

## Where time goes

For a typical `.irj` script:

| Phase | % | Notes |
|---|---|---|
| Lex + parse | ~5% | ANTLR4 in interpreted mode |
| AST build | ~3% | |
| Module inline | ~10% | every `use` parses the imported module too |
| Effect-row lint | ~5% | |
| Bytecode emit | ~75% | one ASM pass; JIT warm-up afterwards |

For deploy (`irij build`), parse+emit happens once at build time; runtime
is just `java -jar`. JVM startup dominates anything ≤ 100 ms.

## Why this shape

- **AST → bytecode directly (no middle IR):** small project, JIT does
  CSE / escape analysis for us. The two SM lowering shapes (Sequence
  and EffIR) act as an implicit local IR inside `ClassEmitter`.
- **Module inlining (not separate compilation):** avoids cross-class
  linkage at runtime; the whole program is one JVM class. Trade-off:
  no incremental compilation; rebuild is fast enough.
- **ANTLR4 + indent rewriter (no hand-written lexer):** indent-sensitive
  grammars are painful to hand-roll; ANTLR4 with a custom token-stream
  filter handles it cleanly.
- **Static effect-row enforcement before emit:** every effect violation
  fails at compile time. The runtime backstop (`checkPerformEffect`)
  catches only what static analysis can't see — dynamically-typed
  callback dispatch through `RT.callAny`.
