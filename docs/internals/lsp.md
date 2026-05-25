# Language Server (LSP)

Entry: `irij lsp`. Speaks the standard Language Server Protocol over
stdio. The editor spawns it as a subprocess; communication is
JSON-RPC framed messages on stdin/stdout.

## Status

MVP shipped in v0.7.x. Capabilities currently advertised:

| Capability | Status |
|---|---|
| `textDocument/sync` (FULL) | ✅ — full text sent on every change; server re-parses |
| `textDocument/publishDiagnostics` | ✅ — parse errors as red squiggles |
| `textDocument/hover` | 🟡 placeholder — shows word + version; symbol-aware payload in 2b.1 |
| `textDocument/definition` | 🟡 placeholder — empty result; AST symbol index in 2b.1 |
| `textDocument/completion` | 🟡 placeholder — keyword list only; identifier index in 2b.1 |

## Architecture

```
editor ──stdio──▶ irij lsp
                  │
                  ▼
         IrijLspServer  (LanguageServer + TextDocumentService)
                  │
                  ├── docs : ConcurrentHashMap<URI, String>   ← editor's mirror
                  │
                  ├── LspDiagnostics.parseErrors(src)         ← parse via IrijParseDriver
                  │     │
                  │     └── ANTLR errors → LSP Diagnostics
                  │
                  └── LspText.wordAt(src, pos)                ← hover/completion helper
```

Three classes:

- `IrijLspServer` — implements `LanguageServer`, `TextDocumentService`,
  `WorkspaceService`. Holds the in-memory document mirror.
- `LspText` — pure helpers: position → byte-offset, word-at-position,
  1-based-SourceLoc → zero-width LSP range.
- `LspDiagnostics` — pure function: source string → list of LSP
  diagnostics. Parses via `IrijParseDriver`; converts each ANTLR
  error string (`"line:col message"`) to a `Diagnostic` with the
  right range + severity.

Transport: LSP4J's `Launcher.createLauncher` on `System.in` /
`System.out`. Same wire format every standard LSP client speaks.

## Editor integration — Emacs (eglot)

```elisp
(require 'eglot)
(add-to-list 'eglot-server-programs
             '(irij-mode . ("irij" "lsp")))
(add-hook 'irij-mode-hook 'eglot-ensure)
```

That's it — eglot drives stdio, gets diagnostics on save, hover via
`C-h .`, goto-def via `M-.`.

## Editor integration — VS Code

A separate extension package (not in this repo) would do:

```ts
const serverOptions: ServerOptions = {
  command: "irij",
  args: ["lsp"],
  transport: TransportKind.stdio
};
const clientOptions: LanguageClientOptions = {
  documentSelector: [{ scheme: "file", language: "irij" }]
};
const client = new LanguageClient("irij", "Irij", serverOptions, clientOptions);
client.start();
```

## Why FULL sync (not incremental)

LSP supports incremental sync (`Incremental` kind), where the editor
sends only the changed range. The MVP uses FULL: every change carries
the entire document text and the server re-parses from scratch.

Trade-off:
- FULL is simpler — no merging logic, no off-by-one ranges to debug.
- Re-parse cost on a 500-line file is ~1ms on modern hardware. The
  language is small enough that ANTLR is fast.
- Incremental would matter at 10k+ lines. Not the bottleneck today.

If/when needed, switching to incremental is a one-line capability
change + a merge helper in `LspText`.

## Why no compile-pipeline diagnostics yet

Parse errors are file-local — running ANTLR on the current text
gives a clean answer. The later passes (ModuleInliner,
EffectRowChecker) need workspace-shaped resolution:

- ModuleInliner needs to find `use mod.X` imports on disk relative
  to the project root (`irij.toml`).
- EffectRowChecker needs the inlined tree.

That requires workspace-aware document resolution + an incremental
build cache. Tracked for 2b.1.

## What 2b.1 will add

- Symbol index built off the AST: every `fn`, `effect`, `handler`,
  `cap`, `spec` decl gets a `(name, location, kind, signature)`
  record.
- `textDocument/definition` looks up by name.
- `textDocument/hover` reads the decl's signature + leading
  doc-comment (`;;` lines above).
- `textDocument/completion` returns the in-scope identifier set
  in addition to the keywords.
- Diagnostics from EffectRowChecker (once module resolution lands).

## Tests

- `LspTextTest` — `wordAt` over kebab-case identifiers, predicate
  suffixes, whitespace positions.
- `LspDiagnosticsTest` — clean source → empty diagnostics; parse
  error → at least one Error-severity diagnostic with source `irij`.

Smoke-testing the live server requires a JSON-RPC driver; that
loop isn't covered by automated tests today. Manual smoke:
`echo '<initialize-payload>' | irij lsp` returns the capability
advertisement.
