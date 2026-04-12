# Phase 9 — Package Management (Git Deps)

## Overview

Irij now supports external dependencies via `irij.toml` manifest files (TOML format). Dependencies can come from git repositories (pinned to tags or commits) or local filesystem paths (for development). The `irij.toml` file also holds optional project metadata for the package registry.

## irij.toml Format

Create an `irij.toml` file in your project root:

```toml
[project]
name = "my-app"
version = "0.1.0"
description = "My Irij application"
author = "user"
license = "MIT"

[deps.utils]
git = "https://github.com/user/irij-utils.git"
tag = "v0.1.0"

[deps.http-extra]
git = "https://github.com/user/irij-http.git"
commit = "a7f3b2c1"

[deps.local-lib]
path = "../my-lib"
```

### Sections

#### `[project]` (optional)

Project metadata for the package registry.

| Field | Description |
|-------|-------------|
| `name` | Package name |
| `version` | Semver version string |
| `description` | Short description |
| `author` | Author name |
| `license` | License identifier (e.g. "MIT") |

#### `[deps.<name>]`

Each dependency is a TOML table under `[deps.<name>]`.

| Property | Description |
|----------|-------------|
| `git = "url"` | Git repository URL |
| `tag = "ref"` | Git tag to checkout (use with `git`) |
| `commit = "sha"` | Git commit hash to checkout (use with `git`) |
| `path = "dir"` | Local filesystem path (relative to project root) |

A git dependency must have either `tag` or `commit`. A path dependency needs only `path`.

## Installing Dependencies

```bash
irij install
```

This fetches all git dependencies (if not already cached) and validates local paths. Git deps are cached at `~/.irij/deps/<name>/<ref>/` — subsequent runs are instant.

## Using Dependencies

After deps are declared in `irij.toml`, use them like any module:

```irj
;; Qualified import
use utils
println ~ utils.helper-fn 42

;; Open import
use utils :open
println ~ helper-fn 42

;; Selective import
use utils {helper-fn other-fn}

;; Sub-module within a dependency
use utils.extra :open
```

Dependencies are automatically loaded when running a file with `irij file.irj` — no separate install step needed (though `irij install` is useful for pre-fetching).

## Module Resolution in Dependencies

When you write `use depname`, the resolver looks for source files in this order:

1. `<dep-dir>/src/<depname>.irj`
2. `<dep-dir>/<depname>.irj`
3. `<dep-dir>/mod.irj`

For sub-modules like `use depname.sub.module`:

1. `<dep-dir>/src/sub/module.irj`
2. `<dep-dir>/sub/module.irj`

## Recommended Dep Layout

```
my-dep/
  irij.toml           ;; project metadata + transitive deps
  mod.irj             ;; or <name>.irj — main module
  src/
    helpers.irj        ;; sub-module: use <name>.helpers
```

## Full Resolution Priority

When resolving `use some.module`, the registry checks:

1. **Cache** — already-loaded modules
2. **Factories** — Java-implemented modules
3. **Classpath** — `std/*.irj` (standard library)
4. **Dep paths** — from `irij.toml`
5. **File system** — relative to current file's directory

## Implementation

| File | Role |
|------|------|
| `ProjectFile.java` | Parser for `irij.toml` format (uses toml4j) |
| `DependencyResolver.java` | Git clone/cache + local path resolution |
| `ModuleRegistry.java` | Extended with dep path resolution (step 4) |
| `Interpreter.java` | `loadDeps(projectRoot)` method |
| `IrijCli.java` | `irij install` command + auto-load in `runFile` |

## Tests

- 12 `ProjectFile` parser tests (git+tag, git+commit, path, multiple, metadata, errors)
- 3 `DependencyResolver` tests (local path resolution)
- 8 integration tests (end-to-end: irij.toml → use module → run)
