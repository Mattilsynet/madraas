(ns madraas.matrikkel-ws
  (:require
   [clojure.data.xml :as xml]
   [clojure.string :as str]))

(xml/alias-uri 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'endring "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
               'ned     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store   "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

(def domene-ns
  {"adresse" ["Adresse" "Krets" "Veg"]
   "kommune" ["Kommune" "Fylke"]})

;; Forferdelig navn på denne funksjonen. Gjør vondt i sjela.
(defn ->domene-klasse-map
  "Tar en transformeringsfunksjon, xf, med to parametere: domene og domene-klasse.
   Blir en map med domene-klassene som nøkler, og resultatet av transformeringene som verdi."
  [xf]
  (->> domene-ns
       (mapcat (fn [[domene klasser]]
                 (map (fn [klasse]
                        [klasse (xf domene klasse)])
                      klasser)))
       (into {})))

(def domene-klasse->ns-aliases
  (->domene-klasse-map
   (fn [domene _]
     {(str "xmlns:" domene)
      (str "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/" domene)})))

(def domene-klasse->id-type
  (->domene-klasse-map
   (fn [domene klasse]
     (str domene ":" klasse "Id"))))

(defn xml-ns [alias]
  (str (get (ns-aliases 'madraas.matrikkel-ws) (symbol alias) alias)))

(defn soap-envelope
  "attrs må inneholde alle navnerom som blir brukt i en xsi:type på vanlig xml-form:
   {\"xmlns:my-space\" \"http://myspace.com/\"}

   For at flytting av XMLNS-prefiksene til rotnivå skal fungere,
   må nøkkelen angis som en streng, ikke som en en keyword."
  [attrs request]
  [::soapenv/Envelope
   attrs
   [::soapenv/Header]
   [::soapenv/Body request]])

(defn matrikkel-context [service-ns-alias]
  [(keyword (xml-ns service-ns-alias) "matrikkelContext")
   [::dom/locale "no_NO_B"]
   [::dom/brukOriginaleKoordinater "true"]
   [::dom/koordinatsystemKodeId
    [::dom/value "10"]]
   [::dom/systemVersion "4.4"]
   [::dom/klientIdentifikasjon "madraas"]])

(defn find-ids-etter-id-request [domene-klasse id]
  (soap-envelope
   (domene-klasse->ns-aliases domene-klasse)
   [::ned/findIdsEtterId
    [::ned/matrikkelBubbleId {::xsi/type (domene-klasse->id-type domene-klasse)}
     [::dom/value id]]
    [::ned/domainklasse domene-klasse]
    [::ned/filter]
    [::ned/maksAntall 1000]
    (matrikkel-context 'ned)]))

(defn get-objects-request [ids]
  (soap-envelope
   ;; Kan eventuelt hente ut bare de aliasene vi trenger, men det er ikke så mange uansett
   (into {} (vals domene-klasse->ns-aliases))
   [::store/getObjects
    (into [::store/ids]
          (for [{:keys [id domene-klasse]} ids]
            [::dom/item {::xsi/type (domene-klasse->id-type domene-klasse)}
             [::dom/value id]]))
    (matrikkel-context 'store)]))

(defn find-endringer-request [domene-klasse fra-id]
  (soap-envelope
   (domene-klasse->ns-aliases domene-klasse)
   [::endring/findEndringer
    [::endring/id
     [::dom/value (or fra-id "0")]]
    [::endring/domainKlasse domene-klasse]
    [::endring/filter]
    [::endring/returnerBobler "Aldri"]
    [::endring/maksAntall 10000]
    (matrikkel-context 'endring)]))

(comment
  (xml/emit-str (xml/aggregate-xmlns (xml/sexp-as-element (find-ids-etter-id-request 15 "Adresse"))))
  (str/split (xml/indent-str
              (xml/aggregate-xmlns
               (xml/sexp-as-element (get-objects-request [{:id 15 :domene-klasse "Adresse"}
                                                          {:id 16 :domene-klasse "Krets"}]))))
             #"\R")
  (str/split (xml/indent-str
              (xml/aggregate-xmlns
               (xml/sexp-as-element [::dom/A
                                     [::ned/B]
                                     [::ned/C]])))
             #"\R")
)
