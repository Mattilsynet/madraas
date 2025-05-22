(ns madraas.matrikkel-ws-test
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [madraas.matrikkel-ws :as matrikkel-ws]))

(xml/alias-uri 'example "http://example.com/test"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain")

(deftest matrikkel-context-test
  (testing "Matrikkel context"
    (is (= [::example/matrikkelContext
            [::dom/locale "no_NO_B"]
            [::dom/brukOriginaleKoordinater "false"]
            [::dom/koordinatsystemKodeId
             [::dom/value "11"]]
            [::dom/systemVersion "4.4"]
            [::dom/klientIdentifikasjon "madraas"]]
           (matrikkel-ws/matrikkel-context (get (ns-aliases *ns*) 'example) "25833")))))
