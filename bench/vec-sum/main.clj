(defn build [acc n]
  (if (zero? n) acc (recur (conj acc n) (dec n))))

(println (reduce + 0 (build [] 500)))
