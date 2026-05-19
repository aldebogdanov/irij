# Phase 12b — nREPL Playground

## Overview

A web-based playground for evaluating Irij code in the browser. Code is sent to a backend server which runs it in a sandboxed interpreter with timeout protection.

## Architecture

```
Browser (CodeMirror editor)
  → POST /eval {code, timeout}
  → raw-nrepl-eval-sandboxed (Java)
    → new Interpreter(out, sandboxed=true)
    → CompletableFuture with timeout
  ← {value, stdout, error, ok}
```

### Sandboxed Interpreter

`Builtins.installSandboxed(env, out)` installs all standard builtins, then replaces dangerous ones with error stubs:

**Blocked operations:**
- File I/O: `read-file`, `write-file`, `delete-file`, `append-file`, `make-dir`, `list-dir`, `file-exists?`
- HTTP: `raw-http-request`, `raw-http-serve`
- Database: `raw-db-open`, `raw-db-query`, `raw-db-exec`, `raw-db-close`, `raw-db-transaction`

**Allowed:** All pure computation, `println`/`print`, effects, specs, contracts, pattern matching, collections, etc.

### `raw-nrepl-eval-sandboxed`

Built-in function (arity 2): `raw-nrepl-eval-sandboxed code timeout-ms`

Returns `IrijMap`:
```
{value= "result" stdout= "printed output" error= () ok= true}   ;; success
{value= () stdout= "" error= "message" ok= false}               ;; failure
```

Each evaluation creates a fresh sandboxed `Interpreter` instance — no state leaks between evals.

## Playground Server

`playground/server.irj` — Irij application using `std.serve`:

| Endpoint | Description |
|----------|-------------|
| `GET /` | Redirect to `/index.html` |
| `GET /index.html` | Static HTML/JS/CSS (CodeMirror editor) |
| `POST /eval` | Evaluate code, return JSON result |
| `GET /api/examples` | Return curated example snippets |

### Running

```bash
./gradlew shadowJar
java --enable-native-access=ALL-UNNAMED -jar build/libs/irij.jar playground/server.irj
# Open http://localhost:8081
```

## Frontend Features

- **Dark theme** (Tokyo Night-inspired)
- **CodeMirror 5** editor with line numbers
- **Ctrl+Enter / Cmd+Enter** keyboard shortcut to run
- **Examples dropdown** — 6 curated examples (Hello World, Fibonacci, Pattern Matching, Collections, Algebraic Effects, Specs)
- **Share button** — encodes code as base64 in URL fragment, shareable link
- **Output panel** — shows stdout, return value, and errors with color coding
- **Status bar** — shows execution time and ready/running/error state

## Test Coverage

- **Java tests**: 13 tests in `PlaygroundTest.java`
  - 4 sandbox tests (blocks file ops, blocks DB, allows pure computation, allows println)
  - 9 eval tests (simple expression, stdout, parse error, runtime error, sandbox blocking, timeout, multiple evals, function definition)
- Total test suite: 656 Java + 264 integration = 920 tests

## Files Created/Modified

- `Builtins.java` — added `installSandboxed()`, `SANDBOX_FORBIDDEN` list
- `Interpreter.java` — added sandboxed constructor, `raw-nrepl-eval-sandboxed` builtin
- `playground/server.irj` — playground server application
- `playground/index.html` — frontend (CodeMirror + custom UI)
- `PlaygroundTest.java` — Java tests

## Future Work

- CodeMirror Irij syntax highlighting mode
- Persistent nREPL sessions (state preserved across evals)
- Server-side snippet storage (DB-backed permalinks)
- Rate limiting / abuse prevention
