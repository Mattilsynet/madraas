(ns madraas.system
  (:require
   [charred.api :as charred]
   [clojure.core.async :as a]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [confair.config :as config]
   [madraas.matrikkel-ws :as matrikkel-ws]
   [madraas.nats :as m-nats]
   [madraas.postnummer :as postnummer]
   [nats.core :as nats]
   [nats.kv :as kv]
   [nats.stream :as stream]
   [open-telemetry.core :as otel]
   [open-telemetry.tracing :as tracing])
  (:import
   (java.time Duration)))

(def jobber
  {:fylke-jobb "fylker"
   :kommune-jobb "kommuner"
   :postnummer-jobb "postnummere"
   :vei-jobb "veier"
   :veiadresse-jobb "veiadresser"
   :fylke-endringer "fylkesendringer"
   :kommune-endringer "kommuneendringer"
   :postnummer-endringer "postnummerendringer"
   :vei-endringer "veiendringer"
   :veiadresse-endringer "veiadresseendringer"})

(def fylke->subject :nummer)

(defn kommune->subject [{:keys [nummer]}]
  (str (subs nummer 0 2) "." (subs nummer 2)))

(def postnummerområde->subject :postnummer)

(defn vei->subject [{:keys [kommune id]}]
  (str (kommune->subject {:nummer kommune}) "." id))

(defn veiadresse->subject [{:keys [kommune postnummer vei id]}]
  (str (kommune->subject {:nummer kommune}) "." postnummer "." vei "." id))

(def api-er
  {"Vegadresse" {:xf matrikkel-ws/pakk-ut-veiadresse
                 :bucket "adresser"
                 :subject-fn veiadresse->subject
                 :search-prefix "*.*.*.*."}
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
          :subject-fn vei->subject
          :search-prefix "*.*."}
   "Postnummeromrade" {:xf matrikkel-ws/pakk-ut-postnummerområde
                       :endringstype "Krets"
                       :bucket "postnummere"
                       :subject-fn postnummerområde->subject
                       :ignore #(not= "adresse:Postnummeromrade" (:xsi-type %))}})

(defn select-keys-by-ns [m ns]
  (->> (keys m)
       (filter (comp #{ns} namespace))
       (select-keys m)))

(defn avsluttet? [proc]
  (seq (select-keys proc [:feil :synkronisering-ferdig :nedlasting-avbrutt])))

(defn init-connection [{:nats/keys [stream-overrides] :as config} resources]
  (let [conn (nats/connect config {:jet-stream-options (:nats/jet-stream-options config)})
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
    (cond-> (into config extra-config)
      (string? (:nats.stream/request-timeout (:nats/jet-stream-options config)))
      (update-in [:nats/jet-stream-options :nats.stream/request-timeout] Duration/parse))))

(defn drain! [ch]
  (a/close! ch)
  (a/<!! (a/go-loop []
           (when (a/<! ch)
             (recur)))))

(defn last-ned
  ([config type start-id] (last-ned (atom {:startet (java.time.Instant/now)
                                           :lastet-ned 0})
                                    config type start-id))
  ([prosess config type start-id]
   (tap> ["Laster ned" type])
   (let [ch (a/chan 2000)
         running? (atom true)]
     (a/go
       (try
         (loop [start start-id]
           (swap! prosess assoc :start-id start)
           (let [ignore (get-in api-er [type :ignore])
                 entiteter (cond->> (->> (matrikkel-ws/last-ned config type start)
                                         (map (get-in api-er [type :xf]))
                                         (sort-by :id))
                             ignore (remove ignore))]
             (swap! prosess update :lastet-ned + (count entiteter))

             (doseq [e entiteter] (a/>! ch e))

             (if (and @running? (seq entiteter))
               (recur (:id (last entiteter)))
               (do
                 (when @running?
                   (swap! prosess assoc :nedlasting-ferdig (java.time.Instant/now)))
                 (a/close! ch)))))
         (catch Exception e
           ((:stop @prosess))
           (tap> ["Feil ved nedlasting av" type ":" e])
           (swap! prosess assoc :feil e))))
     {:chan ch
      :stop #(do
               (when @running?
                 (swap! prosess assoc :nedlasting-avbrutt (java.time.Instant/now)))
               (reset! running? false)
               (drain! ch))})))

(defn siste-synkroniserte-subject [type]
  (-> type str/lower-case (str ".siste-synkroniserte")))

(defn siste-synkroniserte-id [nats-conn type]
  (or (kv/get-value nats-conn "madraas" (siste-synkroniserte-subject type) nil)
      0))

(defn synkroniser-til-nats [prosess nats-conn ch type]
  (swap! prosess assoc :synkronisert-til-nats 0)
  (a/go
    (let [last-msg (atom nil)
          siste-synkroniserte-subject (siste-synkroniserte-subject type)
          {:keys [bucket subject-fn]} (api-er type)]
      (try
        (loop []
          (if-let [msg (a/<! ch)]
            (do
              (reset! last-msg msg)
              (kv/put nats-conn bucket (subject-fn msg) (charred/write-json-str msg))
              (kv/put nats-conn "madraas" siste-synkroniserte-subject (:id msg))
              (swap! prosess update :synkronisert-til-nats inc)
              (recur))
            (swap! prosess assoc :synkronisering-ferdig (java.time.Instant/now))))
        (catch Exception e
          (tap> ["Klarte ikke å skrive" (:id @last-msg) "av" type "til NATS" bucket ":" e])
          ((:stop @prosess))
          (swap! prosess assoc
                 :synkronisering-avbrutt (java.time.Instant/now)
                 :feil e))))))

(defn ^{:indent 3} last-ned-og-synkroniser
  ([config nats-conn type]
   (last-ned-og-synkroniser config nats-conn type nil))
  ([config nats-conn type {:keys [xf synkron?]}]
   (let [bucket (get-in api-er [type :bucket])
         prosess (atom {:startet (java.time.Instant/now)
                        :lastet-ned 0
                        :data (if synkron?
                                (m-nats/read-all nats-conn bucket)
                                [])})
         start-id (siste-synkroniserte-id nats-conn type)
         {:keys [chan stop]} (last-ned prosess config type start-id)
         ch (a/map (fn [verdi]
                     (try
                       (let [verdi (cond-> verdi
                                     xf xf)]
                         (when synkron?
                           (swap! prosess update :data conj verdi))
                         verdi)
                       (catch Exception e
                         (tap> ["Klarte ikke å mappe" type (:id verdi) ":" e])
                         ((:stop @prosess))
                         (swap! prosess assoc
                                :synkronisering-avbrutt (java.time.Instant/now)
                                :feil e))))
                   [chan] 2000)
         synk-ch (synkroniser-til-nats prosess nats-conn ch type)]
     (tap> ["Startet synkronisering av" (str/lower-case type) "fra" start-id])
     (swap! prosess assoc :stop
            (fn []
              (swap! prosess assoc :stop (constantly nil))
              (tap> (str "Draining all chans for " type))
              (stop)
              (tap> "Drain mapping chan")
              (drain! ch)
              (tap> "Draining syncing chan")
              (drain! synk-ch)))
     prosess)))

(defn vent-på-synkronisering [prosess]
  (let [ch (a/chan 2000)]
    (add-watch prosess ::synkronisering
     (fn [_ _ _ proc]
       (when (avsluttet? proc)
         (remove-watch prosess ::synkronisering)
         (when (:synkronisering-ferdig proc)
           (a/put! ch (:data proc)))
         (a/close! ch))))
    (a/<!! ch)))

(defn berik-veiadresser [krets->postnummer vei->kommune]
  (fn [adresse]
    (let [postnummer (->> (:kretser adresse)
                          (map @krets->postnummer)
                          (filter some?)
                          first)
          kommune (-> adresse :vei (@vei->kommune))]
      (-> adresse
          (assoc :postnummer postnummer
                 :kommune kommune)
          (dissoc :kretser)))))

(defn synkroniser [config nats-conn]
  (let [fylke-jobb (last-ned-og-synkroniser config nats-conn "Fylke")
        kommune-jobb (last-ned-og-synkroniser config nats-conn "Kommune")
        postnummer->bruksområder (postnummer/last-ned-postnummere config)
        postnummer-jobb (last-ned-og-synkroniser config nats-conn "Postnummeromrade"
                          {:xf #(assoc % :bruksområder (postnummer->bruksområder (:postnummer %)))
                           :synkron? true})
        vei-jobb (last-ned-og-synkroniser config nats-conn "Veg" {:synkron? true})

        krets->postnummer (future (->> (vent-på-synkronisering postnummer-jobb)
                                       (map (juxt :id :postnummer))
                                       (into {})))
        vei->kommune (future (->> (vent-på-synkronisering vei-jobb)
                                  (map (juxt :id :kommune))
                                  (into {})))
        veiadresse-jobb (last-ned-og-synkroniser config nats-conn "Vegadresse"
                          {:xf (berik-veiadresser krets->postnummer vei->kommune)})
        jobber [fylke-jobb kommune-jobb postnummer-jobb vei-jobb veiadresse-jobb]
        stop #(doseq [j jobber]
                ((:stop @j))
                (remove-watch j ::synkroniseringsfeil))]

    (doseq [jobb [fylke-jobb kommune-jobb postnummer-jobb vei-jobb veiadresse-jobb]]
      (add-watch jobb ::synkroniseringsfeil
                 (fn [_ _ _ jobb]
                   (when-let [feil (:feil jobb)]
                     (tap> (str "Avbryter grunnet feil: " (.getMessage feil)))
                     (stop)))))

    {:fylke-jobb fylke-jobb
     :kommune-jobb kommune-jobb
     :postnummer-jobb postnummer-jobb
     :vei-jobb vei-jobb
     :veiadresse-jobb veiadresse-jobb
     :krets->postnummer krets->postnummer
     :vei->kommune vei->kommune
     :stop stop}))

(defn ^:export run [opts]
  (assert (:config-file opts))
  (add-tap #(if (string? %)
              (println %)
              (apply println %)))
  (tap> "Starter synkronisering")
  (let [config (-> (init-config {:path (:config-file opts)})
                   (config/verify-required-together
                    #{#{:matrikkel/url
                        :matrikkel/username
                        :matrikkel/password}})
                   (config/mask-config))
        nats-conn (init-connection config (edn/read-string
                                           {:readers {'time/duration Duration/parse}}
                                           (slurp "./resources/nats.edn")))
        synk (synkroniser config nats-conn)]
    (doseq [[jobb type] jobber]
      (add-watch (jobb synk) ::fremdrift
                 (fn [_ ref old-status status]
                   (when (avsluttet? status)
                     (remove-watch ref ::fremdrift))

                   (when (every? (comp avsluttet? deref synk) (keys jobber))
                     (System/exit (if (some (comp :feil deref synk) (keys jobber)) 1 0)))

                   (cond (:feil status)
                         (tap> ["Synkronisering av" type "mislyktes"])

                         (:nedlasting-avbrutt status)
                         (tap> ["Nedlasting avbrutt etter synkronisering av"
                                (:synkronisert-til-nats status)
                                type "fullført"])

                         (:synkronisering-ferdig status)
                         (tap> ["Synkronisering av" (:synkronisert-til-nats status)
                                type "fullført"])

                         (and (= 0 (mod (:synkronisert-til-nats status) 10000))
                              (not= (:synkronisert-til-nats status)
                                    (:synkronisert-til-nats old-status)))
                         (tap> [(:synkronisert-til-nats status)
                                type "synkronisert til NATS"]))))
      ;; En liten ping til alle watches, sånn at umiddelbart ferdige prosesser ikke henger systemet
      (swap! (jobb synk) identity))))

(comment

)
