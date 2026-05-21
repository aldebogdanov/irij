# Irij internals

How the language works under the hood. Source-level design decisions,
code structure, and the trade-offs that shaped each subsystem.

Audience: contributors, the curious, and future-me trying to remember
why something is the way it is.

## Reading order

1. [Pipeline](pipeline.md) — source → AST → bytecode
2. [Parser](parser.md) — ANTLR4 grammar, lexer quirks, indent tokens
3. [AST](ast.md) — node shapes, sealed-interface dispatch
4. [Bytecode compiler](bytecode.md) — `ClassEmitter`, ASM, JVM mapping
5. [Effect system](effects.md) — state-machine lowering, handlers, composition
6. [Tail-call optimization](tco.md) — self-TCO in bytecode, mutual deferral
7. [Hot redefinition](hot-redef.md) — `invokedynamic` + `MutableCallSite`
8. [Spec system](specs.md) — runtime validation, contract layers, blame
9. [Modules](modules.md) — `mod`/`use`, inlining, alias rewriting
10. [Concurrency](concurrency.md) — virtual threads, structured concurrency, SM_STACK
11. [JVM capability](jvm-capability.md) — `::: JVM`, why Java is impure
12. [Stdlib](stdlib.md) — what lives in Irij vs Java, the boundary
13. [nREPL](nrepl.md) — protocol, sessions, bytecode-session eval
14. [Glossary](glossary.md) — terms used throughout

Note: the `dev.irij.interpreter` package was removed in v0.6.13.
What remains under that name (`Values`, `Builtins`, `EffectSystem`,
`JavaInterop`, `Environment`) is bytecode-mode **runtime support** —
not the interpreter. The tree-walking evaluator is gone; bytecode is
the only execution model.
