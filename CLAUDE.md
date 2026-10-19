# Irij Language

## Build & Test. 
- `./gradlew test` — Java tests
- `irij test` — integration tests
- `./gradlew shadowJar` — build fat JAR
- `./gradlew install` — install to ~/.local/bin/irij

## Spec Annotations
- All `pub fn` declarations MUST have spec annotations (`:: ...InputTypes OutputType`)
- Use `_` for positions where the spec is too complex or not yet determined
- `--no-spec-lint` is for human emergency development ONLY. Never use it as a workaround for missing annotations. Fix the annotations instead.
- When creating or modifying any `pub fn`, always add or maintain its spec annotation

## Build & Deploy
- `irij build` — package app into self-contained JAR (bundles runtime + deps + resources/)
- `irij build server.irj` — build with explicit entry point
- `irij build -o out.jar` — custom output path
- Bundled JAR runs with: `java --enable-native-access=ALL-UNNAMED -jar app.jar`
- Static files go in `resources/` directory (bundled under `__irij_resources/` in JAR)

## Spec Reference
- `docs/irij-lang-spec.org` is the source of truth, you can't change it except if developer asked of that explicitly
- Always update `TODO.md`, `README.md`, examples and other related files when steps are implemented
- Create docs in `./docs/` after each phase

## Internals docs — MANDATORY upkeep
- `docs/internals/` documents how Irij works under the hood: pipeline,
  parser, AST, interpreter, bytecode, effects, TCO, hot-redef, specs,
  modules, concurrency, JVM capability, stdlib, glossary.
- **Whenever you change Irij codebase, you HAVE TO update the matching
  internals doc in the same commit.** Do this without being asked.
  - New AST node → update `docs/internals/ast.md`.
  - Changed effect lowering → update `docs/internals/effects.md`.
  - New emitter pass / case → update `docs/internals/bytecode.md`.
  - New stdlib primitive → update `docs/internals/stdlib.md`.
  - New JVM-capability rule → update `docs/internals/jvm-capability.md`.
  - Any concurrency change → update `docs/internals/concurrency.md`.
  - New terms → update `docs/internals/glossary.md`.
- The README in `docs/internals/` lists every page; if you add a new
  page, add it to the README's reading order.
- If a code change is large enough to break the doc's mental model
  (e.g. a refactor that replaces a subsystem), rewrite the affected
  page, don't just patch it.
- The site at `irij.online/docs` surfaces these docs publicly. Out-of-
  date pages are user-visible. Treat them as code, not afterthought.

## After a noticeable change — update everything users see (MANDATORY)

A change is *noticeable* when a user could tell: a program behaves or
prints differently, something that was refused now compiles (or the
reverse), a syntax form, builtin, std module, CLI command or error
message is new or different, or a documented workaround is no longer
needed. Bug fixes count. Do this without being asked.

1. **In the same PR, in this repo:**
   - the internals docs (section above), `TODO.md`, `README.md`,
     `examples/`, and `docs/parser-gotchas.md` — delete gotchas that
     no longer bite, add new ones;
   - `editors/` when keywords, builtins or block forms change (Emacs,
     TextMate, VS Code; `EditorGrammarSyncTest` and the
     `editors/vscode` tests keep them honest);
   - the spec only with the developer's explicit approval — ask.
   - End the PR description with a **Site and course** section listing
     what irij.online needs (or "none").
2. **On irij.online (`../irij.online`, its own PR), once the Irij
   release that ships the change exists** — the site's CI and
   production run the latest *released* `irij.jar`, so a lesson using
   a new feature fails CI before that:
   - the `/learn` course (`resources/learn/*.md`): teach the new
     behaviour; `grep -rn "engine-limit" resources/learn` lists every
     place a lesson works around a limitation — rewrite each one the
     change removes as if the limit never existed, and drop its
     marker; then `irij run scripts/check-course.irj`;
   - the home-page roadmap row for the release (`home-roadmap` in
     `server.irj`), plus feature cards and code examples for a feature;
   - playground examples (`pg-examples`) and `resources/irij-mode.js`,
     whose keyword and builtin lists mirror `editors/`;
   - `/docs` cards when an internals page is added or renamed.
3. Don't report the work as done while step 2 is outstanding: say
   what is left and which release it waits for.
