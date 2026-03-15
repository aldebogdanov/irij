# Emacs nREPL Client for Irij

`irij-nrepl.el` provides a lightweight, zero-dependency nREPL client that connects to a running `irij --nrepl-server` process and lets you evaluate Irij code directly from your editor — with results in the minibuffer and full output history in a dedicated buffer.

## Quick Start

```bash
# 1. Start the nREPL server (in a terminal or from Emacs M-x shell)
irij --nrepl-server          # listens on port 7888, writes .nrepl-port
irij --nrepl-server=9000     # custom port
```

```emacs-lisp
;; 2. In Emacs — open any .irj file and connect (auto-reads .nrepl-port)
C-c C-n        ;; irij-nrepl-connect
               ;; → "ℑ Connected to nREPL on localhost:7888  (session a3f2bc1e…)"
```

```bash
# 3. Eval code
C-x C-e        ;; eval expression at point  → minibuffer: "ℑ ⇒ 42"
C-c C-d        ;; eval top-level fn/type/binding
C-c C-k        ;; eval entire buffer
C-c M-r        ;; eval selected region
C-c C-o        ;; pop to *irij-nrepl* output buffer
```

## Installation

Add both Elisp files to your `load-path`. `irij-mode.el` will automatically load `irij-nrepl.el` if it is found:

```emacs-lisp
(add-to-list 'load-path "/path/to/irij/editors/emacs")
(require 'irij-mode)   ;; irij-nrepl loaded automatically
```

With `use-package`:

```emacs-lisp
(use-package irij-mode
  :load-path "~/dev/irij/editors/emacs"
  :mode "\\.irj\\'")
```

No external packages required — no CIDER, no Monroe, no MELPA.

## Key Bindings

All nREPL bindings are active in `irij-mode`. They coexist with the existing comint subprocess REPL (`C-c C-z`, `C-c C-r`, etc.).

| Key | Command | Description |
|-----|---------|-------------|
| `C-c C-n` | `irij-nrepl-connect` | Connect (auto-reads `.nrepl-port`) |
| `C-x C-e` | `irij-nrepl-eval-last-sexp` | Eval expression ending at point |
| `C-c C-e` | `irij-nrepl-eval-last-sexp` | Same |
| `C-c C-d` | `irij-nrepl-eval-defun` | Eval top-level declaration at point |
| `C-c C-k` | `irij-nrepl-eval-buffer` | Eval entire buffer |
| `C-c M-r` | `irij-nrepl-eval-region` | Eval selected region |
| `C-c C-o` | `irij-nrepl-show-result-buffer` | Pop to output buffer |

## Hot Redefinition

Redefining a function is just another eval. Because the nREPL server uses
`VarCell` for top-level bindings, all callers immediately see the new version:

```irij
;; In your .irj file:
fn greet
  (name -> "Hello, " ++ name ++ "!")
```

Press `C-c C-d` to eval the `fn` declaration — defined in the server.

```irij
fn greet
  (name -> "Hey " ++ name ++ " 👋")
```

Press `C-c C-d` again — **hot redefined**. Any function that calls `greet`
now uses the new version, without restarting the server.

## Auto-Connect

If `.nrepl-port` exists in (or above) the current directory, the first eval
command auto-connects without prompting:

```
C-x C-e   ;; → auto-connects, then evals → "ℑ ⇒ 3"
```

The `.nrepl-port` file is written by `irij --nrepl-server` on startup and
deleted on shutdown — same convention as Clojure's nREPL.

## Output Buffer `*irij-nrepl*`

All eval results and stdout are appended to `*irij-nrepl*` (open with `C-c C-o`):

```
;; ─────────────────────────────
;; → 42

;; ─────────────────────────────
;; stdout:
;;   Hello, world!
;; → ()

;; ─────────────────────────────
;; error: name not found: foo
```

In the output buffer:
- `g` or `C-c C-c` — clear buffer
- `q` — close window

## Customisation

```emacs-lisp
(setq irij-nrepl-host         "localhost")  ;; server host
(setq irij-nrepl-default-port 7888)         ;; fallback port if no .nrepl-port
(setq irij-nrepl-result-buffer "*irij-nrepl*")  ;; output buffer name
```

## Architecture

`irij-nrepl.el` is self-contained — no external dependencies.

| Layer | Description |
|-------|-------------|
| **Bencode codec** | Pure Elisp encoder/decoder for the nREPL wire format |
| **TCP transport** | Async `make-network-process` with process filter; buffers partial frames |
| **Session** | `clone` op on connect; UUID session tracked for all subsequent evals |
| **Dispatch** | Pending requests keyed by ID; incremental `out`/`value` messages merged until `status=done` |
| **Display** | Short values → minibuffer; all output → `*irij-nrepl*` buffer |

The nREPL server supports: `clone`, `eval`, `describe`, `close`. That is
the complete op set — no middleware required.

## Troubleshooting

**"Not connected to nREPL"** — Start the server (`irij --nrepl-server`) then
press `C-c C-n`. If `.nrepl-port` exists in your project root, it auto-connects.

**"No top-level declaration found at point"** — `C-c C-d` looks for a line at
column 0 starting with `fn`, `type`, `newtype`, or a `:=` binding. Make sure
point is inside or just above the declaration.

**eval returns wrong result after redef** — Ensure you eval the whole `fn`
block (not just a partial expression). `C-c C-d` finds the complete declaration.

**Port mismatch** — `irij --nrepl-server=PORT` uses a custom port; pass it
explicitly: `M-x irij-nrepl-connect RET localhost RET PORT RET`.
