# Archive — historical phase plans

These were per-phase design + checklist docs written during
implementation. They reflect the state of thinking at the time, not
the current implementation. Kept for context, not maintained.

For the current view of the system, read `docs/internals/` (sorted
by topic, not phase) and `docs/STATE-YYYY-MM-DD.md` for the latest
honest audit.

Phase index (when each phase landed):

| Phase | Topic | When | Live doc |
|---|---|---|---|
| 0 | Grammar & parser | v0.1 | `internals/parser.md` |
| 1 | AST + interpreter | v0.1 | `internals/interpreter.md`, `internals/ast.md` |
| 2 | REPL + CLI | v0.1 | — |
| 2.5 | Interactive dev (VarCell, nREPL, Emacs) | v0.1 | `internals/nrepl.md` |
| 3 | Algebraic effects | v0.2 | `internals/effects.md` |
| 4 | Module system + stdlib | v0.2 | `internals/modules.md`, `internals/stdlib.md` |
| 4.5b | Protocols | v0.2 | — |
| 5 | Structured concurrency | v0.2 | `internals/concurrency.md` |
| 6a | Pre/post contracts | v0.3 | — |
| 6bc | Module contracts + laws | v0.3 | — |
| 7a | Effect rows | v0.3 | `internals/effects.md`, `internals/specs.md` |
| 8a | Spec declarations | v0.3 | `internals/specs.md` |
| 8b | Composite + runtime specs | v0.3 | `internals/specs.md` |
| 9 | Package management | v0.3 | — |
| 11 | Database | v0.3 | — |
| 12b | nREPL Playground | v0.4 | `internals/nrepl.md` |
| 14 | Bytecode compiler | v0.4 → v0.5 | `internals/bytecode.md`, `internals/hot-redef.md`, `internals/tco.md` |
| 14c.3 | State-machine effects | v0.4 | `internals/bytecode.md`, `internals/effects.md` |
