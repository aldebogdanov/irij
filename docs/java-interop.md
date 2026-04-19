# Java Interop (Phase 13)

*Introduced in v0.3.0.*

Irij runs on the JVM and exposes every Java class via Clojure-style `Class/member` syntax. No `use` declarations, no type imports — just reference the class and go.

## Forms

| Syntax                   | Meaning                                   |
|--------------------------|-------------------------------------------|
| `Class/staticMethod`     | Callable that dispatches to a static method |
| `Class/STATIC_FIELD`     | Value of a static field                   |
| `Class/new`              | Constructor (callable)                    |
| `obj.instanceMethod`     | Bound instance-method callable            |
| `obj.field`              | Instance field value                      |

Class names can be unqualified (`System`, `Math`, `String`, …) for common `java.lang.*` classes, or fully-qualified (`java.time.Instant`, `java.util.UUID`).

## Examples

```irij
println (Math/abs (-7))              ;; 7
println (Math/PI)                    ;; 3.141...

stamp := java.time.Instant/now ()
println ("hello".toUpperCase ())     ;; HELLO

rnd := java.util.Random/new 42
println (rnd.nextInt 100)
```

See [examples/jvm-interop.irj](../examples/jvm-interop.irj) for the full tour.

## Value coercion

**Irij → Java** (at call site):

| Irij               | Java target        |
|--------------------|--------------------|
| `Str`              | `String`, `CharSequence`, `char` (len 1) |
| `Int` (Long)       | `long`/`int`/`short`/`byte` (preferred), `double`/`float` (fallback) |
| `Float` (Double)   | `double`/`float`   |
| `Bool`             | `boolean`          |
| `()` (Unit)        | `null`             |
| `IrijVector`       | `List`, array      |
| `IrijMap`          | `Map`              |
| Any Java object    | Passed as-is       |

**Java → Irij** (on return):

| Java                      | Irij          |
|---------------------------|---------------|
| `String`, `Boolean`       | As-is         |
| `byte`/`short`/`int`/`long` | `Int` (Long)|
| `float`/`double`          | `Float` (Double) |
| `char`                    | `Str` (len 1) |
| `null`                    | `()`          |
| `List<?>`, `Object[]`     | `IrijVector`  |
| `Map<?,?>`                | `IrijMap`     |
| Anything else             | Opaque value (dot-access still works) |

## Overload resolution

When multiple Java methods match the arg count, Irij picks the one with the best per-argument score:

- exact type match (`Long → long`, `String → String`): **0**
- near match (`Long → int`/`short`/`byte`, `Double → float`): **1**
- cross-family numeric (`Long → double`): **5–6**
- `Object` fallback: **3**
- incompatible: filtered out

Lowest total score wins. This means `Math/abs 7` picks `abs(long)` (not `abs(double)`), and `Math/max 3 5` returns `Int`, not `Float`.

## Effect tag

Every JVM call is tagged with the `JVM` effect. Any function that touches Java must declare it:

```irij
fn read-sys-time :: _ Int ::: JVM
  _ => System/currentTimeMillis ()
```

The `std.test.test` row already includes `JVM`, so tests can use Java directly.

## Gotchas

- **`()` means zero args.** `"hi".toUpperCase ()` → `toUpperCase()`, not `toUpperCase(Locale)`. Irij strips a lone `()` before dispatch.
- **Chained instance calls need parens or `|>`.** `"x".a ().b ()` does not parse. Write `(("x".a ()).b ())` or `"x" |> (s -> (s.a ()).b ())`.
- **`foo/bar` (no spaces) is always a Java ref.** For division use `foo / bar` with spaces. (Integer literals like `10/3` still parse as `RATIONAL`.)
- **camelCase method names require a DOT prefix.** Bare `toUpperCase` anywhere except after `.` or `/` is a lexer error. This is intentional: Irij identifiers remain kebab-case.
- **Nominal types are erased.** `List<String>` coerces loosely — at call time we check `instanceof List`, not the generic param.
- **No type hints yet.** If a method is overloaded and the auto-scored pick is wrong, refactor to disambiguate (e.g., `Long.valueOf(x)` before passing).
- **Reflection perf.** Each call does a `getMethods()` scan. Acceptable for now; `MethodHandle` caching will come if benchmarks demand it.

## Security

JVM interop is **not blocked** in sandboxed interpreters (e.g., the playground). If this matters for a deployment, gate it upstream by stripping the `JVM` effect from handler rows, or replace `JavaInterop.resolveStaticRef` with a deny-by-default wrapper.
