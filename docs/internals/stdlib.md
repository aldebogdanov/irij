# Stdlib

What lives in real `.irj` files vs Java. The split is purely about
hosting: Java-side builtins are reachable from the bytecode emitter
via `RuntimeSupport` static methods; `.irj` files get inlined +
compiled. Single execution model since v0.6.20 (R5d).

## Java side — `RuntimeSupport`/`Rt*` statics + `Builtins` registry

One implementation per builtin since the 2026-07 refactor: the
statics on `RtOps`/`RtMath`/`RtStrings`/`RtCollections`/`RtIo`/…
are the single source of truth (they are what call-position
intrinsics INVOKESTATIC), and the `Builtins` registry's
value-position `BuiltinFn` entries delegate to them.

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
- Crypto + auth (`sha256-hex`, `hmac-sha256-hex`, `random-token`)
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
| `std.log` | `Log` effect — leveled logging; `default-log`/`silent-log` handlers |
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
fns gain the same transparency with **parametric row variables**
(`(Fn):eff … ::: eff`; see `specs.md`). `::: Any` is rejected in
user code and no longer appears in stdlib source either — the
Phase-5 checker exemption for `std.*` remains only as transitional
headroom.

`std.list.fold` is declared `:: (Fn):eff _ _ _ ::: eff`, so
`fold (acc x -> println x) () v` type-checks exactly when the call
site's row has Console — the callback's row binds `eff` there. The
Java BuiltinFn fold was removed; the Irij-ported version is the
single source of truth.

Callers do `use std.list :open` (already imported by std.collection
and std.func; explicit elsewhere).

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

The general direction: stdlib is real Irij; Java provides the bare-
metal building blocks. Higher-order builtins like `fold`, `map`,
`filter` are *.irj* (`src/main/resources/std/list.irj`) — Java is only
where raw type access, JNI, or JVM-specific APIs need to live.

## Name conflicts: effect ops win over builtins

When a user declares `effect Log { log :: Str -> () }`, the symbol
`log` becomes an effect op in scope. It collides with the math
builtin `log` (= `Math.log`). **The effect op wins**: the emitter
checks `effectOps.containsKey(name)` before routing to a math
builtin, so `log "hello"` dispatches via `perform` and the
matching handler clause, never via `Math.log`.

If a program needs both `Math.log` and a `Log` effect in the same
module, the math one is reachable via Java interop:

```
use std.math :open

effect Log
  log :: Str -> ()

handler default-log :: Log
  log msg => resume ()

fn entropy :: Vec Float ::: Log
  (probs ->
    log "computing entropy"
    sum-of (@ (p -> p * (java.lang.Math/log p)) probs))
;;                  ^^^^^^^^^^^^^^^^^^^^ Math.log via interop;
;;                  the `log` effect op handles the perform above.
```

`Math/log` (the JVM static-ref form) bypasses the name-resolution
table entirely and goes straight to the JDK method. This pattern
generalises: any name collision between a stdlib builtin and a
user-declared effect op is resolved by qualifying the builtin via
its Java home.

## Why the split persists

- Some primitives (concurrency, raw-*, JDBC, HTTP, JVM interop) have
  no Irij expression — they're irreducibly Java.
- Direct emit (`INVOKESTATIC RT.foo`) for arithmetic + small list ops
  beats the `IrijFn.apply` dispatch path; keeping them as builtins
  avoids a needless allocation per call.

The principle: stdlib is real Irij where the language is enough;
Java only when it isn't.

## Bench observations

Bytecode `std.list.fold` runs ~the same speed as the direct-emit
`fold` builtin on `vec-sum` (both ~66 ms at N=500). The .irj port is
no slower thanks to TCO + JIT inlining of `IrijFn.apply`. There is no
interpreter to compare against — the only execution path is bytecode.
