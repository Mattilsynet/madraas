(ns madraas.nats
  (:require
   [charred.api :as charred]
   [clojure.core.async :as a]
   [nats.kv :as kv])
  (:import
   (io.nats.client.api KeyValueEntry KeyValueWatchOption KeyValueWatcher)))

(def watch-options
  {:nats.kv.watch-option/ignore-delete KeyValueWatchOption/IGNORE_DELETE
   :nats.kv.watch-option/include-history KeyValueWatchOption/INCLUDE_HISTORY
   :nats.kv.watch-option/meta-only KeyValueWatchOption/META_ONLY
   :nats.kv.watch-option/updates-only KeyValueWatchOption/UPDATES_ONLY})

(defn native->key-value-entry [^KeyValueEntry entry]
  {:nats.kv.entry/bucket (.getBucket entry)
   :nats.kv.entry/key (.getKey entry)
   :nats.kv.entry/created-at (.toInstant (.getCreated entry))
   :nats.kv.entry/operation (kv/operation->k (.getOperation entry))
   :nats.kv.entry/revision (.getRevision entry)
   :nats.kv.entry/value (.getValueAsString entry)})

(definterface PrefixedConsumer
  ;; This allows us to override the getConsumerNamePrefix in KeyValueWatcher.
  ;; Because it is implemented with a default method,
  ;; Clojure cannot see it as part of the interface.
  (^String getConsumerNamePrefix []))

(defn watch
  ([conn bucket-name watcher]
   (watch conn bucket-name nil nil watcher nil))
  ([conn bucket-name watcher watch-opts]
   (watch conn bucket-name nil nil watcher watch-opts))
  ([conn bucket-name subjects watcher watch-opts]
   (watch conn bucket-name subjects nil watcher watch-opts))
  ([conn bucket-name subjects from-rev {:keys [watch end-of-data consumer-name-prefix]} watch-opts]
   (let [subjects (cond
                    (nil? subjects) [">"]
                    (string? subjects) [subjects]
                    (instance? java.util.List subjects) subjects
                    (seqable? subjects) (vec subjects)
                    :else subjects)]
     (.watch (kv/kv-management conn bucket-name)
             ^java.util.List subjects
             (reify
               KeyValueWatcher
               (watch [_ e] (watch (native->key-value-entry e)))
               (endOfData [_] (when end-of-data (end-of-data)))

               PrefixedConsumer
               (getConsumerNamePrefix [_] consumer-name-prefix))
             (or from-rev -1)
             (into-array KeyValueWatchOption (map watch-options watch-opts))))))

(defn read-all [conn bucket-name]
  (let [ch (a/chan 1000)
        sub (watch conn bucket-name
                   {:watch #(-> %
                                :nats.kv.entry/value
                                (charred/read-json {:key-fn keyword})
                                (->> (a/>!! ch)))
                    :end-of-data #(a/close! ch)}
                   [:nats.kv.watch-option/ignore-delete])]
    (a/<!!
     (a/go-loop [m (a/<! ch)
                 ms []]
       (if m
         (recur (a/<! ch) (conj ms m))
         (do (.close ^java.lang.AutoCloseable sub)
             ms))))))
