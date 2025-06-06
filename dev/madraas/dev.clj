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
    (->> (matrikkel-ws/last-ned config "Veg" 6300000000)
         (map matrikkel-ws/pakk-ut-vei)))

  (def vei-adresser
    (->> (matrikkel-ws/last-ned config "Vegadresse" 0)
         (map matrikkel-ws/pakk-ut-vei-adresse)))

  (def fylker
    (->> (matrikkel-ws/last-ned config "Fylke" 0)
         (map matrikkel-ws/pakk-ut-fylke)))

  (def kommuner
    (->> (matrikkel-ws/last-ned config "Kommune" 0)
         (map matrikkel-ws/pakk-ut-kommune)))

  )
