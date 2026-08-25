# Quint — model-based testing

`std.quint` checks Irij code against a formal specification written
in [Quint](https://quint-lang.org/). You write the model of the
system in Quint, Quint generates traces from it, and Irij replays
those traces against real code, failing at the first step where the
two disagree.

```
spec.qnt ──quint run --mbt──> *.itf.json ──decode──> trace
                                                       │
                       model record ──replay──> result / divergence
```

This is the descendant of the `law` / `verify-laws` property testing
removed in v0.6.12 (spec §6.4). The reason that came out was that
sampling is not proof; the reason this goes in is that the sampling
is done by a tool that also does the proving, and the spec it samples
from is a separate artefact that can be reviewed on its own.

## Layers

| layer | where | pure? |
| --- | --- | --- |
| run the `quint` binary | `dev.irij.runtime.QuintCapability` | no — the only process spawn |
| the CLI as an effect | `effect Quint` + `cap quint-cli`, in `std/quint.irj` | — |
| decode ITF | `std/quint/itf.irj` | yes |
| replay + report | `std/quint.irj` | yes, apart from what the model does |

### Generation is an effect, and that is the whole design

Generating traces needs `quint` on `PATH`. Replaying one needs
nothing. In the Clojure sibling this split is a rule about which
namespace may shell out; here it is the handler boundary:

```
with default-quint        ;; runs the binary
  qcheck driver {traces= 50}

qreplay driver "tests/quint/bank-seed42-trace0.itf.json"   ;; no handler, no binary
```

A `handler canned-quint :: Quint` that returns recorded trace JSON
exercises the entire generate-and-replay path with nothing installed,
which is how `tests/test-quint.irj` tests `check` in CI.

## The ITF decoder

`std.quint.itf` is pure and works on `json-parse` output.

| Quint | ITF JSON | Irij |
| --- | --- | --- |
| `int` | `{"#bigint": "39"}` | `39` |
| `str` / `bool` | `"why"` / `true` | as-is |
| `Set(1,2)` | `{"#set": [...]}` | `#{1 2}` |
| `(7, "seven")` | `{"#tup": [...]}` | `#(7 "seven")` |
| `{x: 1}` | `{"x": {"#bigint": "1"}}` | `{x= 1}` |
| `Map(1 -> "a")` | `{"#map": [[k, v], ...]}` | `{"1"= "a"}` |
| `List` | `[...]` | `#[...]` |
| variant | `{"tag": "Busy", "value": ...}` | `{tag= "Busy" value= 2}` |

Sets and tuples land on native Irij values, which is why `to-set` and
`to-tuple` were added: `conj` is Vector-only and `#{}` / `#(...)` are
literals, so a collection of runtime-known size could not be built.

A Quint `Map` becomes an Irij `Map`, whose keys are strings, so every
key goes through `to-str`. That is not a lossy shortcut — `assoc m 1 v`
stringifies too, so a spec's `Int -> a` map compares equal to an
implementation's map built the same way. A spec keying both `1` and
`"1"` is undecodable and says so.

A record and a sum-type variant are the same JSON object, so a variant
stays `{tag= "Busy" value= 2}` and reshaping it belongs to the model —
the only side that knows which of the two it declared.

### Names cross into Irij's convention

Quint is camelCase; Irij identifiers are kebab-case. That is a grammar
difference, not a preference: `lastError` is not a legal Irij
identifier, so without normalization a model could not write
`{last-error= ...}` for it, could not read `st.lastError`, and would be
reduced to string literals through `get` and `assoc` for its own state.

So every name that crosses is rewritten — state variables, record
fields, `nondet` pick names, action names — `lastError` → `last-error`,
`aBigInt` → `a-big-int`, `last_error` → `last-error`. The keys of a
`#map` are data rather than names and are left alone. A spec carrying
both `lastError` and `last_error` collides, and that is an error naming
both. `key-fn` in the model opts out for variable names.

Module paths are stripped to the last segment first
(`bankTest::bank::balances` → `balances`), and two variables that
normalize alike are an error rather than a silent overwrite.

### Two traps, both recorded rather than read

**Integers ≥ 10^15.** With Quint's default `--backend=rust` these
arrive as bignumber.js internals instead of `#bigint`:

```json
{"s": {"#bigint": "1"}, "e": {"#bigint": "15"},
 "c": [{"#bigint": "90"}, {"#bigint": "7199254740993"}]}
```

which is `9007199254740993`; `c` holds base-1e14 limbs. The threshold
is exact — 999999999999999 encodes correctly, 1000000000000000 does
not. The decoder reassembles it as a string and parses once, so
nothing overflows on the way to a value that would have fit.
`--backend=typescript` gets both right but is much slower, so this
decodes the broken form rather than pushing users off the default.

**Irij `Int` is 64-bit and Quint's is unbounded.** An integer past
2^63 is the one place a legitimate trace cannot be decoded. It raises
and says exactly that, rather than truncating.

### Traces that carry no action

`quint run --mbt` adds `mbt::actionTaken` and `mbt::nondetPicks` to
every state. **`quint test` and `quint verify` add neither** — verified
against 0.32.0, not read from documentation. So a scripted run and an
Apalache counterexample can only drive an implementation if the spec
records its own action in an ordinary variable:

```
var lastAction: str
var lastPick: { who: Account, amount: int }
```

and the model says where with `action-path` / `nondet-path`, given in
normalized names:

```
action-path= #["last-action"]
nondet-path= #["last-pick"]
```

The variable each path starts at leaves the compared state: it is the
spec's own bookkeeping, and the implementation must not be asked to
reproduce it.

`mbt::actionTaken` is the **first named action** the step executed,
and is `""` only when the branch that fired is built from bare
assignments with no named action anywhere inside it. That cannot be
dispatched, and the fix is in the spec: name the combination.

## Models

A model binds a Quint specification to the code implementing it. It
comes in two shapes because Irij has two kinds of system, and it can be
written two ways: as a declaration, or as the record that declaration
desugars to.

### The `model` declaration

```
model bank :: "spec/bank.qnt" :pure {main= "bankTest"}
  start                  => {balances= {alice= 0 bob= 0} last-error= ""}
  deposit  st who amount => {...st balances= (credit st.balances who amount)}
  withdraw st who amount => {...st balances= (credit st.balances who (0 - amount))}
  overdraft st _ _       => {...st last-error= "insufficient funds"}
```

The clauses read like handler clauses because they do the same job:
one per action the spec can take, named for it, with that action's
`nondet` picks bound **by name** in the parameter list. `who` and
`amount` are the spec's own names, normalized to Irij's convention.
A pick the trace does not carry arrives as unit.

`start` (pure) and `init` / `state` / `halt` (live) are the lifecycle
clauses; every other name is an action. The optional map after the
mode is spliced into the record as written, so `main`, `ignore`,
`action-path` and the rest need no grammar of their own.

A live model declares the effects it performs once, in the header:

```
model bank :: "spec/bank.qnt" :live ::: Bank
  init                => bank-reset ()
  state               => bank-read ()
  deposit  who amount => bank-deposit who amount
```

That row lands on every clause, which is why the clauses are lowered
to functions rather than lambdas — a lambda declares no row, and an
effect performed from one is refused at the perform. See
[parser.md](parser.md) for the desugaring.

`model` is a soft keyword: still a map field and a dot-access field,
which is where a name this ordinary turns up.

### The record

The declaration produces this, and it can also be written directly —
useful when a model is assembled or modified programmatically, as the
tests do with `{...sloppy ignore= #{"last-error"}}`.

**Pure** — replay is a fold. `start` is the initial state and each
action is `(state picks -> state)`. Nothing is set up or torn down and
one trace cannot leak into the next.

```
driver := {spec-file= "spec/counter.qnt" mode= :pure \
           start= {n= 0} \
           actions= {incr= (st p -> {...st n= st.n + p.by})}}
```

**Live** — something is running. `init` starts or resets it, each
action is `(picks -> ignored)`, `state` reads the world back, `halt`
stops it.

```
driver := {spec-file= "spec/bank.qnt" mode= :live \
           init= start-bank state= read-bank \
           actions= {deposit= do-deposit}}
```

Picks arrive as one record, so a handler reads `p.who` by the name the
spec gave it. There is no argument-shape declaration to keep in sync —
the lambda in the model *is* the mapping. (The Clojure sibling needs
one because it annotates existing functions it must not touch.)

`spec-file`, not `spec`: `spec` is a reserved word.

### What the checker sees of a live model

A model's actions reach `std.quint` inside a `Map` and are called
through it. Irij carries an effect row through a single `(Fn):eff`
field — that is how `std.serve` types a route — but there is no way yet
to say "a `Map` whose values are functions with row eff", so a live
model's effects do not appear in `check`'s row.

They still run, with two things on the author rather than on the
checker: each of the model's own functions declares the row it
performs (a bare lambda that performs is rejected at the perform, since
it declared nothing), and the handlers are installed around the call so
one system lives across the whole run.

```
with in-memory-bank
  with default-quint
    qcheck driver {traces= 50}
```

## Lifecycle

```
for each trace:
  start the model          pure: `start`   live: `init`
  compare against state 0
  for each following state:
    the action's handler, with the picks
    read the state back and compare
  `halt`, if there is one
```

State 0 is compared straight after the model starts, so an `init` that
does not fully reset shows up as a step-0 divergence on the second
trace rather than as corruption of the seventeenth. The cost is that
`init` runs once per trace: fifty traces mean fifty starts, so make it
cheap.

## Errors are one thing, divergence is another

A **broken model** raises: an action nothing handles, a `state` reader
that is not a function, a spec file that is not there, quint exiting
non-zero. Those are mistakes in the test.

A **divergence** is a value in the result record. It is the expected
output of a testing tool, and it carries the step, the action, the
picks, both sides of the disagreement restricted to the variables that
actually differ, and the trace JSON itself — not a path, because the
scratch directory quint wrote it to is deleted before `check` returns.

```
the spec and the implementation diverged on trace 0 of 20, seed 42
  step     4, action deposit
  picks    {amount= 11 who= alice}
  expected {balances= {alice= 11 bob= 0}}
  actual   {balances= {alice= 12 bob= 0}}
  reproduce  cd spec && quint run bank.qnt --mbt --seed=42 ...
```

The reproduce line is pasteable, which is why an absent seed is
generated in `QuintCapability.run` rather than left to quint.

## The four ways in

| function | trace source | for |
| --- | --- | --- |
| `check` | `quint run --mbt`, N random traces | the default |
| `check-run` | `quint test --match ^name$` | one scripted scenario a person wrote down |
| `verify` | `quint verify --invariant I` | Apalache proves it, or hands back a counterexample |
| `replay-file` | a committed `.itf.json` | regression tests, CI, no quint |

Each has a `q`-prefixed sibling (`qcheck`, `qcheck-run`, `qverify`,
`qreplay`) that raises the formatted report instead of returning data,
which is what plugs into `std.test`.

`verify` reports two separate facts. `invariant` says whether the
spec's own property holds. `failure` says whether the code diverged
from the counterexample, and is absent when it did not — meaning the
code reproduces the spec's bug faithfully. That is an answer, not a
pass, and it points at the spec.

An invariant that holds writes no trace at all, so zero traces is the
pass there, unlike `check` where it is an error.

## From a random failure to a committed test

`check` and `replay-file` are the two ends of one workflow, and the
file between them is the whole point of generating traces at random: a
trace that found a bug once has to be able to find it again on a
machine with no Node.

```
qcheck            divergence ── save-failure ──> tests/quint/failures/
                                                       │  mv, by a person
                                                       v
qreplay           tests/quint/bank-seed42-trace0.itf.json
```

Two properties make that a workflow rather than a pile of files:

- **The name is deterministic** — spec, seed and trace index — so
  re-running a failing seed rewrites one file and nothing accumulates.
- **The drop zone is not the archive.** `failures/` is written on every
  divergence and belongs in `.gitignore`; promoting a trace up into
  `tests/quint/` and naming it in a `qreplay` is a decision a person
  makes. A failing run never dirties the repository, and a committed
  trace is always one somebody chose to keep.

`save-failure` is separate from `qcheck` rather than folded into it, so
`check` stays a function of data returning data and does not drag
`FileIO` into every model's effect row.

## `irij quint doctor`

Says whether the binary is reachable and whether it is the version the
ITF decoding was verified against, and exits non-zero when traces
cannot be generated here. There is no `irij check`: a model is ordinary
Irij code, so running one is `irij run` or `irij test`.

## Running quint

`QuintCapability` is the only place in Irij that starts a process.

- Both pipes are drained concurrently. A pipe that fills blocks the
  writer, so draining one after the other deadlocks as soon as the
  second fills while the first is still being read.
- ITF goes to a scratch directory that is deleted before the call
  returns, the files having been read out of it first.
- `run` and `test` execute in the spec's own directory, so sibling
  modules resolve and `#meta.source` stays a bare filename.
- `verify` executes in the scratch directory instead, because Apalache
  writes an `_apalache-out/` directory of logs into the working
  directory and cannot be told not to. The spec is passed absolute so
  it still resolves, and the logs go with the scratch directory.
- The outcome of `verify` is **not** in its exit code: holding exits 0,
  while a counterexample, an unknown invariant, a spec that will not
  typecheck and a missing file all exit 1. What separates them is
  whether a trace was written, so that is what `std.quint` branches on.
- `--max-samples` is attempts and `--n-traces` is traces written;
  quint defaults samples to 1 and rejects n-traces greater than it, so
  `traces` acts as the floor for samples rather than the value.

The capability decides nothing beyond that. It returns
`{exit stdout stderr cmd dir seed traces}` and `std.quint` turns a
non-zero exit into a diagnosis, where the message can be read and
changed without recompiling the runtime.

Quint is not bundled. It is `npm i -g @informalsystems/quint`, and the
decoder was verified against **0.32.0** (`QuintCapability.TESTED_VERSION`).

## Fixtures are recordings

Everything under `tests/quint/` came out of the tool, not out of a text
editor. The `.qnt` files are the specs; the `.itf.json` files are what
Quint 0.32.0 wrote when run against them, including the Apalache
counterexample. A fixture that looks about right would test the
decoder against our idea of the format rather than against the format.
