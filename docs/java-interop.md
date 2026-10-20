# Java Interop (Phase 13)

*Introduced in v0.3.0.*

Irij runs on the JVM and exposes every Java class via Clojure-style `Class/member` syntax, and every Java object's members via `obj#member` — Javadoc's `String#trim` notation. No `use` declarations, no type imports — just reference the class and go.

## Forms

| Syntax                   | Meaning                                   |
|--------------------------|-------------------------------------------|
| `Class/staticMethod`     | Callable that dispatches to a static method |
| `Class/STATIC_FIELD`     | Value of a static field                   |
| `Class/new`              | Constructor (callable)                    |
| `obj#instanceMethod`     | Bound instance-method callable            |
| `obj#field`              | Instance field value                      |
| `#instanceMethod args…`  | Method reference: a function of the receiver |

`.` is for Irij only: record and map fields (including camelCase keys from JSON, `m.userId`), module alias members, handler state, `s.fork`, capability operations. `#` is for Java only, so every Java touchpoint is visible and checked for `::: JVM` at compile time. `.` on a Java object (`s.trim ()`) is an error that names the `#` spelling.

Class names can be unqualified (`System`, `Math`, `String`, …) for common `java.lang.*` classes, or fully-qualified (`java.time.Instant`, `java.util.UUID`).

## Examples

```irij
println (Math/abs (-7))              ;; 7
println (Math/PI)                    ;; 3.141...

stamp := java.time.Instant/now ()
println ("hello"#toUpperCase ())     ;; HELLO

rnd := java.util.Random/new 42
println (rnd#nextInt 100)
println ((java.time.LocalDate/now ())#plusDays 30)
```

See [examples/jvm-interop.irj](../examples/jvm-interop.irj) for the full tour.

## Method references

`#member` with no receiver in front of it is a function of the receiver,
for `@`, `|>` and anything else that takes a function:

```irij
names |> @ #toUpperCase              ;; #["ANN" "BO"]
dates |> @ (#plusDays 30)            ;; each date a month on
"  x  " |> #trim                     ;; "x"
p |> #x                              ;; a public field reads its value
```

Arguments written after the name are the method's, not the receiver:
`#plusDays 30` is `d -> d#plusDays 30`, evaluated once where the reference
is written. With no arguments a method is called with none and a public
field is read. Whether `#name` is a member or a reference depends on what
comes right before it: an atom with no space (`s#trim`, `(f x)#size`,
`"x"#length`) makes it a member of that atom; a space, an opening bracket
or the start of the line makes it a reference. A reference needs `::: JVM`
where it is written, like `Math/abs` passed as a value.

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
| Anything else             | Opaque value (`#` reaches its members) |

## Overload resolution

When multiple Java methods match the arg count, Irij picks the one with the best per-argument score:

- exact type match (`Long → long`, `String → String`): **0**
- near match (`Long → int`/`short`/`byte`, `Double → float`): **1**
- cross-family numeric (`Long → double`): **5–6**
- `Object` fallback: **3**
- incompatible: filtered out

Lowest total score wins. This means `Math/abs 7` picks `abs(long)` (not `abs(double)`), and `Math/max 3 5` returns `Int`, not `Float`.

## Objects of a class you cannot see

An instance method is invoked through the most specific **public, exported**
type that declares it. `ProcessBuilder.start` returns a `java.lang.ProcessImpl`,
which is package-private inside `java.base`: reflection refuses to call the
overrides it declares, even though `Process` declares every one of them
public. So `p#getInputStream ()` resolves to `Process.getInputStream` and
dispatch still lands on the override, exactly as it does from Java source.
The same holds for any implementation class behind a public interface or
superclass.

## Arrays round-trip

An array Java hands out becomes an `IrijVector`, and a vector passed where
Java expects an array (`byte[]` included) is converted back element by
element, so `String/new (stream#readAllBytes ())` works. That costs a boxed
value per element: for large binary data keep the array inside Java, or use
an effect whose capability returns a `Str`.

## Effect tag

Every JVM call is tagged with the `JVM` effect. Any function that touches Java — a `Class/member` or an `obj#member` — must declare it, and the compiler checks every such site:

```irij
fn read-sys-time :: _ Int ::: JVM
  _ => System/currentTimeMillis ()
```

The `std.test.test` row already includes `JVM`, so tests can use Java directly.

## Gotchas

- **`()` means zero args.** `"hi"#toUpperCase ()` → `toUpperCase()`, not `toUpperCase(Locale)`. Irij strips a lone `()` before dispatch.
- **Chained instance calls need parens or `|>`.** In `s#trim ()#length ()` the `#length` attaches to the `()` argument. Write `(s#trim ())#length ()` or `s |> (x -> (x#trim ())#length ())`.
- **`#` is for Java objects.** On an Irij vector, map or record it is an error that points you to `.` or a builtin.
- **`.` is for Irij values.** On a Java object it is an error naming the `#` form: `s.trim ()` → write `s#trim ()`.
- **Space before `#` matters.** `f s#trim` passes `s#trim` (a bound method) to `f`; `f s #trim` passes `s` and the reference `#trim`.
- **`foo/bar` (no spaces) is always a Java ref.** For division use `foo / bar` with spaces. (Integer literals like `10/3` still parse as `RATIONAL`.)
- **camelCase names come after `#`, `/` or `.`.** Bare `toUpperCase` anywhere else is a lexer error. This is intentional: Irij identifiers remain kebab-case. (After `.`, a camelCase name is a field — a JSON key.)
- **Nominal types are erased.** `List<String>` coerces loosely — at call time we check `instanceof List`, not the generic param.
- **No type hints yet.** If a method is overloaded and the auto-scored pick is wrong, refactor to disambiguate (e.g., `Long.valueOf(x)` before passing).
- **Reflection perf.** Each call does a `getMethods()` scan. Acceptable for now; `MethodHandle` caching will come if benchmarks demand it.

## Security

JVM interop is **not blocked** in sandboxed interpreters (e.g., the playground). If this matters for a deployment, gate it upstream by stripping the `JVM` effect from handler rows, or replace `JavaInterop.resolveStaticRef` with a deny-by-default wrapper.
