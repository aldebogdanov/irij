# Capabilities

Capabilities are how Irij names the bridge between an effect's
operations and the JVM (or, eventually, any other host platform)
code that performs them. The pattern: declare an effect, bind a
capability-provider class to it, write a handler that uses the cap
to satisfy the effect ops. The cap name is *only* visible inside
clauses of handlers for the matching effect — so `raw-*` builtins
can disappear from the user-reachable surface without removing the
escape hatch they provided.

## Status

Phase 1 (this commit): parser + AST + lint. The `cap` decl form is
recognised, registered in `EffectRowChecker.capEffect`, and bare or
cross-effect references are rejected at compile time. The emitter
skips `CapDecl` (no runtime representation yet); `cap-name.method`
calls in valid positions still need phase 2's lowering before they
run end-to-end.

Phase 2 (next): emitter lowers `cap-name.method args` inside a
matching handler clause to `INVOKEVIRTUAL` (or `INVOKESTATIC`) on
the bound provider class. Once shipped, the existing `raw-*`
builtins (`raw-db-*`, `raw-http-*`, `raw-fs-*`) become methods on
capability providers and the `raw-` prefix is delisted from
`Builtins` + `EffectRowChecker.BUILTIN_EFFECTS` + `ClassEmitter`.

## Syntax

```
cap <name> :: <Effect> = "<fully-qualified-Java-class>"
pub cap <name> :: <Effect> = "<fully-qualified-Java-class>"
```

The provider class is given as a string literal. This keeps phase 1
unambiguous against expression-level dot access (`a.b.c` could
otherwise be a member-access chain or a classpath). A dedicated
`JAVA_CLASS` token can replace the string in a future phase if we
want prettier syntax; nothing about the design forces strings
specifically.

`pub cap` re-exports the binding through `use mod :open`. Phase 1
makes every cap (pub or not) visible to the effect-row checker
across the whole module-inlined program — sufficient for stdlib +
seed scenarios. Per-module private caps are tracked as future work.

Example:

```
mod std.db

effect Db
  db-open  :: Str  Int
  db-query :: Int Str Vec
  db-close :: Int ()

cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"

pub handler default-db :: Db ::: JVM
  db-open  path     => resume (db-jdbc.open path)
  db-query conn sql => resume (db-jdbc.query conn sql)
  db-close conn     => db-jdbc.close conn ; resume ()
```

User code that imports `std.db` never sees `db-jdbc`. The lint pass
makes the cap unreferenceable outside `Db`-handler clauses.

## Lint rules (`EffectRowChecker`)

The checker maintains two pieces of state:

| Field | Purpose |
|---|---|
| `capEffect : Map<String, String>` | cap-name → effect-name registry, populated by walking every `CapDecl`. |
| `currentClauseEffect : String?` | Effect-name of the handler clause currently being walked. Pushed in `checkHandler`, popped on exit. `null` outside any clause body. |

Three lint rules fire on each `Expr` walk:

1. **Bare cap reference rejected.** `walkExpr` on `Expr.Var v` where
   `v.name() ∈ capEffect.keySet()` throws — option (a): capabilities
   are not first-class values.
2. **Dot-access target must match clause effect.** `walkExpr` on
   `Expr.DotAccess da` where `da.target() instanceof Var v` and
   `v.name() ∈ capEffect.keySet()` checks
   `currentClauseEffect == capEffect.get(v.name())`. Mismatch (wrong
   handler) or `null` (no enclosing handler) throws.
3. **Duplicate cap names with different effects rejected.** Registry
   build catches this in the first pass — two caps with the same
   name binding to different effects is treated as a configuration
   error, not silently overwritten.

## Design choices

### Why string for the provider classpath

A dedicated `JAVA_CLASS` lexer token would clash with regular
dotted-IDENT expressions (`a.b.c` as member access). Disambiguating
contextually inside the parser is doable but adds grammar complexity
that buys nothing in phase 1. STRING is unambiguous, easy to parse,
easy to validate at AST-build time. Future phases can introduce a
prettier form without breaking the AST shape.

### Why caps are not first-class (option a)

If `db-jdbc` could be assigned to a variable, that variable could
outlive the matching handler clause's scope and be invoked from
arbitrary code — defeating the entire point. The cheap version of
preventing that is the syntactic rule: cap-name only legal as a
dot-access target. Stronger versions (linear / affine types so the
cap is a value but cannot be stored or passed) are future work
under "option b" — recorded as tech debt to revisit when Irij grows
linear types.

### Cap declarations at top level only

`cap` is a top-level decl. No lexical-scope `let cap …` forms. This
keeps the registry simple (one global map per compilation), matches
how effects + handlers are declared, and avoids the question of how
nested cap shadowing would interact with handler-clause scope.

### Multiple caps per effect

Allowed and expected — `cap db-jdbc-default :: Db = …` and
`cap db-mock :: Db = …` can coexist. Each handler picks which cap
its clauses reach for. This is how mock-handlers in tests get a
different provider with the same surface.

## Emitter (phase 2 sketch)

When the emitter sees `cap-name.method args` inside a handler
clause, it will:

1. Look up `cap-name` in the global cap registry → provider class.
2. Resolve `method` on that class via reflection at compile time
   (to pick descriptor + static-vs-instance) — same machinery as
   the existing `Class/method` Java interop path.
3. Emit `INVOKESTATIC` or `INVOKEVIRTUAL` accordingly. For instance
   providers, the provider singleton is fetched via a generated
   `<clinit>` field; for static-method providers, the class itself
   is enough.

## Future work

- **Phase 2**: cap dispatch in `ClassEmitter` (see above).
- **Phase 3**: convert existing stdlib (`std.db`, `std.http`,
  `std.fs`, `std.serve`) to use the pattern. Delist `raw-*` builtins
  from `Builtins`, `EffectRowChecker.BUILTIN_EFFECTS`, and
  `ClassEmitter`'s emit table once the migration is complete.
- **Pure-Irij caps**: allow a `cap` RHS to be a record/map of fn
  values rather than only a Java classpath. Useful for in-process
  mocks and for designs where the cap provider is itself written
  in Irij.
- **Multi-language caps**: same RHS form, different scheme — `cap
  db-rs :: Db = "rust://crate@version"` once Irij grows JNI / Panama
  bindings.
- **Per-module private caps**: filter non-`pub` cap decls from the
  re-export pass in `ModuleInliner` so a library can keep a cap
  internal to itself.
- **Tech-debt: option (b) — caps as opaque-typed unstoreable
  values**: revisit once linear / affine types land. Would let caps
  be values without losing the safety property option (a) gives via
  the syntactic rule.
