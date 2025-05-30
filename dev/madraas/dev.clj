(ns madraas.dev
  (:require
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [confair.config :as config]
   [madraas.matrikkel-ws :as matrikkel-ws]
   [madraas.system :as system]
   [madraas.xml-helpers :as xh])
  (:import
   (java.time Duration)))

(xml/alias-uri 'xsi     "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'adr     "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain/adresse"
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

  (xml/element-nss veger)

  (map (juxt #(-> % (xh/xsi-type matrikkel-ws/uri->ns-alias) matrikkel-ws/id-type->domene-klasse)
             #(xh/get-in-xml % [::dom/item ::dom/value]))
       (xh/get-in-xml veger [::soapenv/Envelope ::soapenv/Body ::ned/findIdsEtterIdResponse ::ned/return]))

  )
