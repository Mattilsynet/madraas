(ns madraas.matrikkel-ws
  (:require
   [clojure.data.xml :as xml]))

(xml/alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance"
               'soapenv "http://schemas.xmlsoap.org/soap/envelope/"
               'dom "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/domain"
               'ned "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/nedlastning"
               'store "http://matrikkel.statkart.no/matrikkelapi/wsapi/v1/service/store"
)

(defn xml-ns [alias]
  (str (get (ns-aliases 'madraas.matrikkel-ws) (symbol alias) alias)))

(defn matrikkel-context [service-ns-alias]
  [(keyword (xml-ns service-ns-alias) "matrikkelContext")
   [::dom/locale "no_NO_B"]
   [::dom/brukOriginaleKoordinater "true"]
   [::dom/koordinatsystemKodeId
    [::dom/value "10"]]
   [::dom/systemVersion "4.4"]
   [::dom/klientIdentifikasjon "madraas"]])
