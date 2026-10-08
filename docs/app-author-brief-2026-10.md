# Irij engine changes (October 2026) — brief for application code

You are writing an application in Irij. The engine underneath it changed:
a security/correctness/performance audit plus three language decisions.
This page lists everything that can change how **your** code behaves,
what you must check, and what you can now do. Read it once before
continuing; the checklist at the end is the short version.

All of it is released as **Irij v0.9.270**, except the import rules
(§2, *Imports are enforced*), released as **v0.9.277**. Update your CLI
before continuing: download `irij.jar` from the latest GitHub release, or
build from `main` with `./gradlew install`. Check with `irij --version`.

---

## 1. Language changes

### Int never overflows
`Int` is arbitrary precision. It is a 64-bit integer internally while it
fits; a result that doesn't fit becomes a big integer instead of
wrapping. `9223372036854775807 + 1` is `9223372036854775808` (it used to
be `-9223372036854775808`).

- Integer literals, `parse-int` and `json-parse` accept any size; a big
  integer prints, compares, hashes and JSON-encodes like any Int, and
  satisfies the `Int` spec.
- `==`, `<`, `>`… are exact between Ints (two different Ints past 2^53
  used to compare equal).
- `floor`, `ceil`, `round` return an Int, and on an Int return it
  unchanged (they used to go through a double).
- `**` with an Int base and a non-negative Int exponent is an exact Int
  (`2 ** 100` is `1267650600228229401496703205376`, no longer a Float).
  A negative exponent still gives a Float.
- An index or count that is past 64 bits (`nth`, `take`, `substring`,
  ranges…) is an error, never a silent truncation.
- Cost: none you need to design around (≈0–20% on the tightest pure
  arithmetic loops, nothing measurable in ordinary code). Don't add
  manual overflow guards.

### Rationals
`2/3` — digits, slash, digits, **no spaces** — is an exact `Rational`
literal. It is kept in lowest terms and a whole value is an Int
(`4/2` is `2`, `6/4` is `3/2`). It prints as `2/3`, its `type-of` is
`"Rational"`, and the `Rational` spec checks it.

- Int with Rational → Rational; anything with a Float → Float.
  `1/3 + 2/3` is `1`; `7/2 * 2` is `7`; `1/4 + 0.5` is `0.75`.
- **`/` between two Ints still truncates**: `7 / 2` is `3`. It is exact
  only when a Rational is involved: `7/2 / 1/2` is `7`.
- `floor`, `ceil`, `round` on a Rational are exact Ints.
- `json-encode` writes a Rational as a JSON number (its double value).
- **Watch the spacing.** `10/2` is the Rational literal `5`, not a
  division expression. With an identifier, `n/2` is still division. Put
  spaces around `/` when you mean division: `a / b`.

### A bare inline `if` can't be applied or be an operand
An inline `if` takes one term per part, so `if c a else f x` used to
mean `(if c a else f) x` and `if c 1 else n + 1` meant
`(if c 1 else n) + 1` — the opposite of how they read. Both are now
**compile errors** that name the fix. Write what you mean:

```
if c a else (f x)        ;; call f in the else branch
(if c f else g) x        ;; call whichever function the if returns
if c f else g ~ x        ;; same as above: ~ applies everything to its left
(if c 1 else n) + 1
```

A bare inline `if` that is a whole expression — a binding's value, a
fn body, a parenthesized argument — is fine as before. Applying a
parenthesized `if` with a literal branch (`(if c false else g) x`) is
also a compile error, since `false` can never be called.

---

## 2. Behaviour changes that can affect existing code

### Modules
- **Private names are private.** A module's non-`pub` top-level fns,
  bindings, handlers and caps can no longer be reached (or replaced)
  from outside it. If your code called a module's non-`pub` fn, that is
  now "Unknown function" — make the fn `pub` (with a spec). In return,
  your own names can no longer silently replace a library's internals
  (a program that defined `find-route` used to rewire `std.serve`'s
  router), and a parameter you call `server`, `db-jdbc`, … is just a
  value again.
- **A module's `pub` fns are its own, too.** Before, if your program
  defined a fn with the same name as a `pub` fn of a module you `use`,
  the last definition won **even for calls inside that module** — your
  `shout` silently replaced the library's `shout` everywhere. Now each
  module's code always calls its own `pub` fns (and plain `pub` bindings).
  Your own top-level `shout` shadows the imported one *for your code
  only*. With `use m :as a`, `a.shout` always means the module's
  `shout`, even if you have one too.
- **Imports are enforced (v0.9.277).** A file reaches only what it
  declares, what its own `use` lines import, and builtins. Before, every
  `pub` name of every loaded module resolved bare everywhere: `use m :as
  m` or `use m {a}` still let you write `b`, a name another file imported
  worked in yours, and a module's `pub fn length` replaced the builtin
  `length` in every file. Now each of those is a compile error that names
  the module and the `use` line to add:
  `` `twice` is not imported here: it is lib.m's; import it with `use lib.m {twice}`, or write `m.twice` after `use lib.m :as m` at main.irj:2:10 ``.
  Specs, effects, protos and newtypes are imported by name and bring
  their members: `use m {Mode}` brings the spec and its variants, `use m
  {Tick}` the effect and its ops — also when you only name it in a row
  (`::: FileIO` needs `use std.fs {FileIO}`). They aren't reached through
  an alias (`m.Calm` is an error). Builtin effects (`Console`, `Time`,
  `Env`, `Random`, `JVM`) and specs (`Int`, `Str`, `Ok`, `Err`, …) need no
  import. `use m {nope}` and `m.nope` for a name `m` doesn't export are
  errors too (they used to be silently ignored). A name two `:open`
  imports both export is an error where you use it: import one `:as`. A
  local now shadows an alias (`(m -> m.x)` is the parameter's field).
  `pub use m {names}` re-exports. In a REPL, an eval keeps the imports of
  the evals before it.
- **Spec-lint is back.** `irij <file>` and `irij build` print a warning
  on stderr for every `pub fn` without a `::` spec annotation, including
  in your own modules and seeds. Add the specs (use `_` where the shape
  is open); don't silence it with `--no-spec-lint`.
- Each program run (and each REPL/Playground session) has its own
  `spec` declarations; two sessions declaring `spec Person` no longer
  see each other's.

### HTTP (`std.serve`)
- **Static files come only from `resources/`** (on the classpath in a
  built JAR, `./resources/` when run from source). Before, `serve`
  handed out *any* file under the working directory — `.env`, your
  source, your database. If your app relied on a file outside
  `resources/` being served, move it into `resources/` or serve it from a
  route. A path containing a `..` or `.` segment is never static.
- **Response headers may not contain CR, LF or NUL.** Such a response
  becomes a 500. If you copy request data (a filename, a redirect
  target) into a header, validate it first.
- A handler error gives a bare `500 Internal Server Error`; the message
  goes to the server's stderr only. Don't expect error details in the
  response body.
- Query params: keys are now URL-decoded too, and values are split from
  the raw query — `%26` and `%2B` inside a value now arrive as `&` and
  `+` (they used to cut the value / turn into a space). A stray `%` is
  kept literally instead of failing the request.
- Request bodies sent with `Transfer-Encoding: chunked` are now read
  (they used to arrive empty). `Expect: 100-continue` is answered.
  Malformed requests get 400/413/431/501 before your handler runs.
- The whole request must arrive within 30 s. Bodies are buffered whole
  and capped at 256 MiB; `-Dirij.http.max.body=<bytes>` lowers the cap.
- The router's default 404 page HTML-escapes the path.
- SSE: an event *name* containing a line break is an error; *data* is
  split on CR, LF and CRLF (a bare CR used to let a payload inject its
  own event). Held SSE streams send a heartbeat every 5 s.

### Auth (`std.auth`)
- `hash-password salt pw` now stores
  `"pbkdf2-sha256$600000$<salt>$<hex>"` (PBKDF2-HMAC-SHA256) instead of
  one SHA-256. It is **deliberately slow (~0.2 s per hash)**: call it on
  signup and password change, never in a loop or per request, and expect
  tests that hash to take that long.
- `verify-password stored pw` accepts both the new format and the old
  `"<salt>$<sha256>"` one. After a successful login, if
  `password-needs-rehash? stored` is true, store a fresh
  `hash-password` of the password — that upgrades old hashes in place.
- `verify-signed-token` and `verify-password` compare in constant time.
  Compare any secret you handle yourself (an API key, a MAC) with
  `constant-time-eq? a b`, never `==`.

### Concurrency
- **Cancellation now actually stops work.** When `timeout` fires, a
  `race` has a winner, or a scope is cancelled, the losing fiber is
  interrupted and stops at its next loop iteration or `sleep` (with an
  error "cancelled: the computation was interrupted"). Before, CPU-bound
  fibers kept running and a cancelled `sleep` loop busy-spun. Don't
  write code that catches that error and carries on — the cancellation
  is sticky and will stop it again.
- `sleep` in an interrupted fiber now raises instead of returning early.

### Collections and strings
- `take n` / `drop n` with a negative `n` take/drop nothing (it used to
  crash); past the end they take/drop everything.
- `upper-case` / `lower-case` ignore the machine's locale.
- `chars` / `split s ""` split by Unicode code point (an emoji stays one
  element).
- `substring`, `char-at`: an index past 64 bits is an error.
- `json-parse`: an integral number of any size is an Int; errors read
  `json-parse: <what's wrong>`. `url-decode` of a malformed `%` escape
  is an Irij error.
- `from-char-code` of a non-code-point is an Irij error.

### Input and test output
- `read-line` reads piped stdin line by line (it used to lose everything
  after the first line) and returns `()` at the end of input — a real
  `()`, so `if (line == ())` now ends a read loop (before, it never did,
  and such a loop ran forever).
- `irij test` prints the whole message of a failing test, every line
  (std.quint's divergence report used to be cut to its first line).

### Tooling
- `irij --nrepl-server` listens on localhost only
  (`-Dirij.nrepl.host=...` to change; it is unauthenticated code
  execution). `irij build --nrepl-port` is refused (it never worked).
- The MCP server's `irij_eval` / `irij_run` time out after 120 s.
- Absurdly deep nesting (more than 512 brackets) is a parse error.
- A deep recursion's stack overflow is reported as such (it used to be
  masked by an internal error).

---

## 3. New things you can use

- Big Ints and Rationals (above).
- `http-request {url= … timeout-ms= 5000}` — a whole-request deadline
  for the HTTP client. (The client is now shared and has a 30 s connect
  timeout; before, a dead server hung the call forever.)
- `pbkdf2-sha256-hex pw salt iterations`, `constant-time-eq? a b`,
  `password-needs-rehash? stored`, `pbkdf2-iterations ()`.
- `timeout` / `race` / scopes are now reliable for bounding CPU-heavy
  work.

---

## 4. Performance — what changed and how to write for it

Vectors, maps and sets are now **persistent data structures**: `conj`,
`assoc`, `dissoc`, `tail`, `++` and pattern rests (`#[x ...rest]`)
share structure instead of copying the whole collection. Before, they
copied everything, so building a collection one element at a time was
quadratic.

| Operation | Before | Now |
|---|---|---|
| `conj` 30 000 elements into a vector | ~690 ms | ~2 ms |
| `assoc` 20 000 keys into a map | ~5.8 s | ~7 ms |
| `conj` 20 000 elements into a set | ~6 s | ~6 ms |
| `head`/`tail` recursion over 20 000 | ~730 ms | ~2 ms |
| 2 M small record literals | ~127 ms | ~71 ms |
| `fib 32` with `:: Int Int` specs | ~159 ms | ~59 ms |

So:
- Building results incrementally — `fold` with `conj`/`assoc`,
  recursion with `head`/`tail`, accumulating with `acc ++ #[x]` — is now
  the normal, efficient way. Rewrite any workaround you made to avoid it.
- Maps of up to 8 keys (records, request maps, small JSON objects) are
  stored flat and are cheapest of all; don't avoid record-shaped maps.
- Primitive specs (`Int`, `Str`, `Bool`, `Vec`, …) cost one type check;
  keep annotating — specs are not a performance trade-off.
- Maps and sets stay fast even when fed adversarial keys (hash-collision
  floods), so building them from request data is safe.
- The slow things are now deliberate: password hashing (~0.2 s) and
  anything you do with `JVM`/`proc`/network.

---

## 5. Checklist for your codebase

1. Search for inline `if`s used as functions or operands
   (`if … else f x`, `if … else a + b`, `… |> if …`): the compiler will
   point at each; parenthesize as in §1.
2. Search for digit`/`digit with no spaces meant as division (`10/2`):
   it is now a Rational literal. Use `a / b`.
3. Anything relying on 64-bit wraparound (hashing, checksums) must now
   apply the modulus explicitly (`% 18446744073709551616`).
4. Calls into another module's non-`pub` fns: make them `pub` with a spec.
5. Your own fn names that collide with `pub` fns of modules you use are
   now harmless to the modules, but your code sees *yours* — make sure
   that's what you mean; use `alias.name` (`use m :as alias`) to call
   the module's.
6. Imports (v0.9.277): compile and run everything once; each "is not
   imported here" error names the `use` line to add. Expect them for
   variants used after `use m :as m` (add `use m {Spec}`), for effects
   named in rows (`use std.fs {FileIO}`, `use std.proc {Proc}`), for
   names a module got through another module's `:open`, and for
   `use m {names}` lists naming something `m` doesn't export.
7. Static assets: everything the browser fetches directly must be under
   `resources/`.
8. Response headers built from request data: validate (no CR/LF).
9. Stored password hashes: keep `verify-password`; add
   `password-needs-rehash?` + re-hash on successful login. Don't call
   `hash-password` per request.
10. Secrets compared with `==`: switch to `constant-time-eq?`.
11. Fibers that catch errors around `sleep` or loops to keep going after
    cancellation: let cancellation end them.
12. `pub fn`s without specs: the build now warns — add specs.
13. Remove any workaround for slow `conj`/`assoc`/`tail`.
