(ns madraas.xml-helpers
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(xml/alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance")

(defn children [node]
  (let [content (:content node)]
    (if (= 1 (count content))
      (first content)
      content)))

(defn get-in-xml [xml path]
  (loop [node xml
         ks (seq path)]
    (cond (not ks) node

          (= (:tag node) (first ks))
          (recur (children node) (next ks))

          (seq? node)
          (->> (filter #(= (:tag %) (first ks)) node)
               (map #(get-in-xml % ks))))))

(defn xsi-type
  ([node] (-> node :attrs ::xsi/type))
  ([node uri->ns-alias]
   (let [[ns-alias type] (some-> node :attrs ::xsi/type (str/split #":"))
         uri (-> (xml/element-nss node) :p->u (get ns-alias ns-alias))
         local-prefix (get uri->ns-alias uri uri)]
     (str local-prefix ":" type))))
