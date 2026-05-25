# Language Server (LSP)

Entry: `irij lsp`. Speaks the standard Language Server Protocol over
stdio. The editor spawns it as a subprocess; communication is
JSON-RPC framed messages on stdin/stdout.

## Status

MVP shipped in v0.7.x; v0.8.0 added the 2b.1 slice (symbol-aware
hover, goto-def, identifier completion, doc-comment hover,
effect-row diagnostics).

| Capability | Status |
|---|---|
| `textDocument/sync` (FULL) | ✅ — full text sent on every change; server re-parses |
| `textDocument/publishDiagnostics` | ✅ — parse errors + single-file effect-row errors |
| `textDocument/hover` | ✅ — signature in a code-fence, leading `;;` doc-comment, kind + line |
| `textDocument/definition` | ✅ — AST symbol index; jumps to decl location |
| `textDocument/completion` | ✅ — keywords + in-scope identifiers from the symbol index |

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

## Symbol index (2b.1)

`LspIndex.build(source)` parses the source into an AST and emits
one `Symbol(name, kind, loc, signature, docComment)` per surface
decl. Supported kinds: `FN, EFFECT, HANDLER, CAP, SPEC, NEWTYPE,
PROTO, ROLE`.

Doc-comment extraction walks upward from the decl line gathering
any contiguous `;;`-prefixed lines, strips the leading `;;`, and
joins them into one paragraph. Empty string if the line above
isn't a comment.

The index is rebuilt on every `didOpen`/`didChange` (FULL sync
already re-parses). No incremental update logic — one document's
worth of symbols is small.

`Decl.PubDecl` is unwrapped before classification so `pub fn foo`
surfaces as `Symbol(name=foo, kind=FN, …)` rather than a wrapper.

## Hover / definition / completion (2b.1)

- **hover** — `LspText.wordAt(src, position)` extracts the
  kebab-case identifier under the cursor; `LspIndex.findByName`
  looks it up. Body renders a fenced signature, the doc-comment
  paragraph (if any), then `*<kind>* (line N)`. Unindexed words
  fall back to `\`word\` — no definition in this file`.

- **definition** — returns a `Location` pointing at the decl's
  `SourceLoc`. Column gets bumped to 1-based for LSP.

- **completion** — keyword list first, then in-scope identifiers
  from the index with `CompletionItemKind` mapped per decl kind
  (`FN→Function`, `EFFECT/PROTO→Interface`, `HANDLER/CAP→Module`,
  `SPEC/NEWTYPE→Struct`, `ROLE→Constant`). Each item carries the
  symbol signature in `detail`.

## Effect-row diagnostics (2b.1.d)

`LspDiagnostics.all(source)` runs parse + a single-file
`EffectRowChecker.check(decls)` pass. The checker throws
`IrijCompiler.CompileException`s with an `at L:C` suffix in the
message; the regex `\bat (\d+):(\d+)` recovers the location into
an LSP range. If no location matches, the diagnostic anchors at
(1,1).

The effect-row pass is skipped when parse errors exist — a
half-typed file already has visible squiggles and the checker
would just trip on a partial AST.

Cross-module checks remain future work: `ModuleInliner` needs
workspace-aware module resolution relative to `irij.toml`. The
single-file pass already catches perform-without-row and
intra-file fn-row-mismatch issues.

## Tests

- `LspTextTest` — `wordAt` over kebab-case identifiers, predicate
  suffixes, whitespace positions.
- `LspDiagnosticsTest` — parse error → Error-severity diagnostic;
  effect-row violation surfaces with a non-trivial line range;
  clean program (parse + effect rows) → empty diagnostics.
- `LspIndexTest` — each surfaced decl kind round-trips through the
  index; doc-comment block above a decl attaches; absent doc
  leaves the field empty; partial source doesn't throw.

Smoke-testing the live server requires a JSON-RPC driver; that
loop isn't covered by automated tests today. Manual smoke:
`echo '<initialize-payload>' | irij lsp` returns the capability
advertisement.
