(ns madraas.matrikkel-ws-test
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [madraas.matrikkel-ws :as matrikkel-ws]))

(xml/alias-uri 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'endring "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
               'ned     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store   "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

(deftest matrikkel-context-test
  (testing "Matrikkel context"
    (is (= [::ned/matrikkelContext
            [::dom/locale "no_NO_B"]
            [::dom/brukOriginaleKoordinater "true"]
            [::dom/koordinatsystemKodeId
             [::dom/value "10"]]
            [::dom/systemVersion "4.4"]
            [::dom/klientIdentifikasjon "madraas"]]
           (matrikkel-ws/matrikkel-context 'ned))))

  (testing "Matrikkel context med forskjellig domene har forskjell kun i rot-elementet"
    (let [dom-matrikkel-context (matrikkel-ws/matrikkel-context 'dom)
          ned-matrikkel-context (matrikkel-ws/matrikkel-context 'ned)]
      (is (not= (first dom-matrikkel-context)
                (first ned-matrikkel-context)))

      (is (= (rest dom-matrikkel-context)
             (rest ned-matrikkel-context))))))

(deftest find-ids-etter-id-request-test
  (is (= [::soapenv/Envelope
          {"xmlns:adresse"
           "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"}
          [::soapenv/Header]
          [::soapenv/Body
           [::ned/findIdsEtterId
            [::ned/matrikkelBubbleId {::xsi/type "adresse:AdresseId"}
             [::dom/value 1337]]
            [::ned/domainklasse "Adresse"]
            [::ned/filter]
            [::ned/maksAntall 1000]
            (matrikkel-ws/matrikkel-context 'ned)]]]
         (matrikkel-ws/find-ids-etter-id-request "Adresse" 1337))))

(deftest get-objects-request-test
  (is (= [::soapenv/Envelope
          {"xmlns:adresse"
           "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
           "xmlns:kommune"
           "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"}
          [::soapenv/Header]
          [::soapenv/Body
           [::store/getObjects
            [::store/ids
             [::dom/item {::xsi/type "adresse:AdresseId"}
              [::dom/value 1337]]
             [::dom/item {::xsi/type "kommune:KommuneId"}
              [::dom/value 301]]]
            (matrikkel-ws/matrikkel-context 'store)]]]
         (matrikkel-ws/get-objects-request [{:domene-klasse "Adresse" :id 1337}
                                            {:domene-klasse "Kommune" :id 301}]))))

(deftest find-endringer-request-test
  (is (= [::soapenv/Envelope
          {"xmlns:adresse"
           "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"}
          [::soapenv/Header]
          [::soapenv/Body
           [::endring/findEndringer
            [::endring/id
             [::dom/value 1138]]
            [::endring/domainKlasse "Veg"]
            [::endring/filter]
            [::endring/returnerBobler "Aldri"]
            [::endring/maksAntall 10000]
            (matrikkel-ws/matrikkel-context 'endring)]]]
         (matrikkel-ws/find-endringer-request "Veg" 1138))))
