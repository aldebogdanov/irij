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

- Arithmetic + comparison primitives (`add`, `sub`, `mul`, `quo`,
  `rem`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `++`, `&&`, `||`, `!`)
  — `quo`/`rem` are integer division and remainder. Not `div`/`mod`:
  `mod` is the module-decl keyword, so the old `mod` builtin could
  never be written down. The Java statics behind them keep the names
  `RtOps.div` / `RtOps.mod`, since those also back `/` and `%`.
- IO (`print`, `println`, `dbg`, `read-line`). `read-line` reads stdin through
  one shared reader, so piped input arrives line by line, and returns ()
  at the end of input
- Conversion (`to-str`, `to-vec`, `to-set`, `to-tuple`) —
  `to-set`/`to-tuple` are the dynamic-arity counterparts of the
  `#{}` and `#(...)` literals: a Tuple whose size is only known at
  runtime cannot be built any other way. `conj` appends to a Vector
  and adds to a Set (`conj #{1} 2` → `#{1 2}`). `to-set` collapses
  duplicates and keeps no order; `to-tuple` keeps order. `empty?` and
  `fold` take a Set as they take a Vector; a Set is walked in no
  particular order.
- Collection raw ops (`length`, `head`, `tail`, `nth`, `last`,
  `reverse`, `sort`, `concat`, `take`, `drop`, `keys`, `vals`, `get`,
  `assoc`, `contains?`, `range`, `empty?`, `conj`)

### Numbers — `RtNum`

The numeric tower (spec §1.3.1) lives in `RtNum`; `RtOps` keeps only
the 64-bit fast paths inline.

- **Int never wraps.** An Int is a `Long` while it fits and a
  `BigInteger` only when it doesn't — canonically, so a `BigInteger` Int
  is always outside the `long` range and `equals`/`hashCode` need no
  cross-representation cases (`RtNum.norm`). `RtOps.add`/`sub`/`mul`
  test for overflow with two or three ALU ops (`((x ^ r) & (y ^ r)) < 0`,
  `Math.multiplyHigh`) and only then call `RtNum`. Measured: fib +6%,
  a bare add/sub loop +13–20% (≈0.4 ns/iteration), multiply/modulo
  loops within noise; the alternatives were ~0% for "error on
  overflow" and ~2× slower adds for "BigInteger always".
- **Rational** — `Values.Rational(BigInteger num, BigInteger den)`,
  lowest terms, positive denominator, never `n/1` (`RtNum.ratio` returns
  an Int for a whole value). Int ⊕ Rational → Rational; anything ⊕
  Float → Float. `/` on two Ints truncates (as always); with a Rational
  operand it is exact. `**` is exact for an Int/Rational base and a
  non-negative Int exponent (refused past ~16 M result bits); `floor`,
  `ceil`, `round` return Ints and are exact on Ints (they used to go
  through a double, losing every Int past 2^53).
- Everything that reads numbers follows: `compare` / `==` (exact
  between Ints and Rationals), `parse-int` and Int literals of any size
  (`Expr.BigIntLit`), `json-parse` (an integral number of any size is an
  Int) and `json-encode`, the `Int` spec, `type-of`, JDBC binding, Java
  interop (a `BigInteger` result becomes an Int). An index or count
  argument past 64 bits is an error ("too large here"), not a silent
  truncation, and so is a Float one (`random-token 1.9`, `char-at s
  1.99`): `RtNum.longArg` used to truncate it.
- `parse-int` reads ASCII decimal (optional sign, surrounding space),
  `parse-float` ASCII decimal with an optional exponent (`1.5e3`, `.5`).
  The JDK parsers under them also took any Unicode digit (`１２３`,
  `٤٢`) and `1.5f`, `NaN`, `Infinity`, hex floats; those are refused.

### Persistent Vectors, Maps and Sets

`IrijVector`'s elements are a `PVec` and `IrijMap`'s entries a `PMap`
(`dev.irij.runtime`), each implementing the plain `java.util` interface
so code reading `.elements()` / `.entries()` is unchanged.

- `PVec` — Clojure's persistent vector: a 32-way trie plus a tail.
  `conj` and `++` append in amortised O(1) per element, `get` /
  replace are O(log₃₂ n). `tail` (and a `#[x ...rest]` pattern's rest)
  is an O(1) view that skips a prefix, compacted once the skipped part
  outweighs the live one.
- `PMap` — insertion-ordered, String keys. Up to 8 entries it is a flat
  `[k0 v0 k1 v1 …]` array scanned linearly (record-sized maps — request
  maps, JSON objects, `{status= … body= …}` — are the common case and
  build fastest this way); past 8, a hash array mapped trie for lookup
  plus a `PVec` of keys for order, with tombstones for removed keys
  until they outnumber live ones. Replacing a value keeps its place;
  removing and re-adding moves the key to the end — `LinkedHashMap`'s
  order, which it replaces. Keys whose `String.hashCode`s collide
  (trivial to craft: `"Aa"` and `"BB"`, and every concatenation of
  them) go into a second trie keyed by a per-JVM-seeded SipHash, so a
  request full of crafted JSON keys stays O(n log n) to parse —
  `LinkedHashMap` defended by treeifying; a flat collision list would
  have made it quadratic.

Each version shares structure with the one it came from. Before, every
`conj` / `assoc` / `tail` copied the whole collection (`List.copyOf`,
two `LinkedHashMap` copies), so building one element at a time was
quadratic: a 30 000-element `conj` loop took ~0.7 s and a 20 000-key
`assoc` loop ~6 s (now ~2 ms and ~7 ms); `head`/`tail` recursion over
20 000 elements went from ~0.7 s to ~2 ms.

`IrijSet`'s elements are a `PSet` — a HAMT over the elements'
`hashCode`s, no order. Fully colliding strings, numbers, keywords and
booleans go into a second trie keyed by the same seeded SipHash; other
colliding values share a flat list. A 20 000-element `conj` loop went
from ~6 s to ~6 ms.
- Math (`abs`, `min`, `max`, `pi`, `e`)
- Higher-order (`fold`)
- Concurrency (`spawn`, `await`, `sleep`, `par`, `race`, `timeout`,
  `try`)
- Crypto + auth (`sha256-hex`, `hmac-sha256-hex`, `pbkdf2-sha256-hex`,
  `constant-time-eq?`, `random-token`). `std.auth` stores passwords as
  `pbkdf2-sha256$<iterations>$<salt>$<hex>` (600 000 iterations) and
  compares secrets with `constant-time-eq?`; the pre-v0.9 single-SHA-256
  `<salt>$<hex>` format still verifies, and `password-needs-rehash?`
  flags it.
  - **Bytes are exact.** A Str is UTF-16, and one holding an unpaired
    surrogate (`json-parse "\udc00"`, `from-char-code 56320`, half a
    split emoji) has no UTF-8 encoding. `String.getBytes(UTF_8)` writes
    `?` for it, so the hash builtins hashed "alice" plus a lone U+DC00
    as "alice?" — a signature for one verified the other. They now go
    through `TextCodec.utf8`, which refuses it; `constant-time-eq?`
    compares UTF-16 code units, exactly what `==` compares.
  - `std.auth`'s checks stay Bool / Str: `verify-password` is false and
    `verify-signed-token` is `""` for input the hash refuses, and for a
    stored iteration count past the 100 000 000 `pbkdf2-sha256-hex`
    allows. `hash-password` refuses a salt holding `$` (the hash would
    never verify). A signed token may contain `.`: the signature is
    what follows the last one.
- URL encoding (`TextCodec`): `url-encode` / `url-decode` are the form
  encoding of a query string or form body (a space is `+`);
  `percent-encode` is RFC 3986's, for a path segment (only `A–Z a–z
  0–9 - . _ ~` stay, a space is `%20`). All refuse what has no exact
  encoding — an unpaired surrogate, `%`-escapes that aren't UTF-8
  (`%FF` and `%FE` both decoded to U+FFFD), a `%` without two hex
  digits — instead of substituting.
- Effect / handler internals (`raw-*` calls for HTTP, DB, SSE, session)

The capability providers in `dev.irij.runtime` are the other half of
the boundary: `FsCapability`, `JdbcCapability`, `HttpClientCapability`,
`ServeCapability`, `SessionCapability`, `TermCapability` and
`QuintCapability` (the only one that starts a process).

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
| `std.random` | `Random` effect (`rand-int`, `rand-float`, `rand-token`) + `default-random` handler. The builtins `random-int`, `random-float` and `random-token` perform these ops: a handler with the clause answers them (a test's fixed values), else the real source does — see effects.md § Builtins that perform an effect |
| `std.env` | `Env` effect + handler: `env-var`, and `env-args` (the arguments after the program, from the `program-args` builtin) |
| `std.log` | `Log` effect — leveled logging; `default-log`/`silent-log` handlers |
| `std.fs` | `FileIO` effect + handlers |
| `std.resource` | `Resource` effect — `resource-read`, `resource-exists?`: read-only access to the program's own `resources/` (bundled into the jar by `irij build`, else `resources/` under the working directory), nothing else; `default-resource` handler |
| `std.http` | HTTP client (`http-get`, `http-post`, `http-request` — the latter takes an optional `timeout-ms`) |
| `std.db` | `Db` effect + SQLite handler |
| `std.serve` | Web server framework (routes, middleware, request/response); serves the app's `resources/` directory as static files, nothing else. `route-prefix "/admin"` matches whole segments (`/admin`, `/admin/x`, not `/adminpanel`). A request's `path` is decoded, `query` raw, `params` decoded — see capabilities.md, *3c — Serve / SSE* |
| `std.session` | nREPL session effects |
| `std.proc` | `Proc` effect — child processes: `proc-run` (to completion, with stdin and a timeout) and `proc-start`/`proc-line`/`proc-send`/`proc-close`/`proc-wait`/`proc-kill` (streaming); `default-proc` handler |
| `std.term` | `Term` effect — raw-mode terminal I/O for TUI apps; `default-term` handler + `esc`/`csi`/`with-term` helpers |
| `std.datastar` | Datastar SSE protocol. Every line of a payload gets its key (`elements …`, `signals …`), so a line break in user text can't start a directive of its own; a selector or mode with a line break is refused. A GET's signals come from `request.params` |
| `std.json` | JSON parser + serialiser |
| `std.convert` | Type coercions (`to-int`, `to-float`, `to-bool`) |
| `std.test` | Test runner (`test`, `assert-eq`, `assert-throws`, ...). A failure's message keeps all its lines: the later ones are indented six spaces under `[FAIL]`, and `irij test` prints them (std.quint's divergence report runs over several) |
| `std.jvm` | `JVM` effect + `unsafe-jvm` handler |
| `std.quint` | `Quint` effect + model-based testing against a Quint spec — see [quint.md](quint.md) |
| `std.quint.itf` | ITF trace decoding, pure |

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

Callers import it themselves (`use std.list {fold map}`, or `:open`):
std.collection's and std.func's own `use std.list` doesn't reach their
importers. A bare `fold` without that import is the builtin.

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
| `to-vec` | `INVOKESTATIC RT.toVec` |
| `to-set` | `INVOKESTATIC RT.toSet` |
| `to-tuple` | `INVOKESTATIC RT.toTuple` |

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
use std.math :as math

effect Log
  log :: Str -> ()

handler default-log :: Log
  log msg => resume ()

fn entropy :: Vec Float ::: Log
  => probs
  log "computing entropy"
  math.sum-of (@ (p -> p * (java.lang.Math/log p)) probs)
;;                      ^^^^^^^^^^^^^^^^^^^^ Math.log via interop;
;;                      the `log` effect op handles the perform above.
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
