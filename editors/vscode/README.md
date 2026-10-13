# Irij for VS Code

Language support for [Irij](https://irij.online) (ℑ) `.irj` files.

- **Highlighting**, including ```` ```irij ```` code blocks in Markdown
- **Language server** (`irij lsp`): diagnostics, hover, go to definition,
  completion, and Expand/Shrink Selection (`Shift+Alt+→` / `←`) along the syntax tree
- **Editing**: `;;` comment toggling, bracket pairing, indentation after block
  heads (`fn f`, `if c`, `match x`, `pat =>`, …), indentation-based folding
- **REPL** in the integrated terminal

## Requirements

The `irij` executable on your `PATH`, or set `irij.executable`.

## Commands

| Command | Key | |
|---|---|---|
| Irij: Start REPL | | opens `irij repl` in a terminal |
| Irij: Send Selection or Top-Level Form to REPL | `Cmd+Enter` / `Ctrl+Enter` | the selection, or the column-0 form around the cursor with its indented body |
| Irij: Send File to REPL | `Cmd+Alt+Enter` / `Ctrl+Alt+Enter` | `:load`s a saved file, or sends the unsaved text |
| Irij: Run Current File | ▷ in the editor title | `irij run <file>` in a terminal |
| Irij: Restart Language Server | | |

## Settings

| Setting | Default | |
|---|---|---|
| `irij.executable` | `irij` | used for the language server, REPL and run |
| `irij.languageServer.enabled` | `true` | |
| `irij.trace.server` | `off` | log LSP traffic to the output panel |
