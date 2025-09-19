(ns madraas.nats
  (:require
   [charred.api :as charred]
   [nats.consumer :as consumer]))

(defn read-all [conn stream-name]
  (let [consumer-name (str "madraas-" stream-name "-reader")
        consumer (consumer/create-consumer conn {:nats.consumer/name consumer-name
                                                 :nats.consumer/stream-name stream-name
                                                 :nats.consumer/ack-policy :nats.ack-policy/explicit
                                                 :nats.consumer/deliver-policy :nats.deliver-policy/all
                                                 :nats.consumer/durable? true})
        subscription (consumer/subscribe conn stream-name consumer-name)
        entities
        (loop [entities []]
          (if-let [msg (consumer/pull-message subscription 5000)]
            (do (consumer/ack conn msg)
                (recur (conj entities
                             (-> msg
                                 :nats.message/data
                                 (charred/read-json {:key-fn keyword})))))
            entities))]
    (try (consumer/unsubscribe subscription)
         (consumer/delete-consumer conn (:nats.consumer/id consumer))
         (catch Exception _))

    entities))
