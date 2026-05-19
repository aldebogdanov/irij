# JVM capability

Every Java interop site — `Class/method`, `Class/staticField`,
`Class/new`, dot-access on a Java reference — requires `JVM` in the
surrounding fn's effect row.

## Why

Irij's effect rows would otherwise be a lie. Arbitrary `java.io.*` /
`java.util.Random` / `System/exit` calls could hide inside a fn
declared "pure" — the entire spec/effect apparatus loses integrity.

Tracking every Java method's purity *individually* is intractable:

- JDK is huge and ships method-level changes across versions.
- Curating a whitelist becomes a forever-job + flakes on JDK updates.
- Auto-detection via bytecode analysis (transitive `PUTSTATIC` / native
  / synchronized scan) is several months of compiler work.

Irij takes the principled-minimum stance: **all Java interop is impure
unless explicitly declared.** No exceptions. No per-method
classification.

## Enforcement

In `Interpreter.java`:

```java
case Expr.JavaRef(var ref, var loc) -> {
    checkEffectsAvailable(JAVA_EFF, "java-interop:" + ref, loc);
    yield JavaInterop.resolveStaticRef(ref);
}
```

`JAVA_EFF = List.of("JVM")` — single-element. The check consults the
current effect-row stack (`AVAILABLE_EFFECTS`). Top-level scripts get
`AMBIENT_EFFECTS` (everything available), so scripts work. Annotated
fns must declare `::: JVM` to touch Java.

## Default handler

`std.jvm` ships:

```
mod std.jvm
pub effect JVM
pub handler unsafe-jvm :: JVM
```

`effect JVM` has no operations — it's a capability marker. The handler
likewise has no clauses. `with unsafe-jvm body` simply adds `JVM` to
the available effect set for the body's dynamic extent. Suitable for
inline scripts.

The name `unsafe-jvm` is deliberate — it signals the trade-off: you've
opted out of Irij's effect-tracking guarantees within this block.

## Behavioural matrix

| Form | Java interop in body? |
|---|---|
| Top-level script (no fn) | ✅ ambient — works |
| `fn f (...)` | ❌ rejected at the Java site |
| `fn f ::: JVM (...)` | ✅ explicit |
| `fn f ::: Console JVM (...)` | ✅ row-combined |
| `with unsafe-jvm body` (inline) | ✅ for the body's anonymous code |
| `with unsafe-jvm; f (...)` where f is `fn f (s -> Long/parseLong s)` | ❌ — fn boundary enforces its own row |

The last row is intentional: fn-level annotations are a static
contract, *independent* of dynamic scope. To call Java from a helper
fn, that fn declares `::: JVM`. `with unsafe-jvm` doesn't override
the fn's declaration.

## Stdlib pattern

Stdlib effects (`Random`, `Time`, `FileIO`, etc.) wrap raw Java surface
so most user code never needs `::: JVM` directly:

```
fn roll-die ::: Random
  _ -> rand-int 6
```

The `Random` effect operation `rand-int` is implemented inside the
`default-random` handler which DOES touch `java.util.Random`. The
handler clause has `::: JVM` itself. The capability is contained:
callers see `::: Random`, the implementation deals with `::: JVM`.

## What this doesn't catch

- **Java methods that call back into Irij.** A `Runnable` impl passed
  to Java that internally performs effects — capability info is
  erased crossing the FFI boundary. Same problem every FFI has.
- **Reference aliasing across handler boundaries.** A `with unsafe-jvm`
  block can return a `java.util.Random` ref without spec annotation;
  calls on that ref still flow through the plain `JVM` check. The
  per-ref propagation below catches the *annotated* case.

Bytecode-mode enforcement is no longer trust-only: `EffectRowChecker`
runs at compile time (`docs/internals/specs.md` — "Compile-time
effect-row lint") and fails the build for both modes.

## Why not granular Java effects

Considered + rejected:

- **`File` effect, `Net` effect, etc.** Requires whitelisting which
  methods belong where. JDK version drift breaks it; new third-party
  libs aren't classifiable.
- **Auto-detection.** Bytecode analysis of called Java method:
  doable, but conservative defaults still require `::: JVM` for any
  unknown method — most "obvious purity" cases still need annotation.
  Significant impl cost.

A single coarse `JVM` capability is honest about what the system can
verify (nothing about Java internals) while restoring effect-row
integrity at the Irij/Java boundary. Refinement can come later
without breaking this baseline.

## Lessons from other languages

- **Haskell's `IO`** — same idea: one monad for "I touched the world."
  Irij `JVM` is the parallel.
- **Roc's platform** — pure language, all I/O via host. Irij doesn't
  go that far; the platform abstraction is heavyweight.
- **OCaml 5 / Koka FFI** — explicit effect annotations on imported C
  functions. Programmer-declared, trusted. Irij's `::: JVM` is the
  same pattern coarsened to a single tag.

## Per-ref capability propagation

A bind whose spec annotation names a declared effect promotes the
bound variable into a capability handle. Subsequent dot-access on
that variable demands the named effect from the caller's row — in
addition to `JVM`.

```
effect Random
  next-int :: Int Int

fn use-rnd :: Int Int ::: JVM Random
  => seed
  rnd := java.util.Random/new seed :: Random
  rnd.nextInt 100
```

Without `:: Random` on the bind, only `JVM` is required. With it,
`use-rnd` must declare `::: Random` too — letting the type author
mark which logical resource a Java handle represents and forcing
callers to acknowledge it.

Implementation (`EffectRowChecker`):

- Pass 1 collects `effectNames` from every `EffectDecl`.
- Per-fn `varCap: Map<String, String>` is reset on entry.
- `Stmt.Bind` walker records `target → effectName` when
  `b.specAnnotation()` is a `SpecExpr.Name` matching an effect.
- `Expr.DotAccess` walker, if its target is a `Var` present in
  `varCap`, calls `requireEffect(effect, ..., avail, loc)` before
  recursing.

The check is purely lexical — no flow analysis. Reassigning the var
or aliasing through another bind drops the capability. Adequate for
the common case (allocate handle once, call methods on it nearby);
not adequate for capability laundering by indirection. Tighter
tracking is future work.

Tests: `EffectRowLintTest.dotAccessOnEffectTaggedRefRequiresEffect`
+ accept/plain-jvm variants.
