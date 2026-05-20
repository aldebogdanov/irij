# Modules

`mod`, `use`, `pub`. Compile-time source inlining; no runtime
linkage.

## Declaration shapes

```
mod my.app                       ;; first non-comment line declares the module
use std.list :open               ;; flatten every pub into the current scope
use std.text :as text            ;; alias: text.trim, text.split, …
use std.math :as math            ;; alias: math.sqrt, math.div, …
use mymod.helpers {inc twice}    ;; selective: just `inc` and `twice`

pub fn greet
  (name -> "Hi, " ++ name)

fn helper
  (x -> x * 2)            ;; not pub — invisible to importers
```

**Modifier required** (v0.6.4+). `use mod.path` without a
modifier is rejected at compile time:

```
`use std.math` requires an explicit modifier: `:open` (flatten),
`:as <alias>` (rename), or `{ name name ... }` (selective)
```

Before v0.6.4 the bare form created an implicit alias from the
last segment of the qualified name (`use std.math` → `math.X`).
That broke silently when two imports ended in the same name
(`use std.math` + `use third-party.math` both bound `math`).
Explicit `:as` resolves the ambiguity by making the alias choice
the programmer's responsibility.

The shadowed-builtin escape pattern uses `:as` directly:

```
use std.math :as math

fn div :: Map Vec Map
  (attrs children -> el "div" attrs children)

result := math.div 10 3   ;; bare `div` is the local user fn
```

## Resolution

`ModuleInliner` runs after parsing, before back-end dispatch:

1. Encounter `use mod.X` → resolve `mod.X` to a `.irj` file:
   - Standard library: `std/*.irj` in resources (classpath
     `/std/X.irj`).
   - User modules: relative to project `sourceRoot`.
   - Git deps: pulled to `~/.irij/cache/...` via `irij install`,
     then resolved like local.
2. Recursively inline the imported module's AST.
3. Stripping rules:
   - `mod` declaration removed.
   - `pub` prefix removed from each pub decl (kept as a marker for
     blame envelopes).
   - Private decls renamed with module-prefix to avoid clashes
     (e.g. `helper` → `mymod__helpers__helper`).
4. Open / qualified resolution:
   - `:open` rewrites every Var reference to the unqualified name.
   - Default (qualified) rewrites `text.trim x` to `trim x` and adds
     `text` to the module alias set so subsequent `text.foo` calls
     resolve.
5. Selective imports (`:fn1 :fn2`) bring only those names into the
   open scope.

The output of `ModuleInliner` is a single flat `List<Decl>` — both
back-ends consume that.

## Cycle handling

Imports form a DAG. Cycles are detected by tracking the in-flight
import set; encountering an already-in-flight module raises:

```
Module cycle: mod.a → mod.b → mod.a
```

## Why inline, not link

Trade-offs we accepted:

- **No incremental compilation.** The entire program is one
  re-emission per change. Fast enough for ~5K LOC stdlib + project.
- **No class-level isolation.** All names live in one JVM class. No
  pub/private at JVM level — privacy is *enforced at AST-walk time*,
  not at runtime.
- **No runtime classpath.** The shadow JAR is fully self-contained.

What we gained:

- **Inlining = whole-program optimization** for free. JIT sees all
  call sites of a stdlib fn → can inline aggressively.
- **No classloader pain.** One classloader, one class, simple state.
- **Deploy artifact = one JAR.** No "stdlib not found" or
  "incompatible interface" errors at startup.

## `irij install`

Resolves `deps.irj` (TOML-shaped):

```
[deps]
mymod = { git = "https://github.com/user/mymod", ref = "v1.2.3" }
util  = { git = "git@github.com:user/util",  ref = "main" }
```

Downloads to `~/.irij/cache/<sha>/`. Stamped with the resolved git
commit hash. Re-runs do `git fetch && checkout` if `ref` is a branch.

## Module-boundary blame

`pub fn f :: A B` inside `mod my.lib` exports `f` with a "blame
envelope":

- If a *caller* in `app.main` passes a non-`A` arg, the error blames
  `app.main:line:col`.
- If `f` returns a non-`B`, blame `my.lib:f` (line of the fn decl).

The envelope is a `SpecContractFn` wrapper applied at inline time; the
back-end sees a wrapped value, not the raw fn. Bytecode mode doesn't
yet emit the envelope (specs aren't runtime-checked in bytecode) — gap.

## What modules don't do

- **Re-export.** `use std.list :open` doesn't re-export those names
  from your module. Importers see your pubs only.
- **Versioned imports.** All `use std.list` in one program resolve to
  the same `std/list.irj`. No multiple-version mixing.
- **Late binding.** A `pub` change requires a rebuild. (Hot-redef
  applies to fn bodies, not module shape.)

## Stdlib organisation

| Module | What lives here |
|---|---|
| `std.list` | Higher-order list ops (fold, map, filter, ...) — Irij port |
| `std.collection` | Compositional ops over coll (zip, distinct, group-by, ...) |
| `std.func` | Function combinators (flip, identity, repeat-n) |
| `std.text` | String ops (trim, pad, split, join, ...) |
| `std.math` | Math + math constants |
| `std.random` | Random effect + default-random handler |
| `std.env` | Environment-variable effect |
| `std.fs` | File-system effect (read-file, write-file, ...) |
| `std.http` | HTTP client + server |
| `std.db` | Database effect (SQLite via std.db) |
| `std.serve` | Web server framework |
| `std.session` | nREPL session effects |
| `std.datastar` | Datastar SSE protocol |
| `std.json` | JSON parser/serialiser |
| `std.convert` | Type coercions |
| `std.test` | Test runner |
| `std.jvm` | JVM capability + `unsafe-jvm` handler |
