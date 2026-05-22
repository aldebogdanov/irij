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
| `eval` | Compile to JVM bytecode + run. Stateful: top-level `:=` binds AND `fn` definitions carry across evals via a per-session namespace map. |
| `eval-bytecode` | Explicit alias for `eval`. Kept for editor clients that send the older op name. |
| `background-out` | Drain output from spawned threads since last eval. |
| `describe` | Lists supported ops + version. |
| `clone` | Create a child session. |
| `close` | Tear down the session. |

## Session state

Each session owns:

- A `BytecodeSession` with its own session-scoped `ClassLoader` and
  `ConcurrentHashMap<String, Object>` namespace map.
- A `BackgroundOutputStream` collecting output from spawned threads.
- An `IndirectOutputStream` swapped between the BG buffer (default)
  and a per-eval `ByteArrayOutputStream` during eval ops.

Bindings (`x := 5`, `fn foo ...`) made in one eval persist for
subsequent evals in the same session — same as a Clojure REPL.

## `eval-bytecode` — stateful via namespace

Each snippet compiles to a fresh class `irij.NReplEval$N` in the
session's shared classloader. **Top-level `:=` binds persist across
evals** via a per-session namespace map shared on the thread.

Mechanism:

- `NReplSession` owns `bytecodeNamespace: ConcurrentHashMap<String, Object>`.
- Before each invoke, the session sets `RuntimeSupport.NS` (a
  ThreadLocal) to that map; restores on finally.
- The emitter compiles with `CompileOptions.namespaceMode = true`.
  In that mode:
  - Top-level `Stmt.Bind` with a simple-name target emits an extra
    `INVOKESTATIC RT.nsPut(name, value)` after the local-slot ASTORE.
  - `emitVarLoad` falls back to `INVOKESTATIC RT.nsGet(name)` when
    the name isn't in locals / lifted / handlers / user fns.

Round-trip:

```
eval-bytecode "x := 5"        → nsPut("x", 5)
eval-bytecode "println x"     → nsGet("x") = 5; prints "5"
eval-bytecode "x := 100"      → nsPut("x", 100)  (overrides)
eval-bytecode "println x"     → 100
```

Sessions isolate: each `NReplSession` has its own map; namespaces
don't leak between sessions.

Tests: `NReplBytecodeStateTest`.

## What's still not cross-eval

Top-level **fn defs** in one eval can't be called from later evals
yet. Each eval is its own class; `f$impl` from eval N is not
visible in eval N+1. To support this, each `fn` decl would need:

- A registered `IrijFn` wrapper stored in the namespace map (keyed
  by fn name).
- Call sites in subsequent evals routed through `nsGet(name)` →
  `IrijFn.apply(args)`. (Already done via the existing
  user-fn-as-value path when `emitVarLoad` falls through to the
  namespace.)

The wrapper-registration piece is wired-up-but-not-shipped: when
`emitFn` runs in namespaceMode, it would need to also emit code that
runs at class-load time to register an IrijFn pointing at the
emitted method. Possible via `LambdaMetafactory` + a static-init
clinit block. Not yet implemented; tracked future direction.

What works today: data values (`:=`), expressions reading prior
state. Re-defining fns: only within a single eval (`fn` + caller in
the same snippet).

## `mode` parameter (removed)

Old clients sent `mode = "bytecode-sm"` / `"bytecode-threaded"` to
pick a lowering strategy. v0.6.13 removed the threaded back-end, so
only one mode exists. The field is silently ignored for backwards
compat with old client builds.

## Connect-to-running-JAR

`irij build --nrepl-port=N entry.irj -o app.jar` writes an
`Irij-NRepl-Port` manifest entry. When the bundled JAR boots,
`IrijCli.runBundled` checks the manifest, starts an
`NReplServer` on the configured port in a background vthread,
then runs the entry on main. The JVM stays alive as long as the
nREPL socket is open — attach an editor, send `eval` ops, live-patch
fns.

```
irij build --nrepl-port=7888 server.irj -o server.jar
java -jar server.jar
# Embedded nREPL listening on 7888 (connect with: irij nrepl-connect localhost:7888)
```

Caveat: the embedded nREPL gets its OWN `BytecodeSession`, not
the entry's. So it can't read the running app's bindings directly.
What works:

- Redefine top-level fns via `eval` — the nREPL's `BytecodeSession`
  updates its namespace map; if the entry uses hot-redef indy sites
  (which it does by default), the swap propagates.
- Ad-hoc eval against the bundled stdlib.

What doesn't:

- Inspect or mutate the entry's local state from nREPL. Would need
  the embedded session to share the entry's namespace map +
  classloader. Future work.

Auth: not implemented. Don't expose the port to the public internet
without a TCP-level guard (firewall rule, SSH tunnel, etc.).

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
