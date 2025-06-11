(ns madraas.system
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [confair.config :as config]
   [madraas.matrikkel-ws :as matrikkel-ws]
   [nats.core :as nats]
   [nats.kv :as kv]
   [nats.stream :as stream]
   [open-telemetry.core :as otel]
   [open-telemetry.tracing :as tracing])
  (:import
   (java.time Duration)))

(def api-er
  {"Vegadresse" matrikkel-ws/pakk-ut-vei-adresse
   "Fylke" matrikkel-ws/pakk-ut-fylke
   "Kommune" matrikkel-ws/pakk-ut-kommune
   "Veg" matrikkel-ws/pakk-ut-vei
   "Postnummeromrade" matrikkel-ws/pakk-ut-postnummerområde})

(defn select-keys-by-ns [m ns]
  (->> (keys m)
       (filter (comp #{ns} namespace))
       (select-keys m)))

(defn init-connection [{:nats/keys [stream-overrides] :as config} resources]
  (let [conn (nats/connect config)
        existing (stream/get-stream-names conn)]
    (doseq [nats-resource resources]
      (try
        (cond
          (:nats.stream/name nats-resource)
          (when-not (existing (:nats.stream/name nats-resource))
            (tracing/with-span ["create-stream" (otel/flatten-data nats-resource {:prefix "_"})]
              (stream/create-stream conn
                (merge nats-resource (select-keys-by-ns stream-overrides "nats.stream")))))

          (:nats.kv/bucket-name nats-resource)
          (tracing/with-span ["upsert-kv-bucket" (otel/flatten-data nats-resource {:prefix "_"})]
            (kv/create-bucket conn
              (merge nats-resource (select-keys-by-ns stream-overrides "nats.kv"))))

          :else
          (throw (ex-info "Unknown NATS resource" nats-resource)))
        (catch Exception e
          (throw (ex-info "Unable to create NATS resource"
                          {:resource nats-resource
                           :nats-server (:nats.core/server-url config)}
                          e)))))
    conn))

(defn init-config [{:keys [path env-var overrides extra-config]}]
  (let [config (cond
                 (and path (.exists (io/file path)))
                 (config/from-file path overrides)

                 (and env-var (System/getenv env-var))
                 (config/from-base64-env env-var overrides)

                 :else
                 (throw (ex-info "No config found"
                                 {:path path :env-var env-var})))]
    (-> config
        (cond->
          (string? (:nats.stream/request-timeout (:nats/jet-stream-options config)))
          (update-in [:nats/jet-stream-options :nats.stream/request-timeout] Duration/parse))
        (into extra-config))))

(defn last-ned [config type start-id]
  (let [ch (a/chan 2000)
        running? (atom true)]
    (a/go
      (loop [start start-id]
        (tap> (str "Laster ned fra " start))
        (let [entiteter (->> (matrikkel-ws/last-ned config type start)
                             (map (get api-er type)))]
          (a/onto-chan!! ch entiteter false)
          (if (and @running? (seq entiteter))
            (recur (apply max (map :id entiteter)))
            (a/close! ch)))))
    {:chan ch
     :stop #(reset! running? false)}))
