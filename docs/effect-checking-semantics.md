# Effect Checking: Definition-Site vs Call-Site

Status: design decision, as of 0.2.7.

## The choice

Irij checks effect rows **at call time**, not at function definition.

When a function is defined with `fn foo ::: Db`, the runtime records the row
`{Db}`. When `foo` is later called, the interpreter pushes that row onto
`AVAILABLE_EFFECTS` and executes the body. Any builtin or handler operation
that requires an effect name not present in the current frame throws
`Effect 'X' not declared`.

This is a dynamic check; it runs every time the function is actually invoked.

## What this buys

- Simplicity. No separate static analysis pass; no need to resolve forward
  references, handle polymorphism, or reason about partial application
  statically.
- Compositional handlers. A handler can install effects into the frame at
  runtime (`with default-db` pushes `Db`), and the call-time check sees them.
- Works with higher-order code. Lambdas with `effectRow == null` inherit the
  caller's row — fine at call time, hard to type statically.

## What it misses

- **Dead code.** If `foo` references `db-exec` but `foo` is never called on
  the path under test, the mistake stays invisible. A module-level lint pass
  would flag this before running.
- **Typo-resistance.** A function that *could* call `db-exec` gets no
  warning at definition time if the author forgot to add `::: Db`.

## Audit strategy

For authors who want definition-time checking today:

- Run the existing `irij test` — exercising each function from a test
  triggers the runtime check.
- Contracts (`pre`, `post`) are evaluated whenever the function runs, so
  placing `pre true` (or any invariant) gives you a forced call path.
- `--spec-lint` already catches missing spec annotations on `pub fn`. A
  future `--effect-lint` can walk each fn body, resolve references against
  the globals after full file load, and warn on effect-row mismatch.

## Future: `--effect-lint`

Proposed addition (not implemented):

1. After a file is fully loaded (all top-level `fn`s are in `globalEnv`),
   walk each `ImperativeFn`/`Lambda`/`MatchFn` body.
2. For each `Call` node whose callee is a known identifier bound in the
   surrounding env: look up the callee's `effectRow` (or
   `BuiltinFn.requiredEffects()`).
3. Union all such effects. Compare to the caller's declared row.
4. Emit a warning for each effect used but not declared.

Limitations the lint would accept:
- Anonymous lambdas with `effectRow == null` propagate their callee effects
  to the caller (conservative union).
- Higher-order args (`args.get(0)` is a function of unknown kind) can't be
  checked; they are treated as potentially requiring all effects the
  enclosing row allows — so no false positive.
- Dynamic dispatch / protocols can't be fully resolved; the lint skips them.

The dynamic check remains the source of truth at runtime. `--effect-lint`
would be an opt-in warning, analogous to `--spec-lint`.

## Rationale for the current split

Effect rows are a *runtime contract*: "within this frame, only these effect
names may be performed." The dynamic check is cheap (one set lookup per
effect op) and gives precise blame. Static checking is worthwhile but
additive — it should warn, not block, because many correct programs (esp.
higher-order, polymorphic) are hard to prove pure statically.

If you need stronger guarantees than the dynamic check gives, cover the
code path in a test; the check fires before any side effect escapes.
