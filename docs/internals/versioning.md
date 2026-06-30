# Versioning — commit-count releases

Every Irij project — the engine, `irij.online`, and all seeds — versions
as **`MAJOR.MINOR.<commit-count>`** (the replikativ / `git-count-revs`
scheme). The `MAJOR.MINOR` base is declared by hand; the patch is
`git rev-list --count HEAD`, so it advances on every commit and nobody
ever hand-picks a patch.

## Why

- **No patch bookkeeping.** You bump the base only for a real MAJOR/MINOR
  change. Fixes just ride the commit count.
- **Monotonic + reproducible.** The patch strictly increases on a linear
  `main`; a given commit always yields the same version.
- **One rule everywhere.** Engine, site, and seeds share it.

Trade-off: the patch is a *global* build number — it does **not** reset
when `MINOR` bumps (`0.8.300 → 0.9.301`, not `0.9.0`). That's monotonic
and SemVer-valid, just not pretty. We keep it global on purpose;
"commits since the base last changed" is fragile.

## Where it's computed

| Surface | Base lives in | Computed by |
|---|---|---|
| Engine (`irij`) | `build.gradle.kts` `val baseVersion` | Gradle (`gitCommitCount()`), stamped into `irij-version.properties` |
| Seeds + `irij.online` | `irij.toml` `[project] version` | `ProjectVersion` (Java) via `irij version` / `irij publish` |

`dev.irij.module.ProjectVersion` is the single source of truth for the
formula and the guards:

- `requireMajorMinorBase` — rejects a non-2-part base with an actionable
  error. **Publishing a hand-picked patch is refused** so it can never
  collide with the commit-count patch.
- `releaseVersion(root, base)` — `base + "." + commitCount`. Used by
  `irij publish` after it verifies branch + clean tree.
- `buildVersion(root, base)` — same on a release branch; on a dev branch
  appends `-<branch>` (SemVer pre-release) so a local `irij build` jar is
  visibly **not** a release. Never reaches the registry (publish refuses
  non-`main`).
- `latestPatch(available, "MAJOR.MINOR")` — picks the highest patch in a
  minor line; the resolver's half of the scheme (below).

## Releases come from `main` (the branch guard)

There is no branch info in a *published* version. "No releases from
branches" is enforced at the boundary, not in the string:

- `irij publish` → hard-requires `main` (or `master`) **and** a clean
  tree, else a documented error.
- Engine CI (`.github/workflows/ci.yml`) → release is **gated**: it fires
  only on a `[release]` marker in the HEAD commit message or a manual
  `workflow_dispatch` (commit-count would otherwise release every push).
- `irij.online` → released on-demand via `./scripts/release`, which tags
  `v<base>.<count>`; `release.yml` re-derives and verifies the tag.

**Every CI checkout that computes a version MUST use `fetch-depth: 0`** —
a shallow clone reports the commit count as `1`.

## Dependency resolution (the consumer half)

Because patches are commit counts, you pin the **minor line**, not an
exact patch. `DependencyResolver.resolveRegistry`:

- a **2-part** pin (`vrata = "0.2"`) → query `GET /api/seeds/<name>`,
  filter its `versions[]` to the `0.2.*` line, and download the highest
  patch (`ProjectVersion.latestPatch`).
- a **3-part** pin (`vrata = "0.2.9"`) → exact match, verbatim, for
  reproducible builds.

So the everyday dep is `name = "MAJOR.MINOR"` (always latest patch);
exact pins remain available when you need to freeze one.

## Gradle gotcha (don't regress this)

`processResources` expands `irij-version.properties` from `project.version`.
Gradle caches that on the *template file's* content (unchanged), so the
embedded version goes stale even though the computed version changed every
commit. `inputs.property("version", project.version)` on the task forces
re-expansion. Without it, `irij --version` lies.
