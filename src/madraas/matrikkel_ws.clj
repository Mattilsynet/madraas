(ns madraas.matrikkel-ws
  (:require
   [clojure.data.xml :as xml]))

(xml/alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'ned "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store"
)

(defn matrikkel-context [service-ns koordinatsystem]
  [(keyword (if (keyword service-ns)
              (name service-ns)
              (str service-ns))
            "matrikkelContext")
   [::dom/locale "no_NO_B"]
   [::dom/brukOriginaleKoordinater "false"]
   [::dom/koordinatsystemKodeId
    [::dom/value (koordinatsystem->matrikkel-id koordinatsystem)]]
   [::dom/systemVersion "4.4"]
   [::dom/klientIdentifikasjon "madraas"]])
