(ns madraas.dev
  (:require
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [confair.config :as config]
   [madraas.geo :as geo]
   [madraas.matrikkel-ws :as matrikkel-ws]
   [madraas.system :as system]
   [madraas.xml-helpers :as xh])
  (:import
   (java.time Duration)))

(xml/alias-uri 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'adr     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
               'kommune "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"
               'geo "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/geometri"
               'endring "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
               'ned     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store   "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

(comment
  (def config
    (-> (system/init-config {:path "./config/dev-defaults.edn"})
        (config/verify-required-together
         #{#{:matrikkel/url
             :matrikkel/username
             :matrikkel/password}})
        (config/mask-config)))

  (def nats-conn
    (system/init-connection config (edn/read-string
                                    {:readers {'time/duration Duration/parse}}
                                    (slurp "./resources/nats.edn"))))

  (def veger
    (:body (matrikkel-ws/be-om-såpe
            config
            "NedlastningServiceWS"
            (matrikkel-ws/find-ids-etter-id-request "Veg" 53))))

  (def ids
    (->> (xh/get-in-xml veger [::soapenv/Envelope ::soapenv/Body ::ned/findIdsEtterIdResponse ::ned/return])
         (map (juxt #(-> (xh/xsi-type % matrikkel-ws/uri->ns-alias)
                         matrikkel-ws/id-type->domene-klasse)
                    #(xh/get-in-xml % [::dom/item ::dom/value])))
         (map (fn [[type id]] {:id id :domene-klasse type}))))

  (def nedlastede-veger
    (:body
     (matrikkel-ws/be-om-såpe
      config
      "StoreServiceWS"
      (matrikkel-ws/get-objects-request
       ids))))

  (->> (xh/get-in-xml nedlastede-veger
                      [::soapenv/Envelope ::soapenv/Body ::store/getObjectsResponse
                       ::store/return ::dom/item])
       (map #(xh/select-tags % [
                                ::adr/adressenavn
                                ::adr/kortAdressenavn
                                ::adr/kommuneId
                                ::dom/id
                                ::dom/versjon
                                ]))
       (map #(-> %
                 (update ::dom/id xh/get-in-xml [::dom/value])
                 (update ::adr/kommuneId xh/get-in-xml [::dom/value])
                 (set/rename-keys {::dom/id :vei/id
                                   ::dom/versjon :versjon/nummer
                                   ::adr/kommuneId :kommune/id
                                   ::adr/adressenavn :vei/navn
                                   ::adr/kortAdressenavn :vei/kort-navn}))))

  (def fylker
    (-> (->> (matrikkel-ws/find-ids-etter-id-request "Fylke" 0)
             (matrikkel-ws/be-om-såpe
              config
              "NedlastningServiceWS")
             :body)
        (xh/get-in-xml [::soapenv/Envelope ::soapenv/Body ::ned/findIdsEtterIdResponse ::ned/return])
        (->> (map (fn [fylke] {:id (xh/get-in-xml fylke [::dom/item ::dom/value])
                               :domene-klasse (-> fylke
                                                  (xh/xsi-type matrikkel-ws/uri->ns-alias)
                                                  matrikkel-ws/id-type->domene-klasse)}))
             matrikkel-ws/get-objects-request
             (matrikkel-ws/be-om-såpe config "StoreServiceWS")
             :body)
        (xh/get-in-xml [::soapenv/Envelope ::soapenv/Body ::store/getObjectsResponse
                        ::store/return ::dom/item])
        (->> (map #(-> (xh/select-tags % [
                                          ::dom/id
                                          ::dom/versjon
                                          ::kommune/fylkesnummer
                                          ::kommune/fylkesnavn
                                          ::kommune/gyldigTilDato
                                          ::kommune/nyFylkeId
                                          ])
                       (update ::dom/id xh/get-in-xml [::dom/value])
                       (update ::kommune/nyFylkeId xh/get-in-xml [::dom/value])
                       (update ::kommune/gyldigTilDato xh/get-in-xml [::dom/date])
                       (set/rename-keys {::dom/id :fylke/id
                                         ::dom/versjon :versjon/nummer
                                         ::kommune/fylkesnummer :fylke/nummer
                                         ::kommune/fylkesnavn :fylke/navn
                                         ::kommune/gyldigTilDato :fylke/gyldig-til
                                         ::kommune/nyFylkeId :fylke/ny-id}))))
             ))

  (def kommuner
    (-> (->> (matrikkel-ws/find-ids-etter-id-request "Kommune" 0)
             (matrikkel-ws/be-om-såpe
              config
              "NedlastningServiceWS")
             :body)
        (xh/get-in-xml [::soapenv/Envelope ::soapenv/Body ::ned/findIdsEtterIdResponse ::ned/return])
        (->> (map (fn [kommune] {:id (xh/get-in-xml kommune [::dom/item ::dom/value])
                                 :domene-klasse (-> kommune
                                                    (xh/xsi-type matrikkel-ws/uri->ns-alias)
                                                    matrikkel-ws/id-type->domene-klasse)}))
             matrikkel-ws/get-objects-request
             (matrikkel-ws/be-om-såpe config "StoreServiceWS")
             :body)
        (xh/get-in-xml [::soapenv/Envelope ::soapenv/Body ::store/getObjectsResponse
                        ::store/return ::dom/item])
        (->> (map #(-> (xh/select-tags % [
                                          ::dom/versjon
                                          ::kommune/fylkeId
                                          ::kommune/kommunenummer
                                          ::kommune/kommunenavn
                                          ::kommune/gyldigTilDato
                                          ::kommune/nyKommuneId
                                          ::kommune/senterpunkt
                                          ])
                       (update ::kommune/fylkeId xh/get-in-xml [::dom/value])
                       (update ::kommune/nyKommuneId xh/get-in-xml [::dom/value])
                       (update ::kommune/gyldigTilDato xh/get-in-xml [::dom/date])
                       (update ::kommune/senterpunkt xh/select-tags [::geo/x ::geo/y ::geo/z])
                       (update ::kommune/senterpunkt set/rename-keys {::geo/x :x
                                                                      ::geo/y :y
                                                                      ::geo/z :z})
                       (set/rename-keys {::dom/versjon :versjon/nummer
                                         ::kommune/kommunenummer :kommune/nummer
                                         ::kommune/fylkeId :kommune/fylke
                                         ::kommune/kommunenavn :kommune/navn
                                         ::kommune/gyldigTilDato :fylke/gyldig-til
                                         ::kommune/nyKommuneId :kommune/ny-id
                                         ::kommune/senterpunkt :kommune/senterpunkt}))))
             ))


  )
