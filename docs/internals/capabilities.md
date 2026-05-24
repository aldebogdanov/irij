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

Phase 1 (shipped): parser + AST + lint. The `cap` decl form is
recognised, registered in `EffectRowChecker.capEffect`, and bare or
cross-effect references are rejected at compile time.

Phase 2 (shipped): emitter lowers `cap-name.method args` inside a
matching handler clause to a JavaRef-equivalent dispatch on the
bound provider class. End-to-end tests in `CapDispatchTest` verify
a real Irij program reaches a Java provider method through the cap.

Phase 3 — stdlib migration, one effect at a time. Each sub-phase
moves one provider class into `dev.irij.runtime.*`, rewrites the
matching `std.*` module to use the cap, deletes the old `raw-*`
surface from `Builtins` / `EffectRowChecker.BUILTIN_EFFECTS` /
`ClassEmitter` emit table, and updates the test fixture.

- **3a — Db (shipped)**: `JdbcCapability` provider; `std.db`
  exposes `db-open`, `db-close`, `db-query`, `db-exec`,
  `db-transaction` as effect ops (was: open/close were plain fns
  calling `raw-db-*`). `raw-db-*` names gone from the runtime
  surface entirely; tests rewritten to use only effect ops.
- **3b — Http client (shipped)**: `HttpClientCapability`,
  `std.http` rewritten to route through `http-client.request`,
  `raw-http-request` delisted from all three registries.
- **3c — Serve / SSE (shipped)**: single `ServeCapability` holds
  both the server loop and the SSE writer ops (they share the
  `HttpExchange`, splitting would force shared plumbing back into
  Irij). `std.serve` Serve effect grew `sse-response`, `sse-send`,
  `sse-close`, `sse-closed?` ops; `std.datastar` rewritten to call
  them. `raw-http-serve` + `raw-sse-*` delisted from all registries.
- **3d — FS / Multipart**: `FsCapability` (read-file, write-file,
  list-dir, make-dir, delete-file, append-file, file-exists?),
  `MultipartCapability` (raw-multipart-field, raw-multipart-save).
- **3e — Session**: `SessionCapability` for the Playground sandbox
  (raw-session-create, raw-session-eval, raw-session-destroy,
  raw-session-subscribe, raw-session-unsubscribe,
  raw-session-cleanup).

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

## Emitter (phase 2)

The emit-time intercept lives near the top of
`ClassEmitter.emitApp`: an `Expr.App` whose callee is
`Expr.DotAccess(Var capName, methodName)` and whose `capName` is
in `capProvider` gets rewritten into

```
Expr.App(Expr.JavaRef("<providerClass>/<methodName>"), args)
```

and re-dispatched through the existing JavaRef code path. `JavaRef`
already produces a `BuiltinFn` via
`RuntimeSupport.javaStaticRef` → `JavaInterop.resolveStaticRef`,
which handles overload resolution + arg coercion at call time. So
phase 2's whole emit contribution is a 5-line rewrite — no new
bytecode shape, no descriptor reflection in the emitter, no
singleton-field bookkeeping.

**Provider contract (phase 2):** methods are `public static`, take
+ return `Object` (or autoboxable primitives). The class lives at
a stable FQN that the `cap` decl references as a string literal.
Instance / singleton providers will follow in phase 2.5 if needed;
none of the planned migrations (`Jdbc`, `Fs`, `HttpClient`,
`HttpServer`, `Jvm`) need them — each is naturally a utility class
of static dispatchers around the underlying JDK API.

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
