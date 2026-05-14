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

- **Reference aliasing.** A `with unsafe-jvm` block can return a
  `java.util.Random` ref. Calls `.nextInt()` on that ref outside any
  `with` would still bypass tracking. Per-ref capability propagation
  is a possible v2 enhancement (spec annotates the ref's capability
  row; method calls inherit). Not shipped.
- **Java methods that call back into Irij.** A `Runnable` impl passed
  to Java that internally performs effects — capability info is
  erased crossing the FFI boundary. Same problem every FFI has.
- **Bytecode-mode enforcement.** The interpreter enforces; bytecode
  trusts. The intended dev flow is interp-during-development to catch
  violations, then `irij build` for deploy.

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

## Future direction

Per-ref capability propagation (v2):

```
rnd :: Random! := java.util.Random/new 42
;; rnd carries the Random capability in its spec
with java-random rnd
  x := rnd.nextInt 100
```

Implementation: spec annotations gain effect rows; spec-checker
propagates from constructors through dot-accesses. Significant
spec-system work; future direction, not v1.
