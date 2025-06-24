(ns madraas.postnummer
  (:require [clj-http.client :as http]
            [madraas.csv :as csv]))

(def headers
  ["Postnummer"
   "Poststed"
   "Kommunenummer"
   "Kommune"
   "Bruksområde"])

(def bokstav->bruksområde
  {"B" #{:gateadresser
         :postbokser}
   "F" #{:gateadresser
         :postbokser
         :service}
   "G" #{:gateadresser}
   "P" #{:postbokser}
   "S" #{:service}})

(def header->k
  {"Postnummer" [:postnummer]
   "Bruksområde" [:bruksområder bokstav->bruksområde]})

(defn parse-postnummer [input]
  (csv/parse-csv input {:separator \tab :headers headers :kmap header->k}))

(defn last-ned-postnummere [config]
  (->> (http/get (:posten/postnummer-url config))
       :body
       parse-postnummer
       (map (juxt :nummer :bruksområder))
       (into {})))
