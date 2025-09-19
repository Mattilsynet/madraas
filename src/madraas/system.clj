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

(def importjobber
  {:fylke-jobb "fylker"
   :kommune-jobb "kommuner"
   :postnummer-jobb "postnummere"
   :vei-jobb "veier"
   :veiadresse-jobb "veiadresser"})

(def endringsjobber
  {:fylke-endringer "fylkesendringer"
   :kommune-endringer "kommuneendringer"
   :postnummer-endringer "postnummerendringer"
   :vei-endringer "veiendringer"
   :veiadresse-endringer "veiadresseendringer"})

(def jobber
  (merge importjobber endringsjobber))

(defn fylke->subject [{:keys [nummer]}]
  (str "fylker." nummer))

(defn split-kommunenummer [nummer]
  (str (subs nummer 0 2) "." (subs nummer 2)))

(defn kommune->subject [{:keys [nummer]}]
  (str "kommuner." (split-kommunenummer nummer)))

(defn postnummerområde->subject [{:keys [postnummer]}]
  (str "postnummere." postnummer))

(defn vei->subject [{:keys [kommune id]}]
  (str "veier." (split-kommunenummer kommune) "." id))

(defn veiadresse->subject [{:keys [kommune postnummer vei id]}]
  (str "adresser." (split-kommunenummer kommune) "." postnummer "." vei "." id))

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
                                         (mapv (get-in api-er [type :xf]))
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
              (stream/publish nats-conn
                {:nats.message/subject (subject-fn msg)
                 :nats.message/data (charred/write-json-str msg)})
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

(defn add-watch-and-touch
  "Legger til overvåkning av et atom og dytter borti det
  i tilfelle den endringen vi ser etter allerede har skjedd."
  [ref id fn]
  (add-watch ref id fn)
  (swap! ref identity))

(defn vent-på-synkronisering [prosess]
  (let [ch (a/chan 1)]
    (add-watch-and-touch
     prosess ::synkronisering
     (fn [_ _ _ proc]
       (when (avsluttet? proc)
         (remove-watch prosess ::synkronisering)
         (when (:synkronisering-ferdig proc)
           (a/put! ch (:data proc)))
         (a/close! ch))))
    (a/<!! ch)))

(defn berik-veiadresser [krets->postnummer vei->kommune]
  (fn [adresse]
    (let [postnummer (or (:postnummer adresse)
                         (->> (:kretser adresse)
                              (map @krets->postnummer)
                              (filter some?)
                              first))
          kommune (or (:kommune adresse)
                      (-> adresse :vei (@vei->kommune)))]
      (-> adresse
          (assoc :postnummer postnummer
                 :kommune kommune)
          (dissoc :kretser)))))

(defn siste-endring-id [config nats-conn]
  (or (:nats.kv.entry/value (kv/get nats-conn :madraas/siste-endring-id))
      (matrikkel-ws/hent-siste-endring-id config)))

(defn hent-endringer
  ([config nats-conn type start-id] (hent-endringer (atom {:startet (java.time.Instant/now)
                                                           :lastet-ned 0})
                                          config nats-conn type start-id))
  ([prosess config nats-conn type start-id]
   (tap> ["Laster ned endringer av" type "fra" start-id])
   (let [ch (a/chan 2000)
         running? (atom true)
         {:keys [bucket search-prefix]} (api-er type)]
     (a/go
       (try
         (loop [start start-id]
           (swap! prosess assoc :start-id start)
           (tap> ["Laster ned fra" start])
           (let [{:keys [xf ignore endringstype]} (api-er type)
                 {:keys [endringer entiteter ferdig? siste-id]}
                 (matrikkel-ws/hent-endringer config (or endringstype type) start)
                 entiteter (cond->> (mapv xf entiteter)
                             ignore (remove ignore)
                             :always (reduce #(assoc %1 (:id %2) %2) {}))
                 endringer (->> endringer
                                (map (fn [endring]
                                       (assoc endring :entitet
                                              (if (= "Sletting" (:endringstype endring))
                                                (some-> (stream/get-last-message nats-conn bucket (str bucket "." search-prefix (:entitet endring)))
                                                        (charred/read-json {:key-fn keyword})
                                                        (assoc :gyldigTil (subs (:endringstidspunkt endring)
                                                                                0 10)))
                                                (get entiteter (:entitet endring))))))
                                (filter :entitet))]
             (swap! prosess update :lastet-ned + (count endringer))

             (doseq [e endringer]
               (when (not (and ignore (ignore (:entitet e))))
                 (a/>! ch e)))

             (if (and @running? (not ferdig?))
               (recur (inc siste-id))
               (do
                 (when @running?
                   (swap! prosess assoc :nedlasting-ferdig (java.time.Instant/now)))
                 (a/close! ch)))))
         (catch Exception e
           (swap! prosess assoc :feil e)
           (tap> ["Henting av endringer mislyktes:" type e])
           ((:stop @prosess)))))
     {:chan ch
      :stop #(do
               (when @running?
                 (swap! prosess assoc :nedlasting-avbrutt (java.time.Instant/now)))
               (reset! running? false)
               (drain! ch))})))

(defn synkroniser-endring-til-nats [prosess nats-conn ch type]
  (swap! prosess assoc :synkronisert-til-nats 0)
  (a/go
    (let [last-msg (atom nil)
          {:keys [bucket subject-fn]} (api-er type)]
      (try
        (loop []
          (if-let [msg (a/<! ch)]
            (let [{:keys [entitet endringstype]} (reset! last-msg msg)
                  subject (subject-fn entitet)
                  seq-no (if (= "Nyoppretting" endringstype)
                           (:nats.message/seq (stream/get-last-message nats-conn bucket subject))
                           (:nats.publish-ack/seq-no
                            (stream/publish nats-conn
                              {:nats.message/subject subject
                               :nats.message/data (charred/write-json-str msg)})))]
              (stream/publish nats-conn
                {:nats.message/subject (str "endringer." bucket "." (:id msg))
                 :nats.message/data (-> (assoc msg
                                               :entitet subject
                                               :entiet-seq-no seq-no)
                                        charred/write-json-str)})
              (swap! prosess update :synkronisert-til-nats inc)
              (recur))
            (swap! prosess assoc :synkronisering-ferdig (java.time.Instant/now))))
        (catch Exception e
          (tap> ["Klarte ikke å skrive endring av" (:id @last-msg) "av" type "til NATS" bucket ":" e])
          (swap! prosess assoc
                 :synkronisering-avbrutt (java.time.Instant/now)
                 :feil e)
          ((:stop @prosess)))))))

(defn ^{:indent 3} hent-endringer-og-synkroniser
  ([config nats-conn type start-id {:keys [xf]}]
   (let [prosess (atom {:startet (java.time.Instant/now)
                        :lastet-ned 0
                        :data []})
         {:keys [chan stop]} (hent-endringer prosess config nats-conn type start-id)
         ch (a/map (fn [endring]
                     (try
                       (let [endring (cond-> endring xf (update :entitet xf))]
                         (swap! prosess update :data conj (:entitet endring))
                         endring)
                       (catch Exception e
                         (tap> ["Mapping av endring" (:id endring) "av" type "mislyktes:" e])
                         ((:stop @prosess))
                         (swap! prosess assoc
                                :synkronisering-avbrutt (java.time.Instant/now)
                                :feil e))))
                   [chan] 2000)
         synk-ch (synkroniser-endring-til-nats prosess nats-conn ch type)]
     (tap> ["Synkroniserer endringer av" (str/lower-case type) "fra" start-id])
     (swap! prosess assoc :stop
            (fn []
              (swap! prosess assoc :stop (constantly nil))
              (tap> ["Draining all chans for changes of" type])
              (stop)
              (tap> "Drain mapping chan")
              (drain! ch)
              (tap> "Draining syncing chan")
              (drain! synk-ch)))
     prosess)))

(defn berik-postnummer
  ([postnummer->bruksområder]
   (partial berik-postnummer postnummer->bruksområder))
  ([postnummer->bruksområder postnummerområde]
   (-> postnummerområde
       (assoc :bruksområder (postnummer->bruksområder (:postnummer postnummerområde)))
       (dissoc :xsi-type))))

(defn hent-alle-endringer [config nats-conn {:keys [berik-postnummer siste-endring-id] :as prosess}]
  (let [synkroniser-til-id (matrikkel-ws/hent-siste-endring-id config)
        fylke-endringer (hent-endringer-og-synkroniser config nats-conn "Fylke" siste-endring-id {})
        kommune-endringer (hent-endringer-og-synkroniser config nats-conn "Kommune" siste-endring-id {})
        postnummer-endringer (hent-endringer-og-synkroniser config nats-conn "Postnummeromrade" siste-endring-id {:xf berik-postnummer})
        vei-endringer (hent-endringer-og-synkroniser config nats-conn "Veg" siste-endring-id {})
        krets->postnummer (future
                            (let [res (->> (vent-på-synkronisering postnummer-endringer)
                                           (map (juxt :id :postnummer))
                                           (into @(:krets->postnummer prosess)))]
                              (tap> "Postnummermapping oppdatert")
                              res))
        vei->kommune (future
                       (let [res (->> (vent-på-synkronisering vei-endringer)
                                      (map (juxt :id :kommune))
                                      (into @(:vei->kommune prosess)))]
                         (tap> "Kommunemapping oppdatert")
                         res))
        veiadresse-endringer (hent-endringer-og-synkroniser config nats-conn "Vegadresse" siste-endring-id
                                                            {:xf (berik-veiadresser krets->postnummer vei->kommune)})
        jobber [fylke-endringer kommune-endringer postnummer-endringer vei-endringer veiadresse-endringer]
        stop #(doseq [j jobber]
                ((:stop @j))
                (remove-watch j ::synkroniseringsfeil))]

    (doseq [jobb jobber]
      (add-watch-and-touch jobb ::synkroniseringsfeil
                           (fn [_ _ _ jobb]
                             (when-let [feil (:feil jobb)]
                               (tap> (str "Avbryter grunnet feil: " (.getMessage feil)))
                               (stop))))
      (add-watch-and-touch jobb ::endringer-ferdig
                           (fn [_ ref _ jobb]
                             (when (avsluttet? jobb)
                               (remove-watch ref ::endringer-ferdig))
                             (when (every? (comp :synkronisering-ferdig deref) jobber)
                               (kv/put nats-conn :madraas/siste-endring-id synkroniser-til-id)))))
    (-> prosess
        (assoc :fylke-endringer fylke-endringer
               :kommune-endringer kommune-endringer
               :postnummer-endringer postnummer-endringer
               :vei-endringer vei-endringer
               :veiadresse-endringer veiadresse-endringer)
        (update :stop (fn [old-stop]
                        (fn [] (old-stop) (stop)))))
    ))

(defn importer-nye [config nats-conn]
  (let [siste-endring-id (siste-endring-id config nats-conn)
        fylke-jobb (last-ned-og-synkroniser config nats-conn "Fylke")
        kommune-jobb (last-ned-og-synkroniser config nats-conn "Kommune")
        berik-postnummer (berik-postnummer (postnummer/last-ned-postnummere config))
        postnummer-jobb (last-ned-og-synkroniser config nats-conn "Postnummeromrade"
                          {:xf berik-postnummer
                           :synkron? true})
        vei-jobb (last-ned-og-synkroniser config nats-conn "Veg" {:synkron? true})
        krets->postnummer (future
                            (let [res (->> (vent-på-synkronisering postnummer-jobb)
                                           (map (juxt :id :postnummer))
                                           (into {}))]
                              (tap> "Postnummermapping opprettet")
                              res))
        vei->kommune (future
                       (let [res (->> (vent-på-synkronisering vei-jobb)
                                      (map (juxt :id :kommune))
                                      (into {}))]
                         (tap> "Kommunemapping opprettet")
                         res))
        veiadresse-jobb (last-ned-og-synkroniser config nats-conn "Vegadresse"
                          {:xf (berik-veiadresser krets->postnummer vei->kommune)})
        jobber [fylke-jobb kommune-jobb postnummer-jobb vei-jobb veiadresse-jobb]
        stop #(doseq [j jobber]
                ((:stop @j))
                (remove-watch j ::synkroniseringsfeil))]

    (doseq [jobb jobber]
      (add-watch-and-touch
       jobb ::synkroniseringsfeil
       (fn [_ _ _ jobb]
         (when-let [feil (:feil jobb)]
           (tap> (str "Avbryter grunnet feil: " (.getMessage feil)))
           (stop)))))

    {:siste-endring-id siste-endring-id
     :fylke-jobb fylke-jobb
     :kommune-jobb kommune-jobb
     :postnummer-jobb postnummer-jobb
     :vei-jobb vei-jobb
     :veiadresse-jobb veiadresse-jobb
     :berik-postnummer berik-postnummer
     :krets->postnummer krets->postnummer
     :vei->kommune vei->kommune
     :stop stop}))

(defn avslutt-i-fremtiden [& [feil?]]
  (future (Thread/sleep 1000)
          (System/exit (if feil? 1 0))))

(defn vent-på-jobber [prosess jobber ved-suksess ved-avbrudd-eller-feil]
  (doseq [[jobb type] jobber]
      (add-watch-and-touch
       (jobb prosess) ::fremdrift
       (fn [_ ref old-status status]
         (when (avsluttet? status)
           (remove-watch ref ::fremdrift))

         (when (every? (comp avsluttet? deref prosess) (keys jobber))
           (if (every? (comp :synkronisering-ferdig deref prosess) (keys jobber))
             (ved-suksess)
             (ved-avbrudd-eller-feil (some (comp :feil deref prosess) (keys jobber)))))

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
                      type "synkronisert til NATS"]))))))

(defn synkroniser [config nats-conn avslutt]
  (let [importprosess (importer-nye config nats-conn)
        prosess (atom importprosess)]
    (vent-på-jobber
     importprosess importjobber
     #(let [endringsprosess (hent-alle-endringer config nats-conn importprosess)]
        (reset! prosess (vent-på-jobber endringsprosess endringsjobber avslutt avslutt)))
     avslutt)
    prosess))

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
                                           (slurp "./resources/nats.edn")))]
    (synkroniser config nats-conn avslutt-i-fremtiden)))

(comment

)
