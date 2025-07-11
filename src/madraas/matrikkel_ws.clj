(ns madraas.matrikkel-ws
  (:require
   [clj-http.client :as http]
   [clojure.data.xml :as xml]
   [clojure.set :as set]
   [clojure.string :as str]
   [madraas.geo :as geo]
   [madraas.xml-helpers :as xh]))

(xml/alias-uri
 ;; SOAP- og XML-navnerom
 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
 'soapenv "http://schemas.xmlsoap.org/soap/envelope/"

 ;; Domene-navnerom som brukes for typene til Kartverket
 'dom      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
 'adresse  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
 'geometri "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/geometri"
 'kommune  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"

 ;; Tjeneste-navnerom
 'endring "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
 'ned     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
 'store   "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

(def domene-ns
  {"adresse" ["Adresse" "Krets" "Matrikkeladresse" "Postnummeromrade" "Veg" "Vegadresse"]
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

(def id-type->domene-klasse
  (set/map-invert domene-klasse->id-type))

(def uri->ns-alias
  (->> (->domene-klasse-map
        (fn [domene _]
          {(str "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/" domene)
           domene}))
       vals
       (into {})))

(def matrikkel-id->epsg-kode
  {"10" "25832"
   "11" "25833"
   "13" "25835"
   "24" "4258"})

(defn normaliser-stedsnavn [s]
  (->> (for [ord (map str/lower-case (str/split s #" "))]
         (if (#{"og" "i" "på"} ord)
           ord
           (->> (str/split ord #"-")
                (map str/capitalize)
                (str/join "-"))))
       (str/join " ")))

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

(defn find-ids-etter-id-request
  ([domene-klasse id]
   (find-ids-etter-id-request nil domene-klasse id))
  ([config domene-klasse id]
   (soap-envelope
    (domene-klasse->ns-aliases domene-klasse)
    [::ned/findIdsEtterId
     [::ned/matrikkelBubbleId {::xsi/type (domene-klasse->id-type domene-klasse)}
      [::dom/value id]]
     [::ned/domainklasse domene-klasse]
     [::ned/filter]
     [::ned/maksAntall (or (:maks-antall config) 1000)]
     (matrikkel-context 'ned)])))

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

(defn be-om-såpe [config tjeneste forespørsel]
  (let [{:keys [status body]}
        (http/post
         (str (:matrikkel/url config) "/" tjeneste)
         {:basic-auth [(:matrikkel/username config)
                       (:matrikkel/password config)]
          :body (-> forespørsel
                    xml/sexp-as-element
                    xml/aggregate-xmlns
                    xml/emit-str)
          :throw-exceptions false
          :as :stream})]
    {:status status
     :body (xml/parse body)}))

(defn pakk-ut-svar [svar-type soap-svar]
  (xh/get-in-xml soap-svar [::soapenv/Envelope ::soapenv/Body
                            svar-type (keyword (namespace svar-type) "return")]))

(defn pakk-ut-ider [svar-type soap-svar]
  (->> (pakk-ut-svar svar-type soap-svar)
       (map (fn [item]
              {:id (xh/get-in-xml item [::dom/item ::dom/value])
               :domene-klasse (-> item
                                  (xh/xsi-type uri->ns-alias)
                                  id-type->domene-klasse)}))))

(defn pakk-ut-verdi [xml]
  (xh/get-in-xml xml [::dom/value]))

(defn pakk-ut-id [xml]
  (some-> xml pakk-ut-verdi parse-long))

(defn pakk-ut-representasjonspunkt [representasjonspunkt-xml]
  (let [koordinatsystem (-> (xh/get-first representasjonspunkt-xml
                                          [::geometri/koordinatsystemKodeId ::dom/value])
                            matrikkel-id->epsg-kode)
        posisjon (-> representasjonspunkt-xml
                     (xh/get-in-xml [::geometri/position])
                     (xh/select-tags [::geometri/x ::geometri/y ::geometri/z])
                     (set/rename-keys {::geometri/x :x
                                       ::geometri/y :y
                                       ::geometri/z :z})
                     (->> (map (fn [[k v]] [k (parse-double v)]))
                          (into {})))]
    (when koordinatsystem
      {:opprinneligKoordinatsystem koordinatsystem
       :koordinater
       (->> (keys geo/koordinatsystemer)
            (map (fn [til-system]
                   [til-system (geo/konverter-koordinater koordinatsystem til-system posisjon)]))
            (into {}))})))

(defn pakk-ut-entitet [xml tag-name-mappings tag-transforms]
  (let [shaved (-> (xh/get-in-xml xml [::dom/item])
                   (xh/select-tags (keys tag-name-mappings)))
        transformed (reduce (fn [updated [tag update-fn]]
                              (if-let [val (update-fn (get updated tag))]
                                (assoc updated tag val)
                                (dissoc updated tag)))
                            shaved tag-transforms)]
    (set/rename-keys transformed tag-name-mappings)))

(defn pakk-ut-fylke [xml-fylke]
  (pakk-ut-entitet xml-fylke {::dom/id :id
                              ::dom/versjon :versjon
                              ::kommune/fylkesnummer :nummer
                              ::kommune/fylkesnavn :navn
                              ::kommune/gyldigTilDato :gyldigTil
                              ::kommune/nyFylkeId :nyId}
                   {::dom/id pakk-ut-id
                    ::kommune/fylkesnavn normaliser-stedsnavn
                    ::kommune/nyFylkeId pakk-ut-id
                    ::kommune/gyldigTilDato #(xh/get-in-xml % [::dom/date])}))

(defn pakk-ut-kommune [xml-kommune]
  (pakk-ut-entitet xml-kommune {::dom/id :id
                                ::dom/versjon :versjon
                                ::kommune/kommunenummer :nummer
                                ::kommune/fylkeId :fylke
                                ::kommune/kommunenavn :navn
                                ::kommune/gyldigTilDato :gyldigTil
                                ::kommune/nyKommuneId :nyId
                                ::kommune/representasjonspunkt :senterpunkt}
                   {::dom/id pakk-ut-id
                    ::kommune/kommunenavn normaliser-stedsnavn
                    ::kommune/fylkeId pakk-ut-id
                    ::kommune/nyKommuneId pakk-ut-id
                    ::kommune/gyldigTilDato #(xh/get-in-xml % [::dom/date])
                    ::kommune/representasjonspunkt pakk-ut-representasjonspunkt}))

(defn pakk-ut-postnummerområde [xml-postnummerområde]
  (pakk-ut-entitet xml-postnummerområde {::dom/id :id
                                         ::dom/versjon :versjon
                                         ::adresse/kretsnummer :postnummer
                                         ::adresse/kretsnavn :poststed
                                         ::adresse/kommuneIds :kommuner}
                   {::dom/id pakk-ut-id
                    ::adresse/kretsnummer #(format "%04d" (parse-long %))
                    ::adresse/kretsnavn normaliser-stedsnavn
                    ::adresse/kommuneIds #(let [kommuner (xh/get-in-xml % [::kommune/item ::dom/value])]
                                            (cond-> kommuner (string? kommuner) vector))}))

(defn pakk-ut-vei [xml-vei]
  (pakk-ut-entitet xml-vei {::dom/id :id
                            ::dom/versjon :versjon
                            ::adresse/kommuneId :kommune
                            ::adresse/adressenavn :navn}
                   {::dom/id pakk-ut-id
                    ::adresse/kommuneId #(format "%04d" (parse-long (xh/get-in-xml % [::dom/value])))}))

(defn pakk-ut-veiadresse [xml-veiadresse]
  (pakk-ut-entitet xml-veiadresse
                   {::dom/id :id
                    ::dom/versjon :versjon
                    ::adresse/nummer :nummer
                    ::adresse/bokstav :bokstav
                    ::adresse/vegId :vei
                    ::adresse/representasjonspunkt :posisjon
                    ::adresse/kretsIds :kretser}
                   {::dom/id pakk-ut-id
                    ::adresse/vegId pakk-ut-id
                    ::adresse/representasjonspunkt pakk-ut-representasjonspunkt
                    ::adresse/kretsIds #(some->>
                                         (xh/get-in-xml % [::adresse/item ::dom/value])
                                         (map parse-long))}))

(defn last-ned [config domene-klasse fra-id]
  (->> (find-ids-etter-id-request config domene-klasse fra-id)
       (be-om-såpe config "NedlastningServiceWS")
       :body
       (pakk-ut-ider ::ned/findIdsEtterIdResponse)
       get-objects-request
       (be-om-såpe config "StoreServiceWS")
       :body
       (pakk-ut-svar ::store/getObjectsResponse)))

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

  (id-type->domene-klasse
   (xh/xsi-type (-> [::dom/item {"xmlns:ns1" "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
                                 ::xsi/type "ns1:VegId"}]
                    xml/sexp-as-element
                    xml/emit-str
                    xml/parse-str)
                uri->ns-alias))
)
