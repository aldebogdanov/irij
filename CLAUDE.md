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
