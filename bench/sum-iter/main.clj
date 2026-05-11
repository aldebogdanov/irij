(defn sum-to [acc n]
  (if (zero? n) acc (recur (+ acc n) (dec n))))

(println (sum-to 0 1000))
