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

(xml/alias-uri 'xsi      "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv  "http://schemas.xmlsoap.org/soap/envelope/"
               'dom      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'adr      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
               'kommune  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/kommune"
               'geometri "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/geometri"
               'endring  "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/endringslogg"
               'ned      "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store    "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store")

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
    (->> (matrikkel-ws/last-ned config "Fylke" 0)
         (map matrikkel-ws/pakk-ut-fylke)))

  (def kommuner
    (->> (matrikkel-ws/last-ned config "Kommune" 0)
         (map matrikkel-ws/pakk-ut-kommune)))


  )
