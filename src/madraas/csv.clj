(ns madraas.csv
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str]))

(defn get-k->idx [headers]
  (->> headers
       (map-indexed (fn [i header] [header i]))
       (into {})))

(defn csv->map [csv-line k->idx kmap]
  (->> kmap
       (keep (fn [[source [dest-k dest-f]]]
               (when-let [v (some-> (nth csv-line (k->idx source))
                                    str/trim
                                    not-empty)]
                 [(or dest-k source)
                  (if dest-f
                    (dest-f v)
                    v)])))
       (into {})
       not-empty))

(defn read-csv [input & [opt]]
  (csv/read-csv input opt))

(defn parse-csv [input opt]
  (let [lines (read-csv input opt)
        [headers lines] (if (:headers opt)
                          [(:headers opt) lines]
                          [(first lines) (rest lines)])
        k->idx (get-k->idx headers)
        kmap (or (:kmap opt) (into {} (for [k headers]
                                        [k [k]])))]
    (map #(csv->map % k->idx kmap) lines)))
