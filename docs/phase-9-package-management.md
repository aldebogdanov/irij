# Phase 9 — Package Management (Git Deps)

## Overview

Irij now supports external dependencies via `deps.irj` manifest files. Dependencies can come from git repositories (pinned to tags or commits) or local filesystem paths (for development).

## deps.irj Format

Create a `deps.irj` file in your project root:

```irij
;; deps.irj — project dependencies

dep utils
  git "https://github.com/user/irij-utils.git"
  tag "v0.1.0"

dep http-extra
  git "https://github.com/user/irij-http.git"
  commit "a7f3b2c1"

dep local-lib
  path "../my-lib"
```

### Properties

| Property | Description |
|----------|-------------|
| `git "url"` | Git repository URL |
| `tag "ref"` | Git tag to checkout (use with `git`) |
| `commit "sha"` | Git commit hash to checkout (use with `git`) |
| `path "dir"` | Local filesystem path (relative to project root) |

A git dependency must have either `tag` or `commit`. A path dependency needs only `path`.

## Installing Dependencies

```bash
irij install
```

This fetches all git dependencies (if not already cached) and validates local paths. Git deps are cached at `~/.irij/deps/<name>/<ref>/` — subsequent runs are instant.

## Using Dependencies

After deps are declared in `deps.irj`, use them like any module:

```irij
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
  deps.irj          ;; transitive deps (if any)
  mod.irj           ;; or <name>.irj — main module
  src/
    helpers.irj      ;; sub-module: use <name>.helpers
```

## Full Resolution Priority

When resolving `use some.module`, the registry checks:

1. **Cache** — already-loaded modules
2. **Factories** — Java-implemented modules
3. **Classpath** — `std/*.irj` (standard library)
4. **Dep paths** — from `deps.irj`
5. **File system** — relative to current file's directory

## Implementation

| File | Role |
|------|------|
| `DepsFile.java` | Parser for `deps.irj` format |
| `DependencyResolver.java` | Git clone/cache + local path resolution |
| `ModuleRegistry.java` | Extended with dep path resolution (step 4) |
| `Interpreter.java` | `loadDeps(projectRoot)` method |
| `IrijCli.java` | `irij install` command + auto-load in `runFile` |

## Tests

- 12 `DepsFile` parser tests (git+tag, git+commit, path, multiple, errors)
- 3 `DependencyResolver` tests (local path resolution)
- 8 integration tests (end-to-end: deps.irj → use module → run)
