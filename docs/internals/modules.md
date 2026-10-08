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


## Seed module lookup

A seed's modules live **flat** in its root: `uzor.core` is
`<uzorRoot>/core.irj`, not `<uzorRoot>/uzor/core.irj`. So
`ModuleInliner` tries two shapes under each extra root — the full
dotted path, then the path with the leading seed name stripped.

The stripped shape is only tried against **the root that provides that
seed**. `<root>/core.irj` matches any qualified name ending in
`.core`, so trying it against every root made resolution depend on the
order the roots came in: with uzor and butterfly both present,
`use uzor.core` could load butterfly's `core.irj`. Nothing errored —
the module loaded, and every name the caller wanted was simply
missing.

A root's seed is identified by its own `irij.toml` `[project] name`,
which every published seed and every path dep has. Two directory-name
fallbacks cover a bare directory used as a root: the last segment (a
path dep, `…/uzor`) or the one above it (an installed seed,
`…/uzor/0.1.12`).

## Resolution

`ModuleInliner` runs after parsing, before back-end dispatch:

1. Encounter `use mod.X` → resolve `mod.X` to a `.irj` file:
   - Standard library: `std/*.irj` in resources (classpath
     `/std/X.irj`).
   - User modules: relative to project `sourceRoot`.
   - Seeds (dependencies from `irij.toml [seeds]`): fetched into
     `~/.irij/seeds/<name>/<version-or-ref>/` by `DependencyResolver`
     (on first use, or ahead of time with `irij install`), then
     resolved like local.
2. Recursively inline the imported module's AST.
3. Stripping rules:
   - `mod` declaration kept (EffectRowChecker reads which module a fn
     came from); the emitter skips it.
   - `pub` prefix removed from each pub decl once the file's names are
     resolved.
   - Every top-level fn, binding, handler and cap of a module, pub or
     not, is renamed to `name$module$path` (`helper` in `mymod.helpers`
     → `helper$mymod$helpers`) by `ModulePrivacy.privatize` before the
     module is flattened in. `$` can't occur in an Irij identifier, so
     the name is fresh, and every occurrence of the identifier in the
     module is renamed (uses, binders, parameters, patterns) — renaming
     one identifier consistently is meaning-preserving whatever the
     scoping, so no scope analysis is needed. Without this the emitter's
     program-wide names made privacy fictional: a program defining
     `find-route` replaced `std.serve`'s router internals, two seeds with
     the same helper name called each other's, and a module's
     `pub fn length` replaced the builtin `length` in every file. The one
     name left as written is a binding that mentions its own name —
     `pub sqrt := sqrt` re-exports the builtin, and renaming would make it
     refer to itself. Error messages, spec failures and spec-lint show
     names as written (`ClassEmitter.displayName`); a JVM stack frame
     carries the private name (`boom$greeter`).
4. Name resolution (`ModuleScope`), per file, before the file's decls
   join the program: each file's names are resolved against its own
   `use` lines, with a scope-aware walk (locals shadow everything).
   - A bare name is, in order: a local, the file's own top-level
     definition, a name the file imports (`:open` brings every pub name,
     `{a b}` just those), or a builtin. An imported value is rewritten
     to the private name it stands for; a module's value is unreachable
     any other way, so nothing leaks from one file's imports into
     another, or from a module loaded for someone else.
   - `alias.name` after `use m :as alias` is rewritten to `m`'s private
     `name` — unless a local called `alias` is in scope, which shadows
     it (then it is the local's field).
   - Specs (with variants), effects (with ops), protos (with methods) and
     newtypes keep their names program-wide, so for them the check is
     the enforcement: a file names one only if it declares or imports it
     (`use m {Mode}` brings `Mode` and its variants). They are not
     reached through an alias. Effects that builtins perform (`Console`,
     `Time`, `Env`, `Random`, `JVM`) and builtin specs (`Int`, `Str`, …,
     `Ok`, `Err`) need no import.
   - Errors, each naming the file and position: a module's pub name the
     file does not import (with the `use` line to add); a module's private
     name; a module naming something only the program defines; a name two
     `:open` imports both export, when the file uses it; `use m {nope}`
     and `alias.nope` for a name `m` does not export; `pub use m :as a`.
     Any other unknown name is left to the emitter to report.
   - `pub use m :open` / `pub use m {names}` add those names to the
     module's own exports.
   - Builtins are `Builtins.install`'s names, `ClassEmitter`'s constant
     names and `IntrinsicsEmitter.NAMES` (kept equal to
     `emitBuiltinApp`'s labels by `IntrinsicsNamesTest`).
5. REPL sessions (`BytecodeSession`): each eval imports what earlier
   evals imported and reaches the names they defined as its own. Their
   `use` lines are prepended, so each eval inlines the modules again: a
   module's top-level state starts afresh in every eval.

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

Resolves the `[seeds]` table of `irij.toml` (transitively, through
each seed's own `irij.toml`):

```
[seeds]
vrata = "0.1"                                                   # registry
utils = { git = "https://github.com/user/utils.git", tag = "v1.0" }
local = { path = "../my-lib" }                                  # dev only
```

Registry and git seeds land in `~/.irij/seeds/<name>/<version>/` and
`~/.irij/seeds/<name>/<ref>/`; a directory that exists is reused as is
(tags are not re-fetched). Every fetch is built in a scratch sibling
directory and renamed into place only when complete, so an interrupted
download or clone never leaves a half-filled seed for later runs to
trust.

A seed's `irij.toml` is someone else's input, so its values are
checked before they reach the filesystem or a command line: names and
versions must be one plain path segment (`[A-Za-z0-9][A-Za-z0-9._+-]*`,
no `..`); a git URL may not start with `-` (git would read
`--upload-pack=<cmd>` as an option and run `<cmd>`) nor use the
`<transport>::` form (`ext::` runs a shell command); a ref may not start
with `-`. Git runs with `-c protocol.ext.allow=never` and `--` before
the URL.

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

- **Re-export by accident.** `use std.list :open` doesn't re-export
  those names from your module: importers see your pubs, plus what you
  pass on with `pub use`.
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
