(ns madraas.nats
  (:require
   [charred.api :as charred]
   [clojure.core.async :as a]
   [nats.kv :as kv]))

(defn read-all [conn bucket]
  (let [ch (a/chan 1000)
        sub (kv/watch conn bucket
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

(defn find-first [conn bucket subject]
  (let [ch (a/promise-chan)
        sub (kv/watch conn bucket subject
                      {:watch #(-> %
                                   :nats.kv.entry/value
                                   (charred/read-json {:key-fn keyword})
                                   (->> (a/>!! ch)))
                       :end-of-data #(a/close! ch)}
                      [:nats.kv.watch-option/ignore-delete])
        ret (a/<!! ch)]
    (.close ^java.lang.AutoCloseable sub)
    ret))
