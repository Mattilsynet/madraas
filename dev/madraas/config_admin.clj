(ns madraas.config-admin
  (:require [confair.config :as config]
            [confair.config-admin :as ca]))

(def secret-keys
  [:nats.core/credentials])

(def overrides {:secrets {:secret/prod [:config/file "./config/prod-secret.txt"]}})

(comment
  (set! *print-namespace-maps* false)

  (meta (config/from-file "config/local-config.edn"))

  (for [k secret-keys]
    (ca/conceal-value (config/from-file "./config/local-config.edn") :secret/dev k))

  ;; Kjør denne på nytt for å kryptere secrets i konfigurasjonen
  (for [k secret-keys]
    [(ca/conceal-value (config/from-file "./config/prod-config.edn" overrides) :secret/prod k)
     (ca/conceal-value (config/from-file "./config/local-prod-config.edn" overrides) :secret/prod k)])

  (config/from-file "config/prod-config.edn")

)
