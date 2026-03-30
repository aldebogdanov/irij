# Phase 3 — Algebraic Effects & Handlers

## Executive Summary

Implement algebraic effects with **explicit resume** and **one-shot continuations** using
Java virtual threads + `SynchronousQueue` for the continuation mechanism. This avoids CPS
transforms entirely — the tree-walk interpreter's recursive `eval()` naturally provides the
call stack, and virtual threads provide the ability to pause/resume computations.

## Semantic Decisions (Agreed)

- **Explicit resume**: Handler arms must call `resume v` to continue the paused body.
  If a handler arm does NOT call `resume`, the body thread is aborted and the arm's return
  value becomes the `with` block's result.
- **One-shot**: `resume` can only be called once per handler arm invocation. Enforced via `AtomicBoolean`.
- **Deep handlers**: After `resume` returns, the same handler continues to intercept
  subsequent effect operations from the body (handled by recursive `runHandlerLoop`).

## Architecture

### Continuation Mechanism

```
with handler
  body        ← runs in a VirtualThread
```

1. `execWith()` evaluates handler → gets `HandlerValue`
2. Creates a `SynchronousQueue<EffectMessage>` (opChannel) for body→handler communication
3. Creates a `HandlerContext` and pushes it onto the body thread's effect handler stack
4. Starts body in a VirtualThread, main thread enters `runHandlerLoop()`

**Handler Loop** (runs on the calling thread):
```
loop:
  msg = opChannel.take()
  switch msg:
    Done(value)     → return value (with block result)
    Err(throwable)  → rethrow as IrijRuntimeError
    Op(name, args, resumeChannel) →
      find handler clause for 'name'
      bind params in arm env
      inject 'resume' as BuiltinFn into arm env
      eval arm body
      if resume was called → armBody already ran recursive handler loop
        → the recursive loop consumed Done/further ops
        → return armResult (handler arm's return value = with block result)
      if resume NOT called → abort body thread, return armResult
```

**Resume implementation** (inside handler arm):
```
resume(v):
  assert one-shot (AtomicBoolean)
  resumeChannel.put(v)            // unblock body thread
  return runHandlerLoop(...)      // handle further ops recursively
                                  // returns when body hits Done
```

**Body thread** (virtual thread):
```
push HandlerContext onto ThreadLocal stack
try:
  result = evalStmtListReturn(body, env)
  opChannel.put(Done(result))
catch IrijRuntimeError e:
  opChannel.put(Err(e))
catch InterruptedException:
  // aborted by handler, exit quietly
```

**Effect operation dispatch** (when body calls e.g. `print "hello"`):
```
print is registered as a BuiltinFn with arity -1 (variadic):
  scan ThreadLocal handler stack for matching effect
  create resumeChannel = SynchronousQueue<Object>
  opChannel.put(Op("print", args, resumeChannel))
  return resumeChannel.take()     // blocks until handler resumes
```

### Nested Handlers

```
with h1 :: Console
  with h2 :: FileIO      ← this "with" runs inside h1's body thread
    print "hello"         ← finds Console handler (h1) on stack
    read-file "x"         ← finds FileIO handler (h2) on stack
```

Each `with` starts a new virtual thread and handler loop. The body thread's handler
stack is COPIED from the parent thread and the new handler is pushed on top. This means:
- Inner `with` blocks naturally nest
- The stack is scanned from top (innermost handler) to bottom (outermost)
- Each handler's opChannel connects to the thread running ITS handler loop

### Thread Relationships (nested example)

```
Main Thread          VThread1 (h1 body)     VThread2 (h2 body)
│                    │                       │
├─ execWith(h1)      │                       │
│  └─ handler loop   ├─ execWith(h2)         │
│     on opCh1       │  └─ handler loop      ├─ print "hello"
│                    │     on opCh2          │  → stack: [h1, h2]
│  ← Op from opCh1  │                       │  → h1 match, put on opCh1
│  handle print arm  │                       │  → block on resumeCh
│  resume() →        │                       │
│  put to resumeCh   │                       ← resumed
│  runHandlerLoop    │                       │
│  recursively...    │                       │
```

## Sub-phases

### Phase 3a: Core Infrastructure + Simple Effects (non-resuming)

The simplest working case: effect declarations, handler values, `with` blocks where
handler arms do NOT call resume (abort semantics = pure interceptors).

**New files:**
- `src/main/java/dev/irij/interpreter/EffectSystem.java`

**Modified files:**
- `src/main/java/dev/irij/ast/Decl.java` — extend `HandlerDecl` with `stateBindings` field
- `src/main/java/dev/irij/ast/AstBuilder.java` — process binding clauses in handlerDecl
- `src/main/java/dev/irij/interpreter/Values.java` — add `HandlerValue`, `EffectDescriptor`
- `src/main/java/dev/irij/interpreter/Interpreter.java` — implement `evalEffectDecl`, `evalHandlerDecl`, `execWith`
- `src/test/java/dev/irij/interpreter/InterpreterTest.java` — new `Effects` nested class

**Steps:**

1. **Create `EffectSystem.java`** with:
   - `ThreadLocal<Deque<HandlerContext>> STACK` — handler stack
   - `record HandlerContext(String effectName, HandlerValue handler, SynchronousQueue<EffectMessage> opChannel)`
   - `sealed interface EffectMessage` with cases:
     - `record Done(Object value)`
     - `record Err(Throwable error)`
     - `record Op(String opName, List<Object> args, SynchronousQueue<Object> resumeChannel)`
   - `static Object fireOp(String effectName, String opName, List<Object> args)` — scan stack, put Op, block on resume

2. **Add runtime value types** to `Values.java`:
   - `record EffectDescriptor(String name, List<String> ops)` — registered effect info
   - `record HandlerValue(String name, String effectName, Map<String, HandlerClause> clauseMap, Environment closureEnv)` — first-class handler value
     - `clauseMap` maps op name → `Decl.HandlerClause`

3. **Extend `Decl.HandlerDecl`** in `Decl.java`:
   - Add `List<Stmt> stateBindings` field (4th positional arg, before `loc`)
   - `HandlerDecl(String name, String effectName, List<HandlerClause> clauses, List<Stmt> stateBindings, SourceLoc loc)`

4. **Update `AstBuilder.visitHandlerDecl`**:
   - Process `binding` alternative in `handlerClause` → collect into stateBindings list
   - Pass stateBindings to HandlerDecl constructor

5. **Implement `evalEffectDecl`** in Interpreter:
   - Register the EffectDescriptor in the environment
   - For each op in the effect, register a function in the env:
     ```java
     // e.g., for effect Console with op "print"
     globalEnv.define("print", new BuiltinFn("print", -1, args -> {
         return EffectSystem.fireOp("Console", "print", args);
     }));
     ```
   - Arity `-1` = variadic (type signatures not checked yet)
   - **Key**: these functions only work inside a `with` block that handles the effect

6. **Implement `evalHandlerDecl`** in Interpreter:
   - Create a `HandlerValue` as a first-class value
   - Execute state bindings (`:!` lines) into a closure environment
   - Store the HandlerValue in the environment via `defineInScope`

7. **Implement `execWith`** in Interpreter:
   - Evaluate handler expression → get `HandlerValue`
   - Look up the corresponding `EffectDescriptor` to validate
   - Create `opChannel` (`SynchronousQueue<EffectMessage>`)
   - Create `HandlerContext`
   - Start body in virtual thread:
     - Copy parent's handler stack
     - Push new HandlerContext
     - Execute body statements
     - Put `Done(result)` or `Err(error)` on opChannel
   - Call `runHandlerLoop` on the current thread

8. **Implement `runHandlerLoop`** in Interpreter:
   - Take from opChannel
   - On `Done(v)` → return v
   - On `Err(t)` → handle via on-failure or rethrow
   - On `Op(name, args, resumeCh)` →
     - Look up handler clause
     - Create arm environment (child of handler's closureEnv)
     - Bind params
     - Inject `resume` as BuiltinFn (one-shot, calls resumeCh.put + recursive runHandlerLoop)
     - Eval arm body
     - If resume was called: return armResult
     - If resume NOT called: interrupt body thread, return armResult

**Tests for 3a:**
- Effect declaration registers op functions
- Handler declaration creates HandlerValue
- `with handler` runs body
- Effect op inside `with` dispatches to handler arm
- Handler arm without resume aborts body (simple exception-like pattern)
- Handler arm with resume continues body
- Resume returns body's final value to handler arm
- Multiple effect ops in sequence (each resumed)
- Nested `with` blocks (inner handler takes precedence for its effect)
- Unhandled effect error (no handler on stack)
- One-shot enforcement (double resume throws error)

### Phase 3b: Handler-Local State

Handler arms can read/write mutable state declared with `:!` in the handler body.

**Steps:**

1. The AstBuilder change from 3a already collects `stateBindings`
2. In `evalHandlerDecl`: execute state bindings into handler's closure env
   - `:!` bindings create MutableCells in the closureEnv
3. Handler arm bodies get `closureEnv.child()`, so they can read/write state via parent chain
4. State persists across handler arm invocations (same closureEnv)

**Tests for 3b:**
- Handler with `:!` state — state accessible from arm
- State mutation across multiple handler arm invocations
- `mock-console` pattern: print appends to list, read-line pops from list
- Handler state isolated between different `with` blocks

### Phase 3c: Pure Test Handlers (Integration)

Full integration test of the mock-console pattern from the spec.

**Tests for 3c:**
- Full mock-console test: declare Console effect, mock handler, run greet function,
  verify handler state contains expected output
- Handler dot-access: `handler.field` reads from handler's closureEnv (for inspection after with block)

### Phase 3d: on-failure Clause

The `with` block's `on-failure` clause runs when the body raises an unhandled exception.

**Steps:**

1. In `runHandlerLoop`, when `Err(t)` is received:
   - If `on-failure` stmts exist, execute them in a child env
   - Otherwise, rethrow

**Tests for 3d:**
- `with handler` body throws → on-failure runs
- on-failure not triggered on success
- on-failure can access handler state

### Phase 3e: Handler Composition (`>>`)

`with (h1 >> h2)` is syntactic sugar for nested `with` blocks.

**Steps:**

1. Add `ComposedHandler` value type (or reuse `ComposedFn`)
2. When `>>` is applied to two HandlerValues, create a ComposedHandler
3. In `execWith`, if handler is ComposedHandler, decompose into nested with blocks

**Tests for 3e:**
- `with (h1 >> h2)` handles effects from both h1 and h2
- Three handlers composed

---

## Key Implementation Details

### Effect Op Registration — Shadowing Concern

When `effect Console` declares `print`, it registers a `print` function in the env.
This SHADOWS the existing `println`/`print` builtins. This is intentional: inside a
`with console-handler` block, `print` means "perform the Console effect", not the builtin.

But outside a `with` block, calling the shadowed `print` will fail with "unhandled effect".
This means: once you declare `effect Console` with a `print` op, the builtin `print` is
effectively replaced. The user must use `println` for direct output, or always use a handler.

**Mitigation**: We could namespace effect ops (e.g., `Console.print`) but the spec doesn't
show this. For Phase 3, we'll accept shadowing and document it. The builtin is `println`,
not `print`, so this specific case is fine. But for `read-line` there's no builtin conflict.

### `execWith` Return Value

Currently `execWith` returns void (via `exec()` which returns void). But `with` should
return a value (the handler loop's result). We need:
- Change `execWith` to return `Object`
- Since `exec()` returns void, the `with` result is only usable at the declaration level
  (`WithDecl` in `execDecl`), where we can yield it
- OR: make `with` an expression (future consideration)

For now: `execWith` returns Object internally. `exec()` case for `Stmt.With` calls it but
discards the return. `execDecl()` case for `WithDecl` can yield the result.

### Thread Safety

- `EffectSystem.STACK` is `ThreadLocal` — each thread has its own handler stack
- `SynchronousQueue` is thread-safe by design
- Handler arm execution happens on the handler's thread (not the body's thread)
- `Environment.bindings` uses plain HashMap — safe because:
  - The body thread only reads the global env
  - Handler arm creates child envs on its own thread
  - State bindings use MutableCell which is single-threaded access (handler arm)

### `resume` Implementation Detail

```java
var resumed = new AtomicBoolean(false);
var resumeFn = new BuiltinFn("resume", 1, args -> {
    if (!resumed.compareAndSet(false, true)) {
        throw new IrijRuntimeError("resume called twice (one-shot continuation)");
    }
    try {
        resumeChannel.put(args.get(0));           // unblock body
        return runHandlerLoop(handler, opChannel); // handle further ops
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IrijRuntimeError("Interrupted during resume");
    }
});
armEnv.define("resume", resumeFn);
```

After `resume(v)`:
1. Body thread unblocks, continues execution
2. `runHandlerLoop` on handler thread waits for next message
3. If body does another effect op → handler loop dispatches again (recursive)
4. If body finishes → `Done(result)` → `runHandlerLoop` returns result
5. `resume()` returns that result to the handler arm
6. Handler arm can transform/use it
7. `armResult` = handler arm's final return value = `with` block result

### `execStmtListReturn` — New Helper

Need a variant of `execStmtList` that returns the last expression's value:
```java
private Object execStmtListReturn(List<Stmt> stmts, Environment env) {
    Object last = Values.UNIT;
    for (var stmt : stmts) {
        switch (stmt) {
            case Stmt.ExprStmt es -> last = eval(es.expr(), env);
            default -> { exec(stmt, env); last = Values.UNIT; }
        }
    }
    return last;
}
```

This matches the existing pattern in `apply()` for `ImperativeFn`.

## File Change Summary

| File | Change |
|------|--------|
| `EffectSystem.java` | **NEW** — ThreadLocal stack, messages, fireOp |
| `Decl.java` | Extend HandlerDecl with stateBindings |
| `AstBuilder.java` | Process binding clauses in handlerDecl |
| `Values.java` | Add EffectDescriptor, HandlerValue |
| `Interpreter.java` | Implement evalEffectDecl, evalHandlerDecl, execWith, runHandlerLoop, execStmtListReturn |
| `InterpreterTest.java` | New Effects nested class with ~15-20 tests |
| `TODO.md` | Update Phase 3 checklist |
| `docs/phase-3-effects.md` | Phase 3 documentation |

## Risk Assessment

1. **Deadlock risk**: If body thread and handler thread both block on their respective queues
   with no one to unblock them. Mitigated by: body always sends Done/Err before exiting;
   handler always reads from opChannel.

2. **Thread leak**: If handler throws before reading Done from body. Mitigated by:
   interrupt body thread in finally block of runHandlerLoop / execWith.

3. **Stack overflow in deeply nested handlers**: Each `with` + virtual thread adds to
   nesting. Virtual threads have large stacks (~1M frames), should be fine in practice.

4. **Interaction with `spawn`**: Spawned threads have NO handler stack by default.
   Effect ops in spawned threads will fail with "unhandled effect". This is correct behavior —
   spawned threads must install their own handlers.

## Order of Implementation

1. Create `EffectSystem.java` (types + static methods)
2. Add `EffectDescriptor` + `HandlerValue` to `Values.java`
3. Extend `HandlerDecl` in `Decl.java`
4. Update `AstBuilder.java` for state bindings
5. Implement `evalEffectDecl` in Interpreter
6. Implement `evalHandlerDecl` in Interpreter
7. Add `execStmtListReturn` helper
8. Implement `execWith` + `runHandlerLoop`
9. Write tests incrementally after each step
10. Implement on-failure (3d)
11. Implement handler composition (3e)
12. Write docs + update TODO.md + update MEMORY.md
