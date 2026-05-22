# Glossary

Terms used throughout the internals docs.

| Term | Meaning |
|---|---|
| **A-normalization** | Pass that lifts nested effect-op calls to top-level binds with fresh `$anf$N` names. Pre-classification step for SM body shaping. |
| **AMBIENT_EFFECTS** | Special sentinel `Set<String>` whose `contains()` always returns true. Pushed for top-level scripts and `with` body re-entries that should see everything. |
| **AVAILABLE_EFFECTS** | Thread-local stack of `Set<String>`; top is current effect row. Effect ops and capability-gated calls consult `.peek()` for legality. |
| **Aardvark-DNS / Netavark** | Podman's networking stack — not relevant to Irij internals; mentioned only in deploy docs. |
| **Blame** | In the spec system, the source location attributed for a spec or contract violation. Caller-side for input failures, callee-side for output failures (Findler/Felleisen 2002). |
| **BuiltinFn** | Java-implemented function value (lives in `dev.irij.runtime`, referenced from the bytecode runtime). Effect-transparent — callback runs in caller's row. |
| **CompiledHandler** | Bytecode-side handler value: `(name, effectName, clauses: Map<String, IrijFn>)`. Static fields hold handler state. |
| **CompiledComposedHandler** | `(handlers: List<CompiledHandler>)` flattened from `>>` operator. |
| **CompiledScopeHandle** | Runtime handle for `scope { fork ... }` blocks. Tracks spawned fibers + parent effect snapshots. |
| **Deep handler** | Handler whose clauses resume into the same dynamic extent as the body. Irij's only kind of handler. |
| **Direct linking** | Build mode (`--direct-linking`) where top-level fn calls compile to plain `invokestatic`. Disables hot-redef. Mirrors Clojure deploy mode. |
| **EffectSystem.STACK** | Thread-local stack of `HandlerContext` records. Walked by `dispatchLoopSMImpl` as a bridging fallback when no SM handler matches a `PerformSignal` and no SM_STACK frame matches either — relevant for fibers spawned outside any SM `with`. |
| **EffIR** | SM body shape for bodies with branching that contain effect ops. CFG of blocks with `Return`/`Perform`/`Branch`/`Jump` terminators. |
| **fireOp** | `EffectSystem.fireOp(eff, op, args)` — legacy synchronous effect-op entry kept for fibers spawned outside any SM `with`. Walks `EffectSystem.STACK`, falls through to `fireOpToSM`. |
| **fireOpToSM** | Synchronous SM-dispatch from `fireOp` — lets a fiber (running outside any SM dispatch loop) reach an inherited SM handler via `SM_STACK`. |
| **Hot redef** | Swap a fn's implementation at runtime via `MutableCallSite.setTarget`. |
| **IndentRewriter** | ANTLR4 token-stream filter that emits `INDENT`/`DEDENT` tokens around lines based on indentation. Pre-parse pass. |
| **IrijContinuation** | Concrete struct (`int state, Object[] fields, IrijFn step`) used by SM-mode bodies. Holds machine state across perform throws. |
| **IrijFn** | SAM interface `(Object[]) -> Object` representing a first-class function value. `invokeBuiltin` + `LambdaMetafactory` produce these. |
| **Lifted local** | A local variable that must survive a SM perform. Stored in `k.fields[idx]` instead of a JVM local slot. |
| **MutableCallSite** | JSR-292 (`java.lang.invoke`) call site whose target can be swapped at runtime. Used for hot-redef. |
| **nREPL** | Network REPL — Clojure-flavoured protocol. Irij hosts an nREPL server with bytecode-backed sessions (`BytecodeSession`); each connection gets a per-session classloader + namespace. |
| **OpSection** | `(+)` etc. as a first-class fn value. Lowered to `GETSTATIC RuntimeSupport.OP_ADD` etc. |
| **Perform** | An effect-op invocation. SM mode (the only execution path): throw `PerformSignal`. |
| **PerformSignal** | Pooled `RuntimeException` (stack-trace-free) carrying `(effectName, opName, args, continuation)`. Thrown by SM bodies at perform sites. |
| **runWithSM** | SM-mode entry. Allocates `IrijContinuation`, enters `dispatchLoopSM`. |
| **runWithSMNoHs** | SM dispatch with empty `hs` — used for tier-c clause bodies so their performs escape to enclosing handlers. |
| **Self-tail-call** | A recursive call where the recursive position is the body's last evaluation. Lowered to `GOTO` + arg rebind. |
| **Sequence** | SM body shape: linear list of segments separated by performs and/or inner-withs. N-state tableswitch. |
| **SingleOp** | SM body shape: exactly one top-level perform. Two states. |
| **SM** | Short for "state machine" — the 14c.3 effect-lowering style. |
| **SM_STACK** | Thread-local stack of `List<CompiledHandler>` — every active SM dispatch frame. Used for cross-frame dispatch when an inner loop doesn't match a signal. |
| **Spec** | Runtime predicate validating a value's shape. Malli-inspired. |
| **SpecContractFn** | Wrapper applied to fn values at definition time when specs are declared. Validates args on entry, return on exit. |
| **Tagged** | `Values.Tagged(constructorName, args)` — discriminated-union representation for `Maybe.Some v`, etc. |
| **TailResume** | SM-mode resume mechanism. Synthesised `resumeFn` throws `TailResume(value, target)`; the trampoline catches it (target-matching) and iterates. |
| **Tier (a)** | Clauses that don't perform — most stdlib handlers. |
| **Tier (b)** | Bodies that perform tier-a effects only. |
| **Tier (c)** | Clauses that themselves perform foreign effects (have `::: Other` rows). Compile clause body as its own SM. |
| **Trampoline** | The dispatch loop pattern: catch a control-flow exception, update state, iterate. Used by SM-mode resume to avoid stack growth. |
| **Vector** | Irij's primary sequential collection. `#[1 2 3]`. Backed by `Values.IrijVector` (wraps `List<Object>`). |
| **vthread** | JVM virtual thread (`Thread.startVirtualThread`). Cheap (~1 KB), block-friendly. Underlies all Irij concurrency. |
| **`with`** | Effect-handler block. `with handler body` runs body with handler installed for the body's dynamic extent. |
