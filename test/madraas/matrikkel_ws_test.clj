(ns madraas.matrikkel-ws-test
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [madraas.matrikkel-ws :as matrikkel-ws]))

(xml/alias-uri 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'ned     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning")

(deftest matrikkel-context-test
  (testing "Matrikkel context"
    (is (= [::ned/matrikkelContext
            [::dom/locale "no_NO_B"]
            [::dom/brukOriginaleKoordinater "true"]
            [::dom/koordinatsystemKodeId
             [::dom/value "10"]]
            [::dom/systemVersion "4.4"]
            [::dom/klientIdentifikasjon "madraas"]]
           (matrikkel-ws/matrikkel-context 'ned)))

    (is (not= (first (matrikkel-ws/matrikkel-context 'dom))
              (first (matrikkel-ws/matrikkel-context 'ned))))

    (is (= (rest (matrikkel-ws/matrikkel-context 'dom))
           (rest (matrikkel-ws/matrikkel-context 'ned))))))

(deftest find-ids-etter-id-request-test
  (is (= [::soapenv/SoapEnvelope
          {"xmlns:adresse"
           "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"}
          [::soapenv/Header]
          [::soapenv/Body
           [::ned/findIdsEtterId
            [::ned/matrikkelBubbleId
             #::xsi{:type "adresse:AdresseId"}
             [::dom/value 1337]]
            [::ned/domainklasse "Adresse"]
            [::ned/filter]
            [::ned/maksAntall 1000]
            (matrikkel-ws/matrikkel-context 'ned)]]]
         (matrikkel-ws/find-ids-etter-id-request "Adresse" 1337))))
