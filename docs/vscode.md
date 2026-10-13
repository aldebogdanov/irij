# VS Code support

`editors/vscode/` is a VS Code extension: highlighting, the `irij lsp`
client, editing rules and a terminal REPL. The user-facing summary is
[editors/vscode/README.md](../editors/vscode/README.md).

## Build and install locally

```bash
cd editors/vscode
npm install
npm run package          # → irij-0.1.0.vsix
code --install-extension irij-0.1.0.vsix
```

`code: command not found` means the `code` CLI isn't on your `PATH`;
on macOS VS Code doesn't add it by default (and `sudo` won't find it
either). Any of these works:

- run **Shell Command: Install 'code' command in PATH** from the
  Command Palette (`Cmd+Shift+P`), then open a new terminal;
- call it by its full path:
  `"/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" --install-extension irij-0.1.0.vsix`;
- skip the terminal: Extensions view → `…` menu → **Install from VSIX…**.

For development, open `editors/vscode` in VS Code and press F5 after
`npm run build` (or keep `npm run watch` running).

## Layout

| Path | |
|---|---|
| `src/extension.ts` | activation, language client, REPL and run commands |
| `src/forms.ts` | top-level form detection for "send to REPL" (pure, unit-tested) |
| `language-configuration.json` | comments, brackets, word pattern, indentation rules, off-side folding |
| `syntaxes/irij.tmLanguage.json` | **copied** from `editors/textmate/` by `npm run sync-grammar` (gitignored) |
| `syntaxes/markdown-codeblock.json` | injects the grammar into ```` ```irij ```` blocks |
| `test/*.test.ts` | `npm test`: grammar through `vscode-textmate`, indentation regexes, forms |
| `test/e2e/` | `npm run test:e2e`: launches VS Code with the extension |

The grammar has one source, `editors/textmate/irij.tmLanguage.json`, kept
in sync with the lexer by `EditorGrammarSyncTest`.

## Indentation

VS Code indents from regexes, not from a parse, so the rules in
`language-configuration.json` mirror the Emacs mode's heuristics
(`docs/emacs.md`): a block head at the start of a line (`fn f`, `if c`,
`else`, `match x`, `with h`, `x := match y`, …) or a line ending in `=>`,
`->`, `:=`, `<-` or `=` indents the next line. Inline forms don't:
`if c a else b`, `with h do (…)`, `do (a) (b)`, `spec :: T`, a leading
`=> params`. Typing `else` dedents. Folding is indentation based
(`offSide`), so a block's trailing blank lines fold with it.

## REPL

The REPL is `irij repl` in an integrated terminal. "Send" pastes the
code followed by a blank line; `irij repl` reads multi-line input by the
offside rule (`ReplInput`, see `docs/internals/nrepl.md`), so the blank
line ends any open block. Top-level forms are found the same way as the
Emacs nREPL client's eval-defun: back to the nearest column-0 code line,
forward to the next one, minus trailing blank and comment lines; a
column-0 `else` stays with its `if`.

## Tests

- `npm test` — runs in CI (`vscode` job): 14 tests, no VS Code needed.
- `npm run test:e2e` — opens VS Code with a throwaway profile and a
  temp workspace; checks language registration, activation,
  `selectionRange`, hover and diagnostics from the language server, and
  command registration. `VSCODE_EXECUTABLE` picks an installed VS Code
  (otherwise one is downloaded); `IRIJ_EXECUTABLE` picks the `irij`
  to test (it needs `selectionRange`, so a current build).

## Not done

- Marketplace / Open VSX publishing: needs a publisher account, and the
  repo has no license file yet (`vsce package --skip-license`).
- Inline evaluation results over nREPL, as the Emacs client shows.
