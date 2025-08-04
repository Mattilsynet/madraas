(ns madraas.dev
  (:require
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [confair.config :as config]
   [madraas.geo :as geo]
   [madraas.matrikkel-ws :as matrikkel-ws]
   [madraas.system :as system]
   [madraas.xml-helpers :as xh]
   [nats.kv :as kv])
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

(defn estimer-tidsbruk [jobb forventet-totalantall]
  (let [startet (:startet jobb)
        nå (java.time.Instant/now)
        synkronisert-til-nats (:synkronisert-til-nats jobb)
        forventet-ferdig (->> (java.time.Duration/between startet nå)
                              .toSeconds
                              (quot synkronisert-til-nats)
                              (quot forventet-totalantall)
                              (.plusSeconds startet))
        tid-igjen (java.time.Duration/between nå forventet-ferdig)]
    {:startet startet
     :forventet-ferdig forventet-ferdig
     :tid-igjen (format "%02d:%02d:%02d"
                        (.toHoursPart tid-igjen)
                        (.toMinutesPart tid-igjen)
                        (.toSecondsPart tid-igjen))
     :synkronisert-til-nats synkronisert-til-nats}))

(comment

  (set! *warn-on-reflection* true)

  (let [ch (:chan (system/last-ned config "Fylke" 0))]
    (synkroniser-til-nats nats-conn ch "fylker" :nummer))

  (let [ch (:chan (system/last-ned config "Kommune" 0))]
    (synkroniser-til-nats nats-conn ch "kommuner" kommune->subject))

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

  (def postnummere
    (->> (matrikkel-ws/last-ned config "Postnummeromrade" 0)
         (map matrikkel-ws/pakk-ut-postnummerområde)))

  (def fylke-jobb (system/last-ned-og-synkroniser config nats-conn "Fylke"))

  (dissoc @fylke-jobb :data)
  (def synk (system/synkroniser config nats-conn))

  @(:veiadresse-jobb synk)

  (estimer-tidsbruk @(:veiadresse-jobb synk) 2500000)

  (watch nats-conn "kommuner" ">" 0 {:watch println} :nats.kv.watch-option/ignore-delete)

  )
