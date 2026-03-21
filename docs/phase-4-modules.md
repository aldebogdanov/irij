# Phase 4 — Module System + Stdlib

## Overview

Irij's module system enables code organization via `mod` declarations, `use` imports, and `pub` visibility. Standard library modules are written in Irij itself (loaded from classpath resources), with only primitive operations implemented as Java builtins.

## Module Declarations

```irj
mod myapp.auth.jwt    ;; one per file, declares module identity
```

## Import Forms

```irj
;; Qualified — bind module as namespace, access via dot
use std.math
math.sqrt 16          ;; → 4.0

;; Open — import all exports into current scope
use std.text :open
trim "  hello  "      ;; → "hello"

;; Selective — import specific names only
use std.math {sqrt pow}
sqrt 9                ;; → 3.0
pow 2 10              ;; → 1024.0
```

## Pub Visibility

```irj
mod mylib.utils

pub fn public-api       ;; exported — visible to importers
  (x -> x + 1)

fn internal-helper      ;; private — not in module exports
  (x -> x * 2)

pub greeting := "hi"    ;; pub bindings also exported
```

## Module Resolution Order

1. **Cache** — already-loaded modules returned immediately
2. **Registered factories** — Java-implemented std modules (future extension point)
3. **Classpath resources** — `std/math.irj` for `use std.math`
4. **File system** — dots → `/`, append `.irj`, resolve relative to source file's directory

## Standard Library Modules

### `std.math`

Re-exports builtins: `sqrt`, `floor`, `ceil`, `round`, `sin`, `cos`, `tan`, `log`, `exp`, `pow`, `abs`, `min`, `max`, `pi`, `e`, `random-int`, `random-float`

Irij-defined: `clamp`, `lerp`, `square`, `cube`, `sign`, `even?`, `odd?`, `gcd`, `lcm`, `sum-of`, `product-of`

### `std.text`

All Irij-defined: `chars`, `words`, `lines`, `unwords`, `unlines`, `blank?`, `contains-substr?`, `pad-left`, `pad-right`, `repeat`

### `std.collection`

All Irij-defined: `zip`, `zip-with`, `enumerate`, `flatten`, `distinct`, `frequencies`, `partition`, `group-by`, `sort-by`, `map-vals`, `filter-vals`, `map-keys`, `each`, `find-index`, `all?`, `any?`, `none?`, `flat-map`, `sum`, `product`, `count-by`, `interleave`, `take-while`, `window`

### `std.func`

All Irij-defined: `flip`, `compose`, `pipe`, `apply-to`, `on`, `juxt`, `complement`, `constantly`, `times`

### `std.convert`

All Irij-defined: `to-int`, `to-float`, `digits`

## New Global Builtins

These are always available (no `use` needed) — they're Java primitives that can't be expressed in Irij:

### Error & Type
- `error msg` — throw a runtime error
- `type-of val` — return type name as string

### Dynamic Map Operations
- `assoc map key val` — return new map with key set
- `dissoc map key` — return new map with key removed
- `merge m1 m2` — merge two maps (m2 wins)

### String Operations
`split`, `join`, `trim`, `upper-case`, `lower-case`, `starts-with?`, `ends-with?`, `replace`, `substring`, `char-at`, `index-of`

### Math Operations
`sqrt`, `floor`, `ceil`, `round`, `sin`, `cos`, `tan`, `log`, `exp`, `pow`, `random-int`, `random-float`

### Conversion
`parse-int`, `parse-float`, `char-code`, `from-char-code`

### IO
`read-file`, `write-file`, `file-exists?`, `get-env`, `now-ms`

## Files

| File | Purpose |
|------|---------|
| `src/main/java/dev/irij/module/ModuleRegistry.java` | Module cache, resolution, circular dependency detection |
| `src/main/java/dev/irij/module/StdModules.java` | Extension point for Java-implemented std modules |
| `src/main/java/dev/irij/interpreter/Values.java` | `ModuleValue` record |
| `src/main/java/dev/irij/interpreter/Environment.java` | `exportBindings()`, `copyAllFrom()` helpers |
| `src/main/java/dev/irij/interpreter/Interpreter.java` | `evalUseDecl`, `evalModDecl`, `evalPubDecl`, `loadModuleSource` |
| `src/main/java/dev/irij/interpreter/Builtins.java` | ~35 new primitive builtins |
| `src/main/resources/std/math.irj` | std.math module |
| `src/main/resources/std/text.irj` | std.text module |
| `src/main/resources/std/collection.irj` | std.collection module |
| `src/main/resources/std/func.irj` | std.func module |
| `src/main/resources/std/convert.irj` | std.convert module |

## Limitations

1. **INDENT/DEDENT in parens**: Multi-line lambdas with block `if`/`else` inside `()` don't work. Use named helper functions or inline `if` expressions instead.
2. **No circular module dependencies**: Detected at runtime with clear error message.
3. **No re-export shorthand**: `pub use other.module` works but doesn't re-export transitively yet.
4. **Keyword module names**: Module names can't use Irij keywords (e.g., `std.fn` → use `std.func` instead).
5. **No version control**: Module loading is path-based, no package manager.
