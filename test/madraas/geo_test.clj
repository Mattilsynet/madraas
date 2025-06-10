(ns madraas.geo-test
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing]]
            [madraas.geo :as geo]))

(def feilmargin-meter 0.5) ;; 50 cm

(defn kartesisk-avstand [a b]
  (let [dx (- (:x a) (:x b))
        dy (- (:y a) (:y b))
        dz (- (:z a) (:z b))]
    (math/sqrt (+ (* dx dx)
                  (* dy dy)
                  (* dz dz)))))

(defn tilnærmet-avstand-grader [a b]
  (let [xa (math/to-radians (:x a))
        xb (math/to-radians (:x b))
        ya (math/to-radians (:y a))
        yb (math/to-radians (:y b))
        dx (- xa xb)
        midt-y-smørøyet (/ (+ ya yb) 2)
        x (* dx (math/cos midt-y-smørøyet))
        y (- ya yb)]
    (* (math/sqrt (+ (* x x) (* y y)))
       ;; Tilnærmet jordens radius i meter
       6371000)))

(deftest konverter-koordinater-test
  (testing "Konvertering av koordinater matcher data hentet fra Matrikkelen"
    (is (> feilmargin-meter
           (kartesisk-avstand
            (geo/konverter-koordinater "25832" "25833"
                                       {:x 527874.0 :y 6801725.0 :z 0.0})
            {:x 207162.88400456856 :y 6813914.332992757 :z 0.0})))

    (is (> feilmargin-meter
           (kartesisk-avstand
            (geo/konverter-koordinater "25832" "25835"
                                       {:x 527874.0 :y 6801725.0 :z 0.0})
            {:x -426998.2757099052 :y 6927117.817062382 :z 0.0})))

    (is (> feilmargin-meter
           (tilnærmet-avstand-grader
            (geo/konverter-koordinater "25832" "4258"
                                       {:x 527874.0 :y 6801725.0 :z 0.0})
            {:x 9.521089638249814 :y 61.34857089221428 :z 0.0})))

    (is (> feilmargin-meter
           (tilnærmet-avstand-grader
            (geo/konverter-koordinater "25832" "4326"
                                       {:x 527874.0 :y 6801725.0 :z 0.0})
            ;; Dette er ETRS89 lengdegrad- og breddegrad-koordinater fra Matrikkelen
            ;; Men jeg ser ingen forskjell i hva vi får ved ETRS89- og WGS84-konvertering
            {:x 9.521089638249814 :y 61.34857089221428 :z 0.0})))))
