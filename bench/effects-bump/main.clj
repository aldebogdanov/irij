;; Equivalent of the Irij effects-bump bench: 50 in-line state mutations
;; against an atom. No vthread / handler dispatch, just a fair-ish
;; comparison of "do 50 stateful operations in a row".

(def state (atom 0))
(dotimes [_ 50] (swap! state inc))
(println @state)
