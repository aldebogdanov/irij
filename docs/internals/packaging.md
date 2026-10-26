# Packaging — seeds, the manifest, and `irij publish`

A **seed** is an Irij package: a git repo with an `irij.toml` manifest,
`.irj` sources, and (optionally) a README. Seeds are published to a
registry (canonically `https://irij.online`) and consumed via the
`[seeds]` table of a downstream project's manifest.

## The manifest — `irij.toml`

Parsed by `dev.irij.module.ProjectFile` into `ProjectMeta` + a
`Dependency` list. All parsing is toml4j; missing string fields
normalize to `""`, never null.

```toml
[project]
name = "my-app"            # required to publish
version = "0.1"            # required; 2-part MAJOR.MINOR (see versioning.md)
description = "…"          # required to publish
author = "user"            # required to publish
license = "MIT"
website = "https://…"      # optional — shown on the registry seed page
repo = "https://github…"   # optional — likewise
docs = "https://…/docs"    # optional — likewise

[seeds]
vrata = "0.2"                                        # registry, minor line
utils = { git = "https://…/utils.git", tag = "v1" }  # git
local = { path = "../lib" }                          # path (dev only)

[jvm]                      # optional — Java libraries (§ Java libraries below)
"org.neo4j.driver:neo4j-java-driver" = "5.28.5"

[trust]                    # optional — who may reach Java (jvm-capability.md § Trust)
caps = ["acme.clock"]      # modules that may declare a Java `cap`
jvm = ["seed-readme"]      # modules that may write Java interop by hand
jars = ["neo4irij"]        # seeds whose [jvm] libraries may be fetched and loaded
```

### Link fields (`website` / `repo` / `docs`)

Optional. When present they must be `http(s)://` URLs — the CLI
refuses to publish otherwise, and the registry re-validates
server-side (a `javascript:` URL echoed into an `href` on the seed
page would be stored XSS, so the check cannot live only in the
client). The registry stores them per-package, **latest-publish-wins**
(same rule as `license`): the manifest is the source of truth, so
removing a link there removes it from the seed page on the next
publish. Older clients that don't send the fields leave them untouched
on the server default (`''`).

## `irij publish` (alias `sow`)

`IrijCli.runPublish`, in order:

1. Parse `irij.toml`; require name/version/author/description, and a
   `[trust]` section (empty is fine; `irij audit --fix` writes it — see
   jvm-capability.md § Trust). Consumers enforce their own `[trust]`,
   not the seed's; requiring it makes the author state where the seed
   reaches Java.
2. Validate link fields are web URLs (above).
3. Version guards (see `versioning.md`): 2-part base only, `main`
   branch only, clean tree only. Publish version =
   `ProjectVersion.releaseVersion` = `base + "." + commit count`.
4. Refuse path deps — they can't resolve outside this machine — and
   `[jvm] repos` (a seed can't choose where its consumers download from).
5. Bundle `irij.toml` + `**/*.irj` + README into a tar.gz.
6. POST multipart (`metadata` JSON part + `tarball` part) to
   `<registry>/api/seeds/publish` with `Authorization: Bearer <token>`
   (token from `$IRIJ_TOKEN` or `~/.config/irij/token`; created at
   the registry's `/dashboard`).

The `metadata` JSON carries: `name`, `version` (full 3-part),
`description`, `author`, `license`, `website`, `repo`, `docs`, and
`jvm` — the seed's `[jvm]` libraries as `"group:artifact:version"`
strings (`[]` without any), so the registry can show what Java a seed
brings before anyone installs it. Publish refuses a seed whose `[jvm]`
sets `repos`: consumers would refuse it anyway (§ Java libraries).
Unknown keys are ignored by older registries, so adding fields is
forward-compatible.

## Registry storage (irij.online)

Two tables split package-level from version-level data:

- `packages` — `name`, `owner_id` (FK to users; publish 403s on
  mismatch), `license`, `website`, `repo`, `docs`, timestamps.
  Upserted on every publish (`ON CONFLICT(name) DO UPDATE`).
- `versions` — one row per published version: `version`,
  `description`, `readme`, `author`, `checksum`, `published`.

`readme` is not in the publish metadata: the registry reads it out of
the uploaded tarball (`seed-readme.irj` in irij.online) — the
archive's root `README.md`, else `README`, at most 256 KiB, walking
tar headers and skipping file bodies so no tarball is unpacked into
memory. That works for every CLI release, and at boot the registry
fills versions stored with `''` from their kept tarballs
(`<workdir>/seeds/<name>/<version>.tar.gz`). The seed page renders it
as Markdown in the browser, through DOMPurify — a README is written
by whoever published the seed.

Schema migrations are PRAGMA-probe + `ALTER TABLE` on boot
(SQLite has no `ADD COLUMN IF NOT EXISTS`), so old prod DBs pick up
new columns without manual steps.

## Consumption

`DependencyResolver` resolves `[seeds]` entries: registry pins go
through `GET /api/seeds/<name>` (2-part pin → highest patch in the
minor line, 3-part → exact; see `versioning.md`), git deps are cloned
at the tag/commit, path deps are dev-only and refused at publish.

## Java libraries — `[jvm]` and `irij.lock`

A project or a seed lists the Java libraries it needs in `[jvm]`:

```toml
[jvm]
"org.neo4j.driver:neo4j-java-driver" = "5.28.5"
repos = ["https://repo.example/m2"]   # optional, root project only
```

Keys are quoted `"group:artifact"` coordinates (TOML would split a bare
dotted key); values are **exact** versions — ranges, `LATEST`,
`RELEASE` and `-SNAPSHOT` are refused at parse time, so a manifest
always names one jar. `ProjectFile.parseJvm` validates; toml4j hands
quoted keys back *with* their quotes, which it strips. Seeds' tarballs
already carry `irij.toml`, so a consumer reads each seed's `[jvm]` from
its resolved source dir.

### Resolution (`dev.irij.module.jvm`)

`ProjectDeps.resolve(projectRoot)` is the one entry point for every
"run in a project" caller — `irij run`/`test` (`BytecodeRunner`), the
REPL, nREPL, `irij build`, `irij install`, `irij deps`. It resolves
`[seeds]` (`DependencyResolver`), then:

1. **Plan** (`JvmDeps.plan`). Collect a request per `[jvm]` entry from
   the root and every seed. A seed that sets `repos` is refused. With a
   root `[trust]`, a seed with libraries that `jars` doesn't name is
   recorded as untrusted. Requests for one `group:artifact` merge: the
   root's version wins (a note says which seed asked otherwise); among
   seeds the highest wins, but two **major** versions are an error the
   root settles by pinning one — a program has one classpath.
2. **Trust gate.** Untrusted seeds stop here (`Plan.requireTrusted`),
   before anything is downloaded; the error prints the `jars = [...]`
   line to add.
3. **Fetch** (`JvmDeps.fetch`). Maven Resolver (the `supplier-mvn3`
   bundle: Maven 3 POM semantics, transitive, nearest-wins, runtime
   scope, optional deps skipped) resolves the merged roots against the
   repos — Maven Central unless the root lists `repos`, which then
   *replace* it — into a Maven-layout cache at `~/.irij/jars`
   (`irij.jars.dir` overrides; tests use it). Checksum policy is
   `fail`: a jar whose `.sha1` doesn't match is not used.
4. **Lock.** The result is written to `irij.lock` beside `irij.toml`:

```toml
version = 1
inputs = "<sha256 of the plan: repos + merged roots>"

[[jar]]
coord = "org.apache.commons:commons-text:jar:1.12.0"
sha256 = "de02…"
```

While `inputs` matches the current plan, later runs skip resolution.
When every locked jar is already in the cache with its locked sha256
(hashes are remembered per file size + mtime), Maven Resolver isn't
even started — the normal case, and what keeps `irij test` (one
resolution per test file) fast and offline. Otherwise they fetch
exactly the locked coordinates, then check each jar's sha256
— a repository (or cache) serving a different file for the same
version is refused, which the remote `.sha1` alone can't catch (it
comes from the same server). Any change to `[jvm]`, here or in a seed,
changes `inputs` and resolves again. Only the root's lock counts; a
seed's own `irij.lock` isn't published. Maven Resolver logs through
SLF4J with no provider on the CLI's classpath, so
`slf4j.internal.verbosity=ERROR` silences its startup warning.

### The deps class loader

`dev.irij.runtime.DepsClassLoader.install(jars)` makes the jars the
process's Java libraries. Every loader that defines compiled Irij
classes (`BytecodeRunner.BytesLoader`, `BytecodeSession.Loader`, the
built JAR's program loader) has it as parent, and
`JavaInterop.resolveClass` looks names up through it — so both a `cap`
(lowered to `INVOKESTATIC`, linked by the program class's loader) and
hand-written `Class/member` reach library classes. Without `[jvm]` the
current loader is the runtime's own and nothing changes.

Lookup is **child-first**: the JDK (platform loader), then the jars,
then the runtime. A library therefore gets the gson / ASM / antlr it
shipped with, not the runtime's copy — Irij's own dependencies aren't
relocated, so parent-first would hand a library whatever version
`irij.jar` happens to bundle. `dev.irij.*` and `java.*` always come
from the parent: a jar can't replace the runtime. The fallback to the
runtime is limited to `DepsClassLoader.RUNTIME_PACKAGES` — Irij and the
libraries `irij build` bundles with it (the same list drives the
bundling). The CLI's own libraries (Maven Resolver, SLF4J, LSP) stay
invisible, so a library sees the same runtime under `irij run` as in
the built app: SQLite's driver, finding the CLI's SLF4J, printed a
no-provider warning under `irij run` only. `getResources` lists
the jars' resources before the runtime's, so a library's
`ServiceLoader` finds its own providers first. The run thread's
context class loader is the deps loader for the same reason (JDBC,
logging, Netty look there).

Installing the same jar list again keeps the loader (the test runner
goes file to file); a different list replaces it and clears
`JavaInterop`'s class cache. One project per process: an nREPL or REPL
loads its project's jars at start, and changing `[jvm]` needs a
restart.

### `irij build` with libraries

Libraries stay **whole jars** in the app JAR — not merged into it — so
their signatures, `META-INF/services` files and multi-release layouts
survive:

```
META-INF/MANIFEST.MF     Main-Class: dev.irij.runtime.JarLauncher
__irij_program/irij/…    the compiled program (off the classpath)
__irij_lib/<sha256>.jar  each library, classpath order
__irij_lib/index         main irij.Program / jar <sha256> <file name>
dev/irij/…, std/…        the runtime, as before
```

The program can't sit on the JAR's own classpath: its classes must link
against the libraries, which live in their own loader. `JarLauncher`
copies each library out to `~/.irij/lib/<sha256>.jar` (`irij.lib.dir`
overrides; a read-only home falls back to `$TMPDIR/irij-lib-<user>`),
reusing a file only when its sha256 matches, installs the deps loader,
and defines the program from `__irij_program/` in a loader under it —
the arrangement `irij run` has. An app without `[jvm]` keeps the old
layout (`Main-Class: irij.Program`). The runtime bundle never includes
`dev.irij.module.jvm` or Maven Resolver, and copies only the service
files it needs (jline's providers): the CLI's other libraries aren't
bundled, so their service files would name missing classes. No JDBC
driver is bundled either — a program's driver is a `[jvm]` library
(capabilities.md, *3a — Db*).

### Commands

- `irij install` — fetch seeds and libraries; writes `irij.lock`.
- `irij deps` — what each declarer asked for, and the locked jars in
  classpath order.
- `irij audit` — after the Java sites, a *Java libraries* section: each
  declarer's coordinates, seeds marked `trusted` / `NOT TRUSTED`
  against `[trust] jars`, and whether `irij.lock` is current. Downloads
  nothing; exits 1 on an untrusted seed.
