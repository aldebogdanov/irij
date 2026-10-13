# Irij TextMate grammar

`irij.tmLanguage.json` (scope `source.irij`, files `*.irj`) is the editor-neutral
highlighter for Irij. TextMate grammars are consumed by VS Code, Shiki, GitHub
Linguist, Sublime Text, Zed, and anything built on `vscode-textmate`.

Tokens follow `src/main/antlr/IrijLexer.g4`. Builtin function names are the
names a file reaches without an import (`IntrinsicsEmitter.NAMES` plus `pi`, `e`,
`resume`). `EditorGrammarSyncTest` fails when the keyword or builtin lists here
or in `editors/emacs/irij-mode.el` drift from the lexer or the compiler.

## Try it with Shiki

```js
import { createHighlighter } from 'shiki'
import fs from 'fs'
const g = JSON.parse(fs.readFileSync('irij.tmLanguage.json', 'utf8'))
const h = await createHighlighter({ themes: ['github-dark'], langs: [{ ...g, name: 'irij' }] })
console.log(h.codeToHtml(fs.readFileSync('../../examples/basics.irj', 'utf8'),
                         { lang: 'irij', theme: 'github-dark' }))
```

## Upstreaming

- **Shiki**: add under `packages/langs` / `textmate-grammars-themes` so ```` ```irij ```` works in docs sites and any tool bundling Shiki.
- **GitHub Linguist**: needs a language entry plus about 200 `.irj` files in public repos.
- **VS Code**: done — `editors/vscode/` copies this grammar at build time.

Other highlighters are maintained separately: `editors/emacs/irij-mode.el` and
`irij.online/resources/irij-mode.js` (CodeMirror 5). The first two are checked by
`EditorGrammarSyncTest`; the CodeMirror mode lives in another repo and is not.
