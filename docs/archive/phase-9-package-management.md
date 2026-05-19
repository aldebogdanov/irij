# Phase 9 — Package Management (Seeds)

## Overview

Irij supports external dependencies ("seeds") via `irij.toml` manifest files (TOML format). Seeds can come from:

- **Registry** — the Irij seed registry (version string shorthand)
- **Git** — repositories pinned to tags or commits
- **Path** — local filesystem paths (for development)

The `irij.toml` file also holds optional project metadata for the seed registry. Resolution is recursive with cycle detection — transitive seeds are automatically resolved.

## irij.toml Format

Create an `irij.toml` file in your project root:

```toml
[project]
name = "my-app"
version = "0.1.0"
description = "My Irij application"
author = "user"
license = "MIT"

[seeds]
vrata = "0.1.1"                                                    # registry
utils = { git = "https://github.com/user/irij-utils.git", tag = "v0.1.0" }  # git
local-lib = { path = "../my-lib" }                                  # path
```

### Sections

#### `[project]` (optional)

Project metadata for the seed registry.

| Field | Description |
|-------|-------------|
| `name` | Seed name |
| `version` | Semver version string |
| `description` | Short description |
| `author` | Author name |
| `license` | License identifier (e.g. "MIT") |

#### `[seeds]`

Seeds support three formats:

| Format | Example | Description |
|--------|---------|-------------|
| Registry shorthand | `vrata = "0.1.1"` | Download from seed registry |
| Git inline table | `utils = { git = "...", tag = "v1.0" }` | Clone from git repo |
| Path inline table | `dev = { path = "../lib" }` | Local filesystem (dev only) |

Full table syntax also supported:

```toml
[seeds.utils]
git = "https://github.com/user/utils.git"
tag = "v1.0"
```

Git seeds require `tag` or `commit`. Path seeds need only `path`.

## Installing Seeds

```bash
irij install    # or: irij seed
```

Fetches all seeds (if not already cached) and validates local paths. Git seeds cached at `~/.irij/seeds/<name>/<ref>/`. Registry seeds cached at `~/.irij/seeds/<name>/<version>/`.

## Publishing Seeds

```bash
irij publish    # or: irij sow
```

Publishes current project to the seed registry. Requires all `[project]` fields (name, version, author, description). Rejects seeds with `path` dependencies.

Override registry URL: `IRIJ_REGISTRY=https://custom.registry irij publish`

## Using Seeds

After seeds are declared in `irij.toml`, use them like any module:

```irj
;; Qualified import
use utils
println ~ utils.helper-fn 42

;; Open import
use utils :open
println ~ helper-fn 42

;; Selective import
use utils {helper-fn other-fn}

;; Sub-module within a seed
use utils.extra :open
```

Seeds are automatically loaded when running a file with `irij file.irj` — no separate install step needed (though `irij install` is useful for pre-fetching).

## Module Resolution in Seeds

When you write `use seedname`, the resolver looks for source files in this order:

1. `<seed-dir>/src/<seedname>.irj`
2. `<seed-dir>/<seedname>.irj`
3. `<seed-dir>/mod.irj`

For sub-modules like `use seedname.sub.module`:

1. `<seed-dir>/src/sub/module.irj`
2. `<seed-dir>/sub/module.irj`

## Transitive Seeds

If a resolved seed has its own `irij.toml` with `[seeds]`, those are resolved recursively. Cycle detection prevents infinite loops. First declaration wins when the same seed appears at multiple levels.

## Recommended Seed Layout

```
my-seed/
  irij.toml           ;; project metadata + seeds
  mod.irj             ;; or <name>.irj — main module
  src/
    helpers.irj        ;; sub-module: use <name>.helpers
```

## Full Resolution Priority

When resolving `use some.module`, the registry checks:

1. **Cache** — already-loaded modules
2. **Factories** — Java-implemented modules
3. **Classpath** — `std/*.irj` (standard library)
4. **Seed paths** — from `irij.toml [seeds]`
5. **File system** — relative to current file's directory

## Implementation

| File | Role |
|------|------|
| `ProjectFile.java` | Parser for `irij.toml` format (uses toml4j) |
| `DependencyResolver.java` | Registry download, git clone/cache, local path, transitive resolution |
| `ModuleRegistry.java` | Extended with seed path resolution (step 4) |
| `Interpreter.java` | `loadDeps(projectRoot)` method |
| `IrijCli.java` | `irij install`/`seed`, `irij publish`/`sow` commands |

## Tests

- 17 `ProjectFile` parser tests (registry shorthand, inline tables, git, path, metadata, errors)
- 8 `DependencyResolver` tests (local path, transitive, deep transitive, cycle detection)
- 9 integration tests (end-to-end: irij.toml → use module → run, including transitive)
