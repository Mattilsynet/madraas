(ns madraas.system
  (:require
   [charred.api :as charred]
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

(def fylke->subject :nummer)

(defn kommune->subject [{:keys [nummer]}]
  (str (subs nummer 0 2) "." (subs nummer 2)))

(def postnummerområde->subject :postnummer)

(defn vei->subject [{:keys [kommune id]}]
  (str (kommune->subject {:nummer kommune}) "." id))

(defn veiadresse->subject [{:keys [kommune vei id]}]
  (str (kommune->subject {:nummer kommune}) "." vei "." id))

(def api-er
  {"Vegadresse" {:xf matrikkel-ws/pakk-ut-veiadresse
                 :bucket "adresser"
                 :subject-fn veiadresse->subject}
   "Fylke" {:xf matrikkel-ws/pakk-ut-fylke
            :bucket "fylker"
            :subject-fn fylke->subject
            :ignore (comp #{"99"} :nummer)}
   "Kommune" {:xf matrikkel-ws/pakk-ut-kommune
              :bucket "kommuner"
              :subject-fn kommune->subject
              :ignore (comp #{"9999"} :nummer)}
   "Veg" {:xf matrikkel-ws/pakk-ut-vei
          :bucket "veier"
          :subject-fn vei->subject}
   "Postnummeromrade" {:xf matrikkel-ws/pakk-ut-postnummerområde
                       :id-fn :kretsId
                       :bucket "postnummere"
                       :subject-fn postnummerområde->subject}})

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

(defn last-ned
  ([config type start-id] (last-ned config type start-id (get-in api-er [type :id-fn])))
  ([config type start-id id-fn]
   (let [ch (a/chan 2000)
         running? (atom true)]
     (a/go
       (loop [start start-id]
         (tap> (str "Laster ned fra " start))
         (let [ignore (get-in api-er [type :ignore])
               entiteter (cond->> (matrikkel-ws/last-ned config type start)
                           :then (map (get-in api-er [type :xf]))
                           ignore (remove ignore))]
           (a/onto-chan!! ch entiteter false)
           (if (and @running? (seq entiteter))
             (recur (apply max (map id-fn entiteter)))
             (a/close! ch)))))
     {:chan ch
      :stop #(reset! running? false)})))

(defn synkroniser-til-nats [nats-conn ch bucket subject-fn]
  (a/go-loop []
    (when-let [msg (a/<! ch)]
      (kv/put nats-conn bucket (subject-fn msg) (charred/write-json-str msg))
      (recur))))

(defn last-ned-og-synkroniser [config nats-conn type & [xf]]
  (let [{:keys [bucket subject-fn]} (get api-er type)
        ch (:chan (last-ned config type 0))
        ch (if xf
             (a/map xf [ch])
             ch)]
    (synkroniser-til-nats nats-conn ch bucket subject-fn)))

(comment



  )
