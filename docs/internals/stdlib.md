# Stdlib

What lives in real `.irj` files vs Java. The split matters because
Java code can only run in the interpreter (well, via runtime helper
methods called from bytecode), while `.irj` files compile through
both back-ends.

## Java side — `Interpreter.installInterpreterBuiltins` + `Builtins.install`

Registered as `BuiltinFn` objects in the global environment:

- Arithmetic + comparison primitives (`add`, `sub`, `mul`, `div`,
  `mod`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `++`, `&&`, `||`, `!`)
- IO (`print`, `println`, `dbg`, `read-line`)
- Conversion (`to-str`, `to-vec`)
- Collection raw ops (`length`, `head`, `tail`, `nth`, `last`,
  `reverse`, `sort`, `concat`, `take`, `drop`, `keys`, `vals`, `get`,
  `assoc`, `contains?`, `range`, `empty?`, `conj`)
- Math (`abs`, `min`, `max`, `pi`, `e`)
- Higher-order (`fold`)
- Concurrency (`spawn`, `await`, `sleep`, `par`, `race`, `timeout`,
  `try`)
- Effect / handler internals (`raw-*` calls for HTTP, DB, SSE, session)

Why some live in Java:

- **Raw access to internal types** — `length` on `IrijVector` needs
  the underlying `List<Object>`; can't express in pure Irij.
- **Effect transparency** — `fold` needs to invoke the callback with
  the *caller's* effect row (the callback can perform Console etc.).
  See `phase-14-effect-row-polymorphism.md` (TODO) for the design gap.
- **Performance** — primitive ops on the hot path.

## Irij side — `src/main/resources/std/*.irj`

Real Irij code, parsed + compiled like user code:

| Module | What it provides |
|---|---|
| `std.list` | `fold`, `map`, `filter`, `reverse-vec`, `sum`, `count` (Phase 3 port) |
| `std.collection` | `zip`, `flatten`, `distinct`, `freq`, `group-by`, `map-vals`, `filter-vals`, `each`, `take-while`, `drop-while`, ... |
| `std.func` | `flip`, `compose`, `identity`, `const`, `pipe`, `repeat-n`, ... |
| `std.text` | `trim`, `pad-left`, `pad-right`, `split`, `join`, `starts-with?`, `ends-with?`, `substring`, ... |
| `std.math` | Math helpers (some delegate to `java.lang.Math` via `::: JVM`) |
| `std.random` | `Random` effect + `default-random` handler |
| `std.env` | `Env` effect + handler |
| `std.fs` | `FileIO` effect + handlers |
| `std.http` | HTTP client (`http-get`, `http-post`) + server |
| `std.db` | `Db` effect + SQLite handler |
| `std.serve` | Web server framework (routes, middleware, request/response) |
| `std.session` | nREPL session effects |
| `std.datastar` | Datastar SSE protocol |
| `std.json` | JSON parser + serialiser |
| `std.convert` | Type coercions (`to-int`, `to-float`, `to-bool`) |
| `std.test` | Test runner (`test`, `assert-eq`, `assert-throws`, ...) |
| `std.jvm` | `JVM` effect + `unsafe-jvm` handler |

## The boundary

A Java BuiltinFn is **effect-transparent** by construction — the
callback's effects are invisible at registration time, the callback
runs in whatever effect row the caller has. Irij-side higher-order
fns are **fixed-row** — declared `::: JVM` (or whatever), their
callback must fit that row.

So: `fold (_ x -> println x) () v` works against the Java `fold`
under `::: Console` because the BuiltinFn is transparent. The
Irij-side `std.list.fold` is `::: JVM`-only — the same callback would
fail the Console-not-available check.

Both `fold` implementations co-exist:

- Use the Java `fold` (global, ambient) for callbacks that perform
  arbitrary effects.
- Use `std.list.fold` (after `use std.list :open`) for tail-recursive
  iteration where the callback is a `::: JVM`-row fn (or pure).

The duplication will collapse once effect-row polymorphism for
higher-order fns lands. Today: live with both.

## Raw primitives wired into bytecode

For bytecode mode, these collection / string ops are wired directly
into `ClassEmitter.emitBuiltinApp`:

| Name | Where |
|---|---|
| `length` | `INVOKESTATIC RT.length` |
| `nth` | `INVOKESTATIC RT.nth` |
| `conj` | `INVOKESTATIC RT.conj` |
| `empty?` | `INVOKESTATIC RT.isEmpty` |
| `head` | `INVOKESTATIC RT.head` |
| `tail` | `INVOKESTATIC RT.tail` |
| `fold` | `INVOKESTATIC RT.fold` (effect-transparent — callback runs in caller's row) |

These names match the interpreter convention. A single source
compiles + interprets identically. Other builtins (e.g. `fold`,
`map` — when used in interpreter mode) currently DON'T compile to
bytecode at all; they fail with "Unknown function: fold". The fix
is to either (a) wire each into `emitBuiltinApp`, or (b) port to
`.irj` stdlib (Phase 3 path, used for `fold`/`map`/`filter`/etc.).

The general direction: stdlib is real Irij; Java provides the bare-
metal building blocks.

## Why the split persists

Mostly historical + practical:

- Bytecode mode is younger; the interpreter's `BuiltinFn` shape
  predates the emitter.
- Effect-row polymorphism is unsolved — see above.
- Some builtins (concurrency, raw-*, JDBC, HTTP) have no Irij
  expression — they're irreducibly Java.

Refactor goal: a stable "stdlib in Irij" surface for everything
*expressible* in Irij; Java for primitives that need raw type access
or are effect-transparent.

## Bench observations

Bytecode `std.list.fold` runs ~the same speed as the Java builtin
`fold` on `vec-sum` (both ~66ms at N=500). The .irj port is no
slower thanks to TCO + JIT inlining of `IrijFn.apply`.

Interpreter is ~3× slower for both (~210 ms) — tree-walking overhead
+ no JIT.
