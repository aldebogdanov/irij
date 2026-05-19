# Phase 2.5 — Interactive Development Foundation

## What was built

### VarCell — Hot Redefinition Support

Top-level bindings (function declarations, type constructors, and `:=` bindings at the global scope) now use `VarCell` instead of `ImmutableCell` in the environment. This is Irij's equivalent of Clojure's Var system.

**Key semantics:**
- When a function or binding is redefined at the top level, the existing `VarCell` is updated **in-place** rather than replaced.
- Closures that captured a child of the global environment resolve names through the parent chain at call time — they see the updated definition.
- Local bindings (inside function bodies) still use `ImmutableCell` — they are not redefinable.
- The `<-` assignment operator does NOT work on `VarCell` — top-level bindings are semantically immutable from the user's perspective. Only re-declaration (a new `:=`) updates them.
- Builtins (`println`, `head`, etc.) use `ImmutableCell` — they are infrastructure, not user-redefinable.

**Example:**
```irij
fn helper
  (x -> x + 1)

fn caller
  (x -> helper x)

;; Redefine helper — caller sees the new version
fn helper
  (x -> x + 100)

caller 5   ;; => 105
```

**Architecture:**
- `Environment.VarCell` — mutable cell with `volatile Object value` (thread-safe for nREPL)
- `Environment.defineVar(name, value)` — update in-place if VarCell exists, else create new
- `Interpreter.defineInScope(env, name, value)` — routes to `defineVar` at global scope, `define` locally

### nREPL Server

A TCP server implementing the nREPL protocol (bencode wire format) for editor integration.

**Starting the server:**
```bash
irij --nrepl-server              # default port 7888
irij --nrepl-server=9999         # custom port
./gradlew run --args="--nrepl-server"
```

On startup, the server writes a `.nrepl-port` file in the current directory (standard editor convention). Editors like CIDER, vim-fireplace, and Conjure look for this file to auto-connect.

**Supported ops:**

| Op | Description |
|----|-------------|
| `clone` | Create a new session (returns `new-session` ID) |
| `eval` | Evaluate code in a session (returns `value`, `out`, `status`) |
| `describe` | Server capabilities and version |
| `close` | Close a session |

**Session model:**
- Each session has its own `Interpreter` and `Environment`
- State persists within a session (bindings, function definitions)
- Multiple sessions are independent
- Sessions run on Java virtual threads (one per connection)

**Output capture:**
The nREPL server uses `IndirectOutputStream` to redirect `println`/`print` output per-evaluation. This solves the "captured PrintStream" problem — builtins close over the `PrintStream` at interpreter construction, but the underlying stream target is swapped per-eval to capture stdout.

**Wire format:**
Bencode (same as Clojure nREPL). Self-contained implementation in `Bencode.java`, no external dependencies.

### `:env` REPL Command

Inspect current bindings in the interactive REPL:

```
ℑ> x := 42
ℑ> fn double
  | (x -> x * 2)
ℑ> :env
  double               : Lambda       = <fn double> [var]
  x                    : Long         = 42 [var]

ℑ> :env all
  ;; shows all bindings including builtins (println, head, tail, etc.)
```

**Flags:**
- `:env` — user-defined bindings only (hides builtins, `true`/`false`, `pi`, `e`)
- `:env all` — all bindings including builtins

Each binding shows: name, runtime type, value preview (truncated at 60 chars), and cell kind (`[var]` for VarCell, `[mut]` for MutableCell).

## Build & Run

```bash
./gradlew compileJava              # compile only
./gradlew test                     # run all tests (235 total)
./gradlew test --rerun             # force re-run
./gradlew test -Pverbose           # show test names
./gradlew run                      # launch REPL
./gradlew run --args="--nrepl-server"  # start nREPL server
./gradlew shadowJar                # build fat JAR
./gradlew install                  # install to ~/.local/bin/irij
```

After `./gradlew install`:
```bash
irij                               # REPL
irij --nrepl-server                # nREPL server
irij examples/collections.irj     # run a file
```

## File Structure

```
src/main/java/dev/irij/
  interpreter/
    Environment.java       # VarCell added to Cell hierarchy
    Interpreter.java       # defineInScope() routes var/immutable
  repl/
    IrijRepl.java          # :env command added
  cli/
    IrijCli.java           # --nrepl-server flag
  nrepl/
    IndirectOutputStream.java  # swappable output stream
    Bencode.java              # bencode codec
    NReplSession.java         # session state + op handling
    NReplServer.java          # TCP server

src/test/java/dev/irij/
  interpreter/
    InterpreterTest.java   # VarRedefinition nested class (7 tests)
  nrepl/
    BencodeTest.java       # bencode round-trip tests (14 tests)
    NReplSessionTest.java  # session eval/state tests (12 tests)
    NReplServerTest.java   # TCP integration test (1 test)
```

## Test Summary

| Suite | Tests |
|-------|-------|
| ParserSmokeTest | 64 |
| InterpreterTest | 137 (130 original + 7 VarRedefinition) |
| BencodeTest | 14 |
| NReplSessionTest | 12 |
| NReplServerTest | 1 |
| **Total** | **235** (all passing) |

## Design Decisions

### Why VarCell instead of just overwriting ImmutableCell?

`define()` already silently overwrites, so redefinition "works" accidentally. VarCell makes the indirection **explicit**:

1. **In-place update** — the cell object stays the same; only its value changes. Closures holding a child environment see the update through the parent chain.
2. **Thread safety** — `volatile` value field supports nREPL sessions on virtual threads.
3. **Future bytecode compilation** — VarCell maps cleanly to `invokedynamic` call sites when we add a JIT compiler.
4. **Dependency tracking** — VarCell can be extended with watchers/callbacks for "dependents auto-recompute" (spec §8.1).

### Why not allow `<-` on VarCell?

The user declared the binding with `:=` (immutable). The VarCell mechanism is for *redefinition* (a new `:=` declaration), not *mutation* (`<-`). This preserves the semantic distinction between immutable and mutable bindings while supporting REPL-driven development.

### Why bencode, not JSON?

nREPL uses bencode as its wire format. By implementing bencode, we get compatibility with the entire nREPL ecosystem (CIDER, vim-fireplace, Conjure, etc.) without requiring any adapter.
