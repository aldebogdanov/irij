(defn build [acc n]
  (if (zero? n) acc (recur (str acc "x") (dec n))))

(println (build "" 500))
