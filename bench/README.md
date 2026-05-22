# `bench/` — what these numbers do and don't mean

`bench/run.sh` runs each subdirectory's `main.{irj,clj,py}` under `irij`
+ Clojure + Babashka + CPython and reports median wall-clock ms. Cute,
but **the headline numbers are a noisy proxy for "real perf"** — read
the caveats below before drawing conclusions.

## Caveat 1 — JVM startup dominates small benches

`java -jar irij.jar` carries ~50 ms of cold JVM startup before any
Irij code runs. Clojure adds another ~250 ms on top for its own
runtime warmup. CPython starts in ~20 ms. Babashka (native binary)
starts in ~30 ms.

For benches that finish in < 200 ms, **the gap between languages is
mostly startup, not work.** A 78 ms `string-cat` for Irij vs 38 ms
for CPython means ~30 ms of actual work on each side after subtracting
their respective startup costs — not "Irij is twice as slow at string
ops".

To see real perf, prefer benches where the work clearly exceeds
startup (`fib`, `tak`, `vec-sum` at N=1000+).

## Caveat 2 — workloads must match across languages

`effects-bump` used to be a fairness bug: the Irij version exercised
50 `perform` calls through an effect handler; the Clojure / Python
versions just bumped a counter 50 times. Different micro-benchmarks
under the same name. Now split into two:

- **`effects-overhead`** — Irij-only. Compares a 50-perform handler
  body against the equivalent imperative loop *in Irij*. Tells you
  what Irij's effect dispatch costs, not what other languages do.
- **`counter-loop`** — plain 50-step counter increment in every
  language. Fair cross-lang comparison.

If you write a new bench, the rule is: **every language's `main.*`
must do the same observable work.** "Idiomatic" diverges; here it
needs to be apples to apples.

## Caveat 3 — single-shot timing

Each iteration is a fresh `java -jar` / `python3` / `clojure -M`
invocation. No JIT warmup carries between runs, no inter-run cache.
For a fair JIT-warmed comparison, time *inside* the program (`now-ms`
in Irij, `time.perf_counter()` in Python, `System/currentTimeMillis`
in Clojure) and run the workload N times in one process.

This harness deliberately doesn't do that because it answers a
different question: **"how fast does a cold script run end-to-end?"**
— which is the relevant question for command-line tools, build
scripts, CI tasks. If you want steady-state throughput, hand-roll
the bench.

## Caveat 4 — different code shapes within one bench

`sum-iter` exposes this: Irij + Clojure use tail-recursion; Python
uses a `while` loop. Both terminate; the comparison is "what each
language's idiomatic accumulator loop costs". That's fine as long as
you remember the shape difference when interpreting the number.

## How to actually interpret the table

| Bench | What's being measured | Watch out for |
|---|---|---|
| `fib` | Recursive arithmetic, no allocation | Clean — startup is a small fraction at fib(32) (~3.5M calls). |
| `tak` | Recursive arithmetic, wider call graph | Irij's MutableCallSite indirection adds per-call overhead the JIT can sometimes inline through; the wider nested-call shape stresses it harder than fib. |
| `sum-iter` | Tight tail-recursion | Mostly JVM startup at N=1000. Try N=1_000_000 for honest numbers. |
| `vec-sum` | Allocation-heavy vector build + fold | Compares persistent-vector backends across languages. |
| `string-cat` | Quadratic-ish string concat | CPython has a special-case in-place optimisation for refcount-1 strings; JVM doesn't. Don't read this as "Irij string ops slow". |
| `counter-loop` | Trivial increment loop | Pure startup race. |
| `effects-overhead` | Irij effect dispatch vs Irij imperative | Irij-only. Both columns are Irij. |

## How to add a new bench

1. `mkdir bench/<name>/`
2. Write `main.irj`, `main.clj`, `main.py` — all three doing the
   *same* observable work, idiomatically in each language.
3. Add a header comment in each `main.*` explaining what it
   measures and any caveats (e.g. "uses tail-recursion in Irij /
   Clojure, while-loop in Python — both terminate").
4. `./bench/run.sh -n 3 <name>` to smoke-test.
5. Update the interpretation table in this README.
