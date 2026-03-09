# Irij — TODO

## Completed (Phase 0)

- [x] ANTLR 4 grammar (lexer + parser)
- [x] Tree-walking interpreter (IrijInterpreter)
- [x] Standard library (60+ builtins)
- [x] Loop/recur with trampolining
- [x] Type declarations → constructors (sum types, records)
- [x] Generalized dot access for maps/records
- [x] String interpolation with expressions
- [x] Function composition (`>>`, `<<`)
- [x] Module system (`use std.math`, user modules)
- [x] Warning-only type checker
- [x] REPL with JLine3
- [x] Purity enforcement (`--strict`, `--warn-impure`, `--allow-impure`)

## Completed (Phase 0.5)

- [x] Tuple pattern matching
- [x] Static exhaustiveness checking (constructors, booleans, wildcards)
- [x] Guard clauses (working + tested)
- [x] Multi-line handler clauses (grammar fix)
- [x] Handler state (`state :! initial`, mutable within `with` block)
- [x] Explicit `resume` in effect handlers
- [x] Effect row validation (warn on undeclared effects)
- [x] Self-recursive tail call optimization (trampolining)
- [x] Tail position propagation (if/match/do/function body)

## Completed (Phase 0.6)

- [x] Function parameter binding via match arms (`callUserFunction` → `evalFnMatchArms`)
- [x] Lambda-body functions auto-apply call args
- [x] NEWLINE after DEDENT in lexer (statements after match/with/if blocks)
- [x] Float multiplication/division/modulo (was only Long)
- [x] Power operator `**` implementation
- [x] Comprehensive showcase example (`examples/showcase.irj`)

## In Progress

### Interpreter
- [ ] `@Tailrec` annotation for compile-time tail-call verification
- [ ] `false` displays as `()` via `to-str` (boolean coercion issue)
- [ ] `pi` and `e` are thunks, need `(pi)` not `pi` to get value

### Type System
- [ ] Type inference through bindings (track variable types across scope)
- [ ] Function return type inference
- [ ] Unused binding warnings
- [ ] Full Hindley-Milner inference

### Effect System
- [ ] Multi-shot continuations (CPS transform or Loom)
- [ ] Handler composition (`handler1 >> handler2`)
- [ ] Effect row polymorphism
- [ ] Capabilities (`cap` declarations)
- [ ] Structured concurrency (`scope`, `fork`, `await`, `race`)

### Module System
- [ ] `pub` visibility for exported declarations
- [ ] Qualified access (`Module.function`)
- [ ] Circular dependency detection

### Language Features
- [ ] Or-patterns in match (`Red | Blue => ...`)
- [ ] As-patterns (`x@(Some v)`)
- [ ] Lazy streams / reactive flows
- [ ] JVM interop (call Java, implement interfaces)
- [ ] `try`/`catch` at language level

### Tooling
- [ ] JVM bytecode emitter (ASM 9.x)
- [ ] nREPL-compatible protocol
- [ ] LSP server
- [ ] Formatter (dense ↔ S-expr conversion)
- [ ] Source-mapped error messages with stack traces

## Phase Roadmap

| Phase | Focus | Status |
|-------|-------|--------|
| 0 | Foundation — grammar, interpreter, REPL | Done |
| 0.5 | Deepening — patterns, effects, TCO | Done |
| 1 | Core Language — effects compilation, data types, modules, interop | Next |
| 2 | Distinctive — gradual typing, choreography, streams, LSP | Planned |
| 3 | Production — optimizer, package manager, documentation | Future |
