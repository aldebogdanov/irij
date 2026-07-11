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

1. Parse `irij.toml`; require name/version/author/description.
2. Validate link fields are web URLs (above).
3. Version guards (see `versioning.md`): 2-part base only, `main`
   branch only, clean tree only. Publish version =
   `ProjectVersion.releaseVersion` = `base + "." + commit count`.
4. Refuse path deps — they can't resolve outside this machine.
5. Bundle `irij.toml` + `**/*.irj` + README into a tar.gz.
6. POST multipart (`metadata` JSON part + `tarball` part) to
   `<registry>/api/seeds/publish` with `Authorization: Bearer <token>`
   (token from `$IRIJ_TOKEN` or `~/.config/irij/token`; created at
   the registry's `/dashboard`).

The `metadata` JSON carries: `name`, `version` (full 3-part),
`description`, `author`, `license`, `website`, `repo`, `docs`.
Unknown keys are ignored by older registries, so adding fields is
forward-compatible.

## Registry storage (irij.online)

Two tables split package-level from version-level data:

- `packages` — `name`, `owner_id` (FK to users; publish 403s on
  mismatch), `license`, `website`, `repo`, `docs`, timestamps.
  Upserted on every publish (`ON CONFLICT(name) DO UPDATE`).
- `versions` — one row per published version: `version`,
  `description`, `readme`, `author`, `checksum`, `published`.

Schema migrations are PRAGMA-probe + `ALTER TABLE` on boot
(SQLite has no `ADD COLUMN IF NOT EXISTS`), so old prod DBs pick up
new columns without manual steps.

## Consumption

`DependencyResolver` resolves `[seeds]` entries: registry pins go
through `GET /api/seeds/<name>` (2-part pin → highest patch in the
minor line, 3-part → exact; see `versioning.md`), git deps are cloned
at the tag/commit, path deps are dev-only and refused at publish.
