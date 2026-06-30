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
10. [Versioning](versioning.md) — commit-count releases, publish guards, dep resolution
11. [Concurrency](concurrency.md) — virtual threads, structured concurrency, SM_STACK
12. [JVM capability](jvm-capability.md) — `::: JVM`, why Java is impure
13. [Capabilities](capabilities.md) — `cap` decls, bridging effects to host code
14. [Stdlib](stdlib.md) — what lives in Irij vs Java, the boundary
15. [nREPL](nrepl.md) — protocol, sessions, bytecode-session eval
16. [LSP](lsp.md) — `irij lsp`, capabilities, editor integration
17. [Glossary](glossary.md) — terms used throughout

Note: the tree-walking `Interpreter` class was removed in v0.6.20
(R5d). The threaded handler protocol (14c.2) had already been
removed in v0.6.13. The state-machine bytecode lowering (14c.3) is
the only execution model.

The runtime-support classes (`Values`, `Builtins`, `EffectSystem`,
`JavaInterop`, `Environment`) moved to `dev.irij.runtime` in v0.7.0
to retire the misleading package name. They are bytecode-mode
**runtime support** — `Values` (Irij value reps), `Builtins` (name
registry), `EffectSystem` (handler frame stack used by SM_STACK
bridging) and `JavaInterop`. They are referenced from
`RuntimeSupport`, not driven by an interpreter.
