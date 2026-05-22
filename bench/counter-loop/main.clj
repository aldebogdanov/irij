;; Cross-language counter-bump — see bench/README.md and bench/counter-loop/main.irj
;; for the rationale. 50 trivial atom swaps, no contention.

(def state (atom 0))
(dotimes [_ 50] (swap! state inc))
(println @state)
