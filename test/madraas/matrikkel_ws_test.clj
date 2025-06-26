(ns madraas.matrikkel-ws-test
  (:require [clojure.data.xml :as xml]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [madraas.matrikkel-ws :as matrikkel-ws]))

(xml/alias-uri 'xsi      "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv  "http://schemas.xmlsoap.org/soap/envelope/"
               'dom      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'adresse  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
               'geometri "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/geometri"
               'kommune  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"
               'endring  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
               'ned      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store    "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

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

(deftest pakk-ut-svar-test
  (is (= (xml/sexps-as-fragment [::dom/item "1"]
                                [::dom/item "2"]
                                [::dom/item "3"])
         (matrikkel-ws/pakk-ut-svar
          ::store/getObjectsResponse
          (xml/sexp-as-element [::soapenv/Envelope
                                [::soapenv/Body
                                 [::store/getObjectsResponse
                                  [::store/return
                                   [::dom/item "1"]
                                   [::dom/item "2"]
                                   [::dom/item "3"]]]]])))))

(deftest pakk-ut-ider-test
  (is (= [{:domene-klasse "Veg" :id "1"}
          {:domene-klasse "Adresse" :id "2"}
          {:domene-klasse "Krets" :id "3"}]
         (matrikkel-ws/pakk-ut-ider
          ::ned/findIdsEtterIdResponse
          (-> (xml/sexp-as-element [::soapenv/Envelope
                                    [::soapenv/Body
                                     [::ned/findIdsEtterIdResponse
                                      [::ned/return
                                       {"xmlns:a" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"}
                                       [::dom/item {::xsi/type "a:VegId"}
                                        [::dom/value "1"]]
                                       [::dom/item {::xsi/type "a:AdresseId"}
                                        [::dom/value "2"]]
                                       [::dom/item {::xsi/type "a:KretsId"}
                                        [::dom/value "3"]]]]]])
              xml/emit-str
              xml/parse-str)))))

(deftest pakk-ut-entitet-test
  (testing "Manglende data legges ikke inn som nil av pakk-ut-entitet"
    (is (= {:navn "HAHA"}
           (matrikkel-ws/pakk-ut-entitet
            (xml/sexp-as-element [::dom/item [::kommune/fylkesnavn "HAHA"]])
            {::kommune/fylkesnavn :navn
             ::kommune/fylkesnummer :nummer}
            {})))))

(deftest pakk-ut-fylke-test
  (is (= {:id 1
          :nummer "01"
          :navn "Huttiheita"
          :gyldigTil "2025-01-01"
          :nyId 2
          :versjonsnummer "42"}
         (matrikkel-ws/pakk-ut-fylke
          (xml/sexp-as-element [::dom/item {"xmlns:k" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"
                                            ::xsi/type "k:Fylke"}
                                [::dom/id {::xsi/type "k:FylkeId"}
                                 [::dom/value "1"]]
                                [::dom/versjon "42"]
                                [::kommune/fylkesnavn "HUTTIHEITA"]
                                [::kommune/fylkesnummer "01"]
                                [::kommune/gyldigTilDato
                                 [::dom/date "2025-01-01"]]
                                [::kommune/nyFylkeId
                                 [::dom/value "2"]]])))))

(deftest pakk-ut-kommune-test
  (is (= {:id 101
          :nummer "0101"
          :navn "Huttiheita"
          :fylke 1
          :gyldigTil "2025-01-01"
          :nyId 102
          :senterpunkt {:opprinneligKoordinatsystem "25832"
          :versjonsnummer "42"}
                        :koordinater {"25832" {:x 1.0 :y 2.0 :z 3.0}}}
         (-> (xml/sexp-as-element [::dom/item {"xmlns:k" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"
                                            ::xsi/type "k:Kommune"}
                                [::dom/id {::xsi/type "k:KommuneId"}
                                 [::dom/value "101"]]
                                [::dom/versjon "42"]
                                [::kommune/kommunenavn "HUTTIHEITA"]
                                [::kommune/kommunenummer "0101"]
                                [::kommune/fylkeId
                                 [::dom/value "1"]]
                                [::kommune/gyldigTilDato
                                 [::dom/date "2025-01-01"]]
                                [::kommune/nyKommuneId
                                 [::dom/value "102"]]
                                [::kommune/representasjonspunkt
                                        [::geometri/koordinatsystemKodeId
                                         [::dom/value "10"]]
                                        [::geometri/position
                                         [::geometri/x "1.0"]
                                         [::geometri/y "2.0"]
                                         [::geometri/z "3.0"]]]])
             matrikkel-ws/pakk-ut-kommune
             (update :senterpunkt select-keys [:opprinneligKoordinatsystem :koordinater])
             (update-in [:senterpunkt :koordinater] select-keys ["25832"])))))

(deftest pakk-ut-vei-test
  (is (= {:id 123456789
          :navn "Stien i lien"
          :kortNavn "Stien"
          :kommune "101"
          :versjonsnummer "42"}
         (matrikkel-ws/pakk-ut-vei
          (xml/sexp-as-element [::dom/item {"xmlns:a" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
                                            ::xsi/type "a:Veg"}
                                [::dom/id {::xsi/type "a:VegId"}
                                 [::dom/value "123456789"]]
                                [::dom/versjon "42"]
                                [::adresse/adressenavn "Stien i lien"]
                                [::adresse/kortAdressenavn "Stien"]
                                [::adresse/kommuneId
                                 [::dom/value "101"]]])))))

(deftest pakk-ut-veiadresse-test
  (let [adresse (matrikkel-ws/pakk-ut-veiadresse
                 (xml/sexp-as-element [::dom/item {"xmlns:a" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
                                                   ::xsi/type "a:Vegadresse"}
                                       [::dom/id {::xsi/type "a:VegadresseId"}
                                        [::dom/value "987654321"]]
                                       [::dom/versjon "42"]
                                       [::adresse/vegId
                                        [::dom/value "123456789"]]
                                       [::adresse/nummer "3"]
                                       [::adresse/bokstav "A"]
                                       [::adresse/representasjonspunkt
                                        [::geometri/koordinatsystemKodeId
                                         [::dom/value "10"]]
                                        [::geometri/position
                                         [::geometri/x "541500.0"]
                                         [::geometri/y "6571000.0"]]]]))]
    (is (= {:id 987654321
            :versjonsnummer "42"
            :nummer "3"
            :bokstav "A"
            :vei "123456789"}
           (select-keys adresse [:id :versjonsnummer :nummer :vei :bokstav])))

    (is (= "25832" (get-in adresse [:posisjon :opprinneligKoordinatsystem])))

    (is (= {:x 541500.0, :y 6571000.0 :z 0.0}
           (get-in adresse [:posisjon :koordinater "25832"])))))

(deftest pakk-ut-postnummerområde
  (testing "Pakk ut postnummerområde"
    (is (= {:kretsId 1234
            :versjonsnummer "42"
            :postnummer "0987"
            :poststed "Oslo"
            :kommuner ["5678" "8765"]}

           (matrikkel-ws/pakk-ut-postnummerområde
            (xml/sexp-as-element [::dom/item {"xmlns:a" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
                                              ::xsi/type "a:Postnummeromrade"}
                                  [::dom/id {::xsi/type "a:PostnummeromradeId"}
                                   [::dom/value "1234"]]
                                  [::dom/versjon "42"]
                                  [::adresse/kretsnummer "0987"]
                                  [::adresse/kretsnavn "OSLO"]
                                  [::adresse/kommuneIds
                                   [::kommune/item
                                    [::dom/value "5678"]]
                                   [::kommune/item
                                    [::dom/value "8765"]]]])))))

  (testing "Postnummerområdes kommuner er alltid en liste"
    (is (= ["5678"]
           (:kommuner
            (matrikkel-ws/pakk-ut-postnummerområde
             (xml/sexp-as-element [::dom/item {"xmlns:a" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
                                               ::xsi/type "a:Postnummeromrade"}
                                   [::dom/id {::xsi/type "a:PostnummeromradeId"}
                                    [::dom/value "1234"]]
                                   [::dom/versjon "42"]
                                   [::adresse/kretsnummer "0987"]
                                   [::adresse/kretsnavn "OSLO"]
                                   [::adresse/kommuneIds
                                    [::kommune/item
                                     [::dom/value "5678"]]]])))))))
