# Phase 2 — REPL & Interactive Runner

## What was built

### `IrijCli` — Command-Line Entry Point
`src/main/java/dev/irij/cli/IrijCli.java`

The main entry point for both file execution and the interactive REPL.

```
irij                       # launch interactive REPL
irij <file.irj>            # parse and run a source file
irij --parse-only <file>   # parse, report errors, no evaluation
irij --ast <file>          # dump parsed AST (debug)
irij --version             # print version
irij --help                # usage summary
```

Exit codes: `0` = success, `1` = parse/runtime error.

**Build and install:**
```bash
./gradlew compileJava          # compile only (fast)
./gradlew test                 # run all tests (194 total)
./gradlew test --rerun         # force re-run (Gradle caches passing tests)
./gradlew test -Pverbose       # show each test name + stack traces
./gradlew run                  # launch REPL (dev mode)
./gradlew run --args="file.irj" # run a file (dev mode)
./gradlew shadowJar            # build fat JAR → build/libs/irij.jar
./gradlew install              # install to ~/.local/bin/irij
```

Prerequisites: Java 25+, `~/.local/bin` on `$PATH`.

After `./gradlew install`:
```bash
irij                           # launch interactive REPL
irij examples/hello.irj        # run a file
```

### `IrijRepl` — Interactive REPL
`src/main/java/dev/irij/repl/IrijRepl.java`

Backed by **JLine3** for readline-style editing (history, cursor movement, `Ctrl-C` / `Ctrl-D`).

**Prompts:**
- `ℑ> ` — ready for new input
- `  | ` — continuation (inside an open indented block)

**Multi-line input detection:**
Tokenizes the accumulated buffer and counts INDENT vs DEDENT tokens. If INDENT count > DEDENT count, the block is still open and the REPL prompts for more input.

**REPL commands** (prefix `:`):
| Command | Description |
|---------|-------------|
| `:quit`, `:q` | Exit |
| `:reset` | Create a fresh interpreter (clear environment) |
| `:load <path>` | Load and evaluate a source file |
| `:type <expr>` | Stub — type inference not yet implemented |
| `:help` | Show command list |

**History** is persisted to `~/.irij_history`.

**Evaluation feedback:** Non-unit results are printed with `=>` prefix:
```
ℑ> 1 + 1
=> 2
ℑ> x := 42
ℑ> x
=> 42
```

### `IrijRunner` — Gradle dev entry point
`src/main/java/dev/irij/interpreter/IrijRunner.java`

A thin wrapper delegating to `IrijCli`, used by the Gradle `run` task during development (before the shadow JAR is built).

### Example programs — `examples/`

| File | Contents |
|------|----------|
| `hello.irj` | Hello World |
| `basics.irj` | Bindings, functions (lambda + match arms), pattern matching, sum types, product types, pipelines |
| `collections.irj` | Vectors, seq ops (`@`, `/?`, `/!`, `/+`, `/*`, `/#`, `/&`, `/^`, `/$`), operator sections `(+)`, `fold`, `nth`, `last`, maps, sets, ranges, destructuring |

Run them:
```bash
./gradlew run --args="examples/basics.irj"
./gradlew run --args="examples/collections.irj"
```

### Emacs package — `editors/emacs/irij-mode.el`

Major mode for `.irj` files. Install:
```elisp
(add-to-list 'load-path "/path/to/irij/editors/emacs")
(require 'irij-mode)
```
Or with `use-package`:
```elisp
(use-package irij-mode
  :load-path "path/to/irij/editors/emacs"
  :mode "\\.irj\\'")
```

**Features:**
- Syntax highlighting — keywords, type names (`PascalCase`), role names (`$ROLE`), keyword atoms (`:ok`), operators, string interpolation markers (`${}`), numeric literals, booleans, function/binding names
- 2-space indentation (block-opening lines auto-indent the next line)
- `;;` comment toggling
- REPL integration via `comint`

**Key bindings** (in `irij-mode` buffers):
| Key | Command |
|-----|---------|
| `C-c C-z` | Start / switch to REPL |
| `C-c C-r` | Send region to REPL |
| `C-c C-b` | Send buffer to REPL |
| `C-c C-l` | Send current line to REPL |
| `C-c C-c` | Toggle `;;` comments |

The REPL buffer uses `irij-repl-mode` (derived from `comint-mode`) with the same syntax highlighting and recognises the `ℑ> ` / `  | ` prompts.

## Known limitations

- **Multi-line pipelines** — now supported via implicit continuation (Phase 4.5a). A more-indented line starting with a binary operator auto-joins to the previous line. Does not trigger for `-` (unary negation ambiguity) or `/` (seq op ambiguity). Example:
  ```irij
  result := to-vec (1 .. 20)
    |> /? (n -> n % 2 == 0)
    |> @ (n -> n * n)
    |> /+
  ```

- **`:type` command** — stub, prints a placeholder message until type inference is implemented in a later phase.

- **Destructuring bindings** — vector and tuple patterns now work in bind position (Phase 4.5a):
  ```irij
  #[a b c] := #[1 2 3]
  #[first ...rest] := #[10 20 30 40]
  #(x y) := #(42 "hello")
  {name= n} := person
  ```

## Additional builtins

### `/^` generic reduce and `/$` scan

- `/^` — generic reduce (foldl1). Takes a 2-arg function, uses first element as init:
  ```irij
  #[1 2 3 4] |> /^ (+)                  ;; => 10
  #[3 1 5 2] |> /^ (a b -> if (a > b) a else b)  ;; => 5
  ```
- `/$` — scan (prefix sums). Like `/^` but emits all intermediate values:
  ```irij
  #[1 2 3 4] |> /$ (+)                  ;; => #[1 3 6 10]
  ```
- `fold fn init coll` — reduce with explicit initial value (works on empty collections):
  ```irij
  fold (+) 0 #[1 2 3 4]                 ;; => 10
  fold (+) 0 #[]                         ;; => 0
  ```

### Operator sections: `(+)`, `(-)`, `(*)`, etc.

Operators as first-class 2-arg functions via `(op)` syntax:
```irij
add := (+)
add 3 4            ;; => 7
#[1 2 3] |> /^ (+) ;; => 6
(==) 1 1           ;; => true
```
Supported: `+`, `-`, `*`, `/`, `%`, `**`, `++`, `==`, `/=`, `<`, `>`, `<=`, `>=`, `&&`, `||`.

### `nth`, `last`, `get` on vectors

```irij
nth 0 #[10 20 30]      ;; => 10 — throws on out-of-bounds
nth 2 #[10 20 30]      ;; => 30
last #[10 20 30]       ;; => 30
get 1 #[10 20 30]      ;; => 20 — returns () on out-of-bounds
get "name" {name= "Jo"} ;; => Jo — also works on maps (unchanged)
```

### `length` vs `/#`

- `length` — builtin function, works on vectors, strings, maps, sets, tuples, ranges
- `/#` — seq op, works on collections only; composable via pipeline (`nums |> /#`)

Both return the count. `/#` is concise in pipelines; `length` handles more types.

## File structure

```
src/main/java/dev/irij/
  cli/
    IrijCli.java          # main entry point
  repl/
    IrijRepl.java         # JLine3 REPL
  interpreter/
    IrijRunner.java       # Gradle dev entry point
examples/
  hello.irj
  basics.irj
  collections.irj
editors/
  emacs/
    irij-mode.el          # Emacs major mode
```
