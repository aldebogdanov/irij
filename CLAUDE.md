# Irij Language

## Build & Test
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
- `docs/irij-lang-spec.org` is the source of truth
- Always update `TODO.md` when steps are implemented
- Create docs in `./docs/` after each phase
