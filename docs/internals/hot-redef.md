# Hot redefinition

Swap a top-level fn's implementation at runtime without restarting the
JVM. Models Clojure's `--direct-linking` deploy switch: dev builds get
hot-redef, deploy builds get plain `invokestatic` for max JIT inlinability.

## Mechanism

Each user-fn call site compiles to:

| Build | Bytecode |
|---|---|
| `--direct-linking=false` (default, dev) | `invokedynamic name desc → bootstrap returns MutableCallSite` |
| `--direct-linking=true` (deploy) | `invokestatic name desc` |

The bootstrap `RuntimeSupport.redefBootstrap(Lookup, name, MethodType)`:

```java
MethodHandle target = lookup.findStatic(callerClass, name, mt);
MutableCallSite cs = new MutableCallSite(target);
REDEF_SITES.put("owner.method:descriptor", cs);
return cs;
```

The `REDEF_SITES` map (a `ConcurrentHashMap`) keys by
`owner.method:descriptor` so call sites for the same fn share one
entry.

## Swap

```java
RuntimeSupport.redefine(
    "irij.Program.greet:(Ljava/lang/Object;)Ljava/lang/Object;",
    newImplHandle);
```

This sets the `MutableCallSite`'s target and calls `syncAll`. All
existing call sites now dispatch to `newImplHandle`. No re-link, no
JVM restart.

## Cost

- Indy site startup: one bootstrap call per (call site, fn) pair on
  first invocation. Cached afterward.
- Steady-state: one `MutableCallSite.invokeExact` per call. HotSpot
  inlines stable `MutableCallSite` targets — performance is within
  noise of plain `invokestatic` after warmup.
- Cost of a swap: one `setTarget` + one `syncAll` (broadcasts the
  change across cores). O(1).

The `fib(32)` bench shows < 1 ms difference between direct-linked and
indy modes — well within JIT noise.

## What does NOT swap

- **Self-recursion via TCO** — the tail-call lowers to `GOTO`, not
  `invokestatic`. Recursive calls inside the body keep using the old
  code until the fn finishes the current invocation. New invocations
  call the new impl.
- **References cached as `IrijFn` values** — once you've captured `f`
  by name in a higher-order context (`fold f 0 v`), that `IrijFn`
  closure refers to the old `f$impl` directly. Swap doesn't reach
  through. Workaround: re-capture after redef, or wrap in a
  late-binding indirection.

These are the same caveats Clojure has. Documented.

## REPL integration

Currently the nREPL backend is the *interpreter*, which has its own
hot-redef via `globalEnv.assign` — REPL just rebinds the name in the
env. Bytecode-mode hot-redef (compile-on-eval, then `redefine` swap)
is on the roadmap. See `nrepl.md` (tech-debt: not yet written).

The mechanism is shipped at the bytecode layer — the nREPL is just
not wired to use it yet.

## Tests

`HotRedefTest`:

- `redef_swaps_top_level_fn_impl` — compile, run (prints "hi"), swap
  target to a Java method that prints "yo!", run again, verify.
- `direct_linking_skips_indy_so_redef_has_no_effect` — same source
  compiled with `--direct-linking`; `redefine()` returns false (no
  site registered) and the original impl runs.

## Bootstrap signature constraint

`redefBootstrap` matches the standard indy bootstrap signature
`(Lookup, String, MethodType) → CallSite`. The fn's method type is
passed as the `MethodType` arg — the bootstrap uses
`lookup.findStatic(callerClass, name, mt)` to find the impl. This
ties the `MutableCallSite` to the *current* impl at bootstrap time;
swaps replace just the `target` MH, not the call site's identity.

## Why MutableCallSite, not SwitchPoint or ConstantCallSite

| Choice | Why not |
|---|---|
| `ConstantCallSite` | Can't swap after creation — defeats the purpose. |
| `SwitchPoint` | Good for *invalidating* assumptions, but we want to *swap* targets. Layer of indirection without the right primitive. |
| `MutableCallSite` | Built for exactly this: swap the target MH on demand, `syncAll` propagates. JIT-friendly when the target is stable. |
