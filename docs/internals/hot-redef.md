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
MethodHandle target = lookup.findStatic(ownerClass, name, mt);
MutableCallSite cs = new MutableCallSite(target);
REDEF_SITES.get(ownerClass).computeIfAbsent("method:descriptor", …).add(cs);
return cs;
```

`REDEF_SITES` is a `ClassValue` — a registry hung off each owner class,
holding every call site of each of its fns — plus a weak
owner-name → class index for `redefine`'s string keys. Being attached to
the class, the sites are collected with it: the registry used to be a
static `ConcurrentHashMap<String, MutableCallSite>`, which kept every
class that ever ran (and its classloader) alive — a metaspace leak of
one classloader per Playground / nREPL / MCP eval
(`SessionClassUnloadingTest`). It also kept only the last call site per
key, so `redefine` left the others on the old target.

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

The nREPL backend is `BytecodeSession`: each connection owns its
own classloader + namespace map. Re-evaluating an `fn` definition
emits a fresh class, registers a new `IrijFn` in the namespace map,
and swaps the underlying `MutableCallSite` target so existing call
sites pick up the new implementation on their next invocation.

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
