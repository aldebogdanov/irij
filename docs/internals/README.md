# Irij internals

How the language works under the hood. Source-level design decisions,
code structure, and the trade-offs that shaped each subsystem.

Audience: contributors, the curious, and future-me trying to remember
why something is the way it is.

## Reading order

1. [Pipeline](pipeline.md) — source → AST → values
2. [Parser](parser.md) — ANTLR4 grammar, lexer quirks, indent tokens
3. [AST](ast.md) — node shapes, sealed-interface dispatch
4. [Interpreter](interpreter.md) — tree-walking, environments, effect stack
5. [Bytecode compiler](bytecode.md) — `ClassEmitter`, ASM, JVM mapping
6. [Effect system](effects.md) — threaded vs state-machine lowering, handlers, composition
7. [Tail-call optimization](tco.md) — self-TCO in bytecode, mutual deferral
8. [Hot redefinition](hot-redef.md) — `invokedynamic` + `MutableCallSite`
9. [Spec system](specs.md) — runtime validation, contract layers, blame
10. [Modules](modules.md) — `mod`/`use`, inlining, alias rewriting
11. [Concurrency](concurrency.md) — virtual threads, structured concurrency, SM_STACK
12. [JVM capability](jvm-capability.md) — `::: JVM`, why Java is impure
13. [Stdlib](stdlib.md) — what lives in Irij vs Java, the boundary
14. [Glossary](glossary.md) — terms used throughout
