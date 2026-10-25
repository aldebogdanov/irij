# JVM capability

Every Java interop site — `Class/method`, `Class/staticField`,
`Class/new`, `obj#member`, a method reference `#member` — requires
`JVM` in the surrounding fn's effect row. The forms are lexically
distinct (`JAVA_REF`, `JAVA_MEMBER`, `JAVA_MEMBER_REF`), so
`EffectRowChecker` checks every one at compile time; `.` is for Irij
fields and never Java.

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

## `std.jvm` and the deprecated `unsafe-jvm`

`std.jvm` ships:

```
mod std.jvm
pub effect JVM
pub handler unsafe-jvm :: JVM ::: JVM     ;; deprecated
```

`effect JVM` has no operations — it's a capability marker, and it
needs no import to be named in a row. `unsafe-jvm` has no clauses.
It used to declare no row, so `with unsafe-jvm body` added `JVM` to a
block inside a fn that didn't declare it — the escape hatch that let a
fn declared pure call Java. Its one use in the wild (irij.online's
`stored-readme`) was exactly that. Since a `with` needs its handler's
row (effects.md, pattern 4), it declares `::: JVM`: installing it needs
`JVM` already and grants nothing. The checker warns at every `with` of
it (`warnDeprecatedHandlers`); it will be removed.

## Behavioural matrix

| Form | Java interop in body? |
|---|---|
| Top-level script (no fn) | ✅ ambient — works |
| `fn f (...)` | ❌ rejected at the Java site |
| `fn f ::: JVM (...)` | ✅ explicit |
| `fn f ::: Console JVM (...)` | ✅ row-combined |
| `fn f` (no row) doing `with unsafe-jvm …` | ❌ — `unsafe-jvm :: JVM ::: JVM`; deprecated, warns |
| `fn f` (no row) doing `with default-fs …` (`handler default-fs :: FileIO ::: FileIO`) | ❌ — needs `::: FileIO`, not `JVM`: a `with` needs its handler's row (effects.md, pattern 4) |
| top-level `with default-fs …` | ✅ ambient |

Fn-level annotations are a static contract, *independent* of dynamic
scope: to call Java from a helper fn, that fn declares `::: JVM`, and
no `with` inside a fn can grant an effect its row lacks.

## Stdlib pattern

Stdlib effects (`Random`, `Time`, `FileIO`, etc.) wrap raw Java surface
so most user code never needs `::: JVM` directly:

```
fn roll-die ::: Random
  _ -> rand-int 6
```

A library does the same through a **cap**: a `pub effect` for the
resource (`Clock`, `Mailer`), `pub fn`s whose rows name only that
effect, a `cap` binding a Java class to it, and a real handler whose
clauses call the cap:

```
pub effect Clock
  utc-now :: () -> Str

cap instant :: Clock = "java.time.Instant"   ;; a JDK class works too
cap text :: Clock = "java.lang.String"

pub handler system-clock :: Clock ::: Clock
  utc-now () => resume (text.valueOf (instant.now ()))
```

A call through a Java cap performs the cap's effect, not `JVM`
(capabilities.md § A Java cap call performs its effect), so the real
handler's row is its own effect — the shape `default-time :: Time :::
Time` always had — and a fn that installs it declares `::: Clock`, the
resource it touches. `JVM` appears only where someone writes
`Class/member` or `#member` by hand. The `cap` line is the trust point:
it says "this Java class implements `Clock`", once, greppably.

## What this doesn't catch

- **Java methods that call back into Irij.** A `Runnable` impl passed
  to Java that internally performs effects — capability info is
  erased crossing the FFI boundary. Same problem every FFI has.
- **Reference aliasing across handler boundaries.** A `::: JVM` fn
  can return a `java.util.Random` ref without spec annotation;
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
  rnd#nextInt 100
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
- The `Expr.JavaMember` walker, if its target is a `Var` present in
  `varCap`, calls `requireEffect(effect, ..., avail, loc)` before
  recursing (`Expr.DotAccess` keeps the same check for a capability-typed
  var that holds an Irij value).

## Instance members: `#`, and `.`

`obj#member` is an `Expr.JavaMember`. The checker requires `JVM` at the
site (`java-interop:#member`); the emitter calls
`RtInterop.javaMember(recv, member)`, which re-checks `JVM` against the
runtime row — the backstop for a lambda written where `JVM` was
available and called inside a pure fn — and refuses a receiver of one of
Irij's own runtime classes (vector, map, record, rational…): those
aren't Java objects to the program, and `.` or a builtin is meant.

A method reference, `#member args…` with no receiver, is an
`Expr.JavaMemberRef`: the checker requires `JVM` where it is written,
and `RtInterop.javaMemberRef(member, args)` builds a one-argument
`BuiltinFn` that re-checks `JVM` and the receiver each time it is
applied, then calls `JavaInterop.invokeMember`.

Before `#`, a Java call was spelled `obj.method`, a `DotAccess` like any
field read, so the static checker couldn't tell `s.trim` on a string
from `r.trim` on a record and only `Class/member` was checked. `.` at
run time (`RtInterop.javaInstanceRef`) now reads a map or record field,
or reaches one of Irij's runtime objects (a scope handle's `s.fork`);
on any other Java object it is an error naming the `#` spelling.

The check is purely lexical — no flow analysis. Reassigning the var
or aliasing through another bind drops the capability. Adequate for
the common case (allocate handle once, call methods on it nearby);
not adequate for capability laundering by indirection. Tighter
tracking is future work.

Tests: `EffectRowLintTest.dotAccessOnEffectTaggedRefRequiresEffect`
+ accept/plain-jvm variants.
