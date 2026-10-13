# Emacs support

`editors/emacs/` holds two packages:

- `irij-mode.el` — major mode for `.irj` files (this page)
- `irij-nrepl.el` — nREPL client with inline results, see [emacs-nrepl.md](emacs-nrepl.md)

```elisp
(use-package irij-mode
  :load-path "~/dev/irij/editors/emacs"
  :mode "\\.irj\\'"
  :hook (irij-mode . eglot-ensure))
```

## Highlighting

Follows `src/main/antlr/IrijLexer.g4`: keywords, `model` only where a declaration
starts (it is a soft keyword), declaration names (`fn`, `handler`, `cap`, `model`,
`spec`, `effect`, …), PascalCase types, `$PARTY` names, `:atoms`, Java refs
(`Math/sqrt`), digraph operators, numbers, string escapes and `${…}`.

`?`, `!`, `-`, `_` and `$` are symbol constituents, so `contains?` and
`fs-exists?` are single names for motion and highlighting.

The builtin list is the set of names a file reaches without an import.
`EditorGrammarSyncTest` (run by `./gradlew test`) fails when it, or the keyword
list, drifts from the compiler or lexer.

## Indentation

Irij blocks follow the offside rule, and where a block ends can't be inferred
from the text. So:

- **TAB** indents to the deepest valid column; pressing it again cycles one level
  shallower each time, wrapping back to the deepest.
- A body is indented after block heads (`fn f`, `if c`, `else`, `match x`,
  `with h`, `scope s`, `effect E`, `handler h :: E`, `impl P for T`, `model m …`,
  `x := match y`, …) and after lines ending in `=>`, `->`, `:=`, `<-` or `=`.
- Inline forms don't open a block: `if c a else b`, `with h do (…)`,
  `do (a) (b)`, `fn f (x -> …)`, `spec :: T`, and a leading `=> params`
  marker (its body stays at the marker's level).
- `else` aligns with the `if` it closes.
- Inside brackets, the first line aligns with the first element after the opener,
  or goes one level in when nothing follows the opener (or it ends with `->`).
- A line that starts with `|>`, `++`, `&&`, … continues the expression above;
  consecutive operator lines align with each other.
- **`indent-region`** only moves lines that are clearly wrong (a required body
  not indented, or a dedent that isn't a multiple of 2). Re-indenting every
  `.irj` file in this repo changes nothing.

## Other

- imenu: functions, specs, effects/protos, impls, handlers/caps/models
- `C-c C-z` comint REPL (`irij repl`); `C-c C-r` / `C-c C-b` / `C-c C-l` send region / buffer / line
- LSP: `irij lsp` is registered with eglot and lsp-mode when they load

## Tests

```bash
emacs --batch -Q -L editors/emacs -l editors/emacs/test/irij-mode-test.el -f ert-run-tests-batch-and-exit
```
