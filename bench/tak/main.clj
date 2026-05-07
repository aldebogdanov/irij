(defn tak [x y z]
  (if (>= y x) z
      (tak (tak (- x 1) y z)
           (tak (- y 1) z x)
           (tak (- z 1) x y))))

(println (tak 22 16 8))
