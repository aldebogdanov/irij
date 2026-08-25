# Quint fixtures

Every `.itf.json` here is a **recording**: what Quint 0.32.0 actually
wrote when run against the `.qnt` beside it, on 2026-08-25. None was
written by hand. A fixture that looks about right would test the
decoder against our idea of the format rather than against the format.

| spec | what it pins |
| --- | --- |
| `bank.qnt` | the ordinary case — `quint run --mbt`, two accounts, three actions |
| `shapes.qnt` | every ITF value encoding in one state, including the ≥10^15 integer trap |
| `tracked.qnt` | a spec that records its own action, so `quint test` and `quint verify` traces can drive an implementation |
| `collide.qnt` | two variables that normalize to the same name |
| `anonymous.qnt` | a branch with no named action inside it, which records `""` |

The `.qnt` files come from
[quint-connect-clj](https://github.com/aldebogdanov/quint-connect-clj)
(`dev/fixtures/`), the Clojure sibling of this feature, where the
recorded behaviour they pin is written up in `docs/notes/itf-format.md`.
The traces here were re-recorded rather than copied.

## Re-recording

```sh
cd tests/quint
quint run bank.qnt --mbt --main=bankTest --seed=42 --n-traces=2 \
  --max-samples=200 --max-steps=8 --out-itf='bank_run_{seq}.itf.json' --verbosity=0
quint run shapes.qnt --mbt --seed=1 --n-traces=1 --max-samples=10 --max-steps=2 \
  --out-itf='shapes_{seq}.itf.json' --verbosity=0
quint run tracked.qnt --mbt --main=trackedTest --seed=7 --n-traces=1 \
  --max-samples=200 --max-steps=6 --out-itf='tracked_run_{seq}.itf.json' --verbosity=0
quint run anonymous.qnt --mbt --seed=3 --n-traces=1 --max-samples=50 --max-steps=4 \
  --out-itf='anonymous_{seq}.itf.json' --verbosity=0
quint run collide.qnt --mbt --seed=1 --n-traces=1 --max-samples=10 --max-steps=2 \
  --out-itf='collide_{seq}.itf.json' --verbosity=0
quint test tracked.qnt --main=trackedRuns --match='^depositThenOverdraftTest$' \
  --out-itf='tracked_test_{test}_{seq}.itf.json' --verbosity=0
quint verify tracked.qnt --main=trackedTest --invariant=underFifty --max-steps=3 \
  --out-itf=tracked_verify_underFifty.itf.json --verbosity=0   # exits 1, writes the counterexample
rm -rf _apalache-out
```

A re-recording that changes a fixture is news about Quint, not noise:
read the diff before committing it.
