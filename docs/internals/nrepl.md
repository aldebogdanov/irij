# nREPL

Network REPL — Clojure-flavoured wire protocol. Lets editors and
tools connect to a running Irij process for interactive eval.

Source: `dev.irij.nrepl.NReplServer` + `NReplSession`.

## Wire protocol

bencode over TCP. Each message is a map:

```
{"op": "eval", "code": "println 1", "id": "uuid", "session": "uuid"}
```

Standard ops:

| Op | Effect |
|---|---|
| `eval` | Parse + interpret. Stateful — bindings persist in the session. |
| `eval-bytecode` | Compile to JVM bytecode + run in a fresh class. **Stateless** — does not share bindings with the session. |
| `background-out` | Drain output from spawned threads since last eval. |
| `describe` | Lists supported ops + version. |
| `clone` | Create a child session. |
| `close` | Tear down the session. |

## Session state

Each session owns:

- An `Interpreter` instance with its own `globalEnv`.
- A `BackgroundOutputStream` collecting output from spawned threads.
- An `IndirectOutputStream` swapped between the BG buffer (default)
  and a per-eval `ByteArrayOutputStream` during eval ops.

Bindings (`x := 5`, `fn foo ...`) made in one eval persist for
subsequent evals in the same session — same as a Clojure REPL.

## `eval-bytecode` — limitations and design

The minimal bytecode-eval support compiles each snippet to a fresh
class `irij.NReplEval$N` and invokes its `main`. **No cross-eval
state**:

- Names defined in `eval` do not visible in `eval-bytecode`.
- Names defined in `eval-bytecode` do not persist to the session.
- Each `eval-bytecode` must be a self-contained program.

Useful for:

- Benchmarking compiled performance inside an interactive session.
- Verifying behaviour matches between modes (run the same snippet
  with `eval` and `eval-bytecode`, compare outputs).

Not useful for:

- Stateful REPL-driven development. Use `eval` for that.

## Why no shared state (yet)

A bytecode-backed stateful REPL needs a "namespace" abstraction in
the emitter:

- Top-level `:=` bindings stored in a per-session `Map<String, Object>`
  (the namespace).
- Each eval reads bound-var values from the namespace at the top of
  its class.
- New top-level binds get written back at the bottom.
- Fns defined in one eval must be callable from later evals: requires
  a stable indy site keyed by `(namespace, fn-name)` that
  `MutableCallSite.setTarget`s as fns are redefined.

The infrastructure exists in pieces (hot-redef in
`docs/internals/hot-redef.md`, classloader handling in
`compileSource`) but assembling it into a coherent "namespace"
abstraction is real work. Not blocking; tracked as future direction.

## `mode` parameter

`eval-bytecode` accepts `mode = "bytecode-sm"` (default) or
`"bytecode-threaded"`. Selects which effect-lowering strategy the
emitter uses for any `with` blocks in the snippet.

```
{"op": "eval-bytecode",
 "code": "with unsafe-jvm (println (Math/sqrt 2.0))",
 "mode": "bytecode-threaded"}
```

## Connect-to-running-JAR — not yet shipped

The deployed `irij.online` JAR doesn't currently expose an nREPL
port. Plans:

- Add `--nrepl-port N` to `irij build`'s manifest so deploys can boot
  with a port.
- Auth via shared-secret header (don't expose to public internet
  without it).
- Disable under `--direct-linking` builds (no hot-redef anyway).

Useful for: live patch deploy bots without redeploy. Documented
roadmap, not yet implemented.

## Sessions + concurrency

`NReplServer` accepts one connection at a time, runs each in a
virtual thread. Sessions are independent — no shared state between
sessions. The Emacs client opens one session per buffer; the web
playground opens one per browser tab.

## Output capture

Three streams matter:

| Stream | Captures |
|---|---|
| `IndirectOutputStream` (target = ByteArrayOutputStream) | Direct `println`/`print` during an eval. |
| `BackgroundOutputStream` | `println` from spawned threads after the parent eval returned. Drained by the next `eval` op (prefixed) or by an explicit `background-out` op. |
| `System.out` (for `eval-bytecode`) | Stdout of the compiled class's main. Temporarily swapped during eval-bytecode. |

The split exists because spawned vthreads outlive the eval that
created them. Long-running background work prints into the BG buffer
without colliding with subsequent eval output.

## Open gaps

- **Stateful eval-bytecode** — needs namespace abstraction.
- **Connect-to-running-JAR** — needs nREPL port in deployed JARs.
- **Bencode strictness** — currently accepts UTF-8 strings via lax
  decoder; spec-compliant clients should "just work" but edge cases
  with binary data aren't fuzz-tested.
- **`clone`** is registered in `describe` but not fully implemented.
