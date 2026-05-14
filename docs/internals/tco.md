# Tail-call optimization

Self-recursive calls in tail position lower to GOTO + arg rebind
instead of `INVOKESTATIC`. The fn keeps reusing its JVM frame, so
deep recursion runs in O(1) stack.

Implemented in `ClassEmitter` only (bytecode). The interpreter does
*not* TCO; deep recursion overflows ~5–10K frames in interp mode.

## Mechanism

At fn-emit time:

1. Set `currentFnName`, `currentFnArity`, and place `currentFnEntry`
   (a `Label`) right after the param-slot init.
2. Body is emitted via `emitTailExpr` — a tail-position-aware
   companion to `emitExpr`. `emitTailExpr`:

   - Direct self-tail-call (`App(Var(currentFn), args)` matching arity)
     → push all args onto JVM stack, ASTORE in reverse to param slots,
     `GOTO currentFnEntry`.
   - `Expr.IfExpr` → cond non-tail, branches tail (recurse with
     `emitTailExpr`).
   - `Expr.Block` → earlier stmts non-tail, last expr tail.
   - Otherwise → `emitExpr` + `ARETURN`.

## Why eval-args-then-store-reverse

Tail-call args can reference current param values:

```
fn sum-to
  (acc n -> if (n == 0) acc else (sum-to (acc + n) (n - 1)))
```

The new args `acc + n` and `n - 1` *both* read the old `acc` and `n`.
If we stored `acc + n` to slot 0 first, the second arg evaluation
would read the new (wrong) `acc`.

Solution: push all args onto the JVM operand stack first (evaluating
each using the *original* slot values), then ASTORE in reverse so
top-of-stack lands in the last param:

```
ALOAD acc; ALOAD n; INVOKESTATIC add    ;; stack: [acc+n]
ALOAD n; ICONST 1; INVOKESTATIC sub     ;; stack: [acc+n, n-1]
ASTORE 1                                 ;; n-1 → slot 1
ASTORE 0                                 ;; acc+n → slot 0
GOTO currentFnEntry
```

Stack discipline is simpler than introducing temp slots.

## Test coverage

`TcoTest`:

- `self_tail_call_1M_deep` — `sum-to 0 1_000_000` runs to completion
  in both bytecode modes.
- `self_tail_call_in_if_then_branch` — only one branch is tail.
- `self_tail_call_args_use_old_param_values` — proves the eval-then-
  store-reverse ordering is right.
- `non_tail_call_is_not_rewritten` — `1 + (f (n - 1))` keeps a normal
  `INVOKESTATIC` because the addition follows the call.

## Mutual TCO — not implemented

`fn even? (n -> if (n == 0) true else (odd? (n - 1)))`. Each call
targets a *different* JVM method, so `GOTO` doesn't work directly. To
support this we'd need either:

- **Trampoline** — return a `Thunk(args)` instead of recursing; a
  driver loop unwraps. Allocates per call.
- **Merge into one method** — graph analysis finds the SCC, generates
  a unified helper with a "which fn" tag and `GOTO`s within it. Fast,
  more emitter work.

Neither is shipped. Idiomatic Irij code rarely uses mutual recursion;
when it appears, deep iteration via mutual calls would blow the stack.
Documented limitation.

## Interpreter TCO — deferred

The interpreter walks the AST recursively via `eval`. Adding TCO would
require either:

- Threading a `boolean tail` flag through every `eval` call site (very
  invasive — touches every case).
- A thread-local "expected tail target" with a sentinel return value
  for matching cases — less invasive but still needs taint analysis
  to know which positions are tail.

Dev workloads (REPL, single-script runs) rarely recurse > 1000 deep, so
the gap doesn't bite in practice. Deploy uses bytecode which has TCO.

## Bench effect

`bench/vec-sum/main.irj` uses `std.list.fold` over a vector built via
self-recursion. Without TCO, build at N=500 was at the edge of safe;
with TCO it scales to N=1M with no overhead — recursion is now a
zero-cost abstraction at the bytecode level.

## Interaction with other features

- **SM mode**: TCO works the same. Self-tail-calls in a body that also
  does performs lower correctly — `emitTailExpr` is dispatched at the
  fn-body level, *not* inside the SM step. SM step bodies don't
  themselves contain self-calls in the tail-call sense (the step is
  not the user's fn).
- **`with` body**: Self-tail-call inside a `with` body in interpreter
  mode still doesn't TCO (interp has no TCO). In bytecode SM mode,
  the body is emitted as an SM step — different mechanism (perform
  signals + trampoline) — so deep loops *also* don't overflow, but
  via the SM trampoline, not TCO proper.
- **Hot-redef**: not affected. TCO emits a `GOTO` to the same method
  — there's no call site to swap. Redefining the fn at runtime via
  `MutableCallSite` swaps the *target* of external call sites but the
  body's internal recursion is unchanged.
