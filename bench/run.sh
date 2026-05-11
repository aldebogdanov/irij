#!/usr/bin/env bash
# Irij benchmark harness.
#
# Runs each bench (subdirs of bench/ with main.{irj,clj,py}) across:
#   irij-interp             — `irij build --mode=interp`
#   irij-bytecode-threaded  — `irij build --mode=bytecode-threaded` (14c.2)
#   irij-bytecode-sm        — `irij build --mode=bytecode-sm`       (14c.3)
#   clojure                 — `clojure -M main.clj`    (skipped if not on PATH)
#   python                  — `python3 main.py`        (skipped if not on PATH)
#
# Wall-clock timing, N iterations, reports median (and min) in milliseconds.
#
# Usage:
#   bench/run.sh                    # all benches, default iters=5
#   bench/run.sh -n 10 fib          # 10 iters, only fib bench
#   bench/run.sh --iters=3 fib fact

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CACHE="$ROOT/.cache"
mkdir -p "$CACHE"

ITERS=5
SELECTED=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--iters) ITERS="$2"; shift 2 ;;
        --iters=*)  ITERS="${1#*=}"; shift ;;
        -h|--help)
            sed -n '2,17p' "$0" | sed 's/^# \?//'
            exit 0 ;;
        *) SELECTED+=("$1"); shift ;;
    esac
done

# Discover benches
BENCHES=()
if [[ ${#SELECTED[@]} -gt 0 ]]; then
    BENCHES=("${SELECTED[@]}")
else
    for d in "$ROOT"/*/; do
        name="$(basename "$d")"
        [[ "$name" == ".cache" ]] && continue
        BENCHES+=("$name")
    done
fi

# Runtime detection
HAVE_IRIJ=0;    command -v irij       >/dev/null 2>&1 && HAVE_IRIJ=1
HAVE_JAVA=0;    command -v java       >/dev/null 2>&1 && HAVE_JAVA=1
HAVE_CLOJURE=0; command -v clojure    >/dev/null 2>&1 && HAVE_CLOJURE=1
HAVE_BB=0;      command -v bb         >/dev/null 2>&1 && HAVE_BB=1
HAVE_PYTHON=0;  command -v python3    >/dev/null 2>&1 && HAVE_PYTHON=1

if [[ $HAVE_IRIJ -eq 0 || $HAVE_JAVA -eq 0 ]]; then
    echo "error: irij + java required on PATH" >&2
    exit 1
fi

# Wall-clock in ms via python3 (portable across macOS + Linux).
# Returns "" on command failure.
time_ms() {
    local start end status
    start=$(python3 -c 'import time; print(time.perf_counter())')
    if ! "$@" >/dev/null 2>&1; then
        echo ""; return
    fi
    end=$(python3 -c 'import time; print(time.perf_counter())')
    python3 -c "print(round(($end - $start) * 1000, 1))"
}

# Stats: median + min from space-separated ms values
stats() {
    python3 -c "
import sys
xs = sorted(float(x) for x in sys.argv[1:])
n = len(xs)
med = xs[n//2] if n % 2 else (xs[n//2-1] + xs[n//2]) / 2
print(f'{med:8.1f}  {min(xs):8.1f}')
" "$@"
}

run_variant() {
    local label="$1"; shift
    local times=()
    local t
    for ((i = 0; i < ITERS; i++)); do
        t="$(time_ms "$@")"
        if [[ -z "$t" ]]; then
            printf '  %-28s  FAIL\n' "$label"
            return
        fi
        times+=("$t")
    done
    printf '  %-28s  %s ms (min %s ms)\n' "$label" $(stats "${times[@]}")
}

build_irij_jar() {
    local bench="$1" mode="$2"
    local jar="$CACHE/$bench-$mode.jar"
    if [[ ! -f "$jar" || "$ROOT/$bench/main.irj" -nt "$jar" ]]; then
        ( cd "$ROOT/$bench" \
            && irij build --mode="$mode" main.irj -o "$jar" >/dev/null )
    fi
    echo "$jar"
}

printf '\nIrij benchmark (iters=%s, lower is better)\n\n' "$ITERS"

for bench in "${BENCHES[@]}"; do
    if [[ ! -d "$ROOT/$bench" ]]; then
        echo "skip: $bench (not a directory)"; continue
    fi
    echo "── $bench ───────────────────────────────────────"

    if [[ -f "$ROOT/$bench/main.irj" ]]; then
        for mode in interp bytecode-threaded bytecode-sm; do
            jar="$(build_irij_jar "$bench" "$mode")"
            run_variant "irij-$mode" \
                java --enable-native-access=ALL-UNNAMED -jar "$jar"
        done
    fi
    if [[ -f "$ROOT/$bench/main.clj" && $HAVE_CLOJURE -eq 1 ]]; then
        run_variant "clojure" clojure -M "$ROOT/$bench/main.clj"
    fi
    if [[ -f "$ROOT/$bench/main.clj" && $HAVE_BB -eq 1 ]]; then
        run_variant "babashka" bb "$ROOT/$bench/main.clj"
    fi
    if [[ -f "$ROOT/$bench/main.py" && $HAVE_PYTHON -eq 1 ]]; then
        run_variant "python3" python3 "$ROOT/$bench/main.py"
    fi
    echo
done
