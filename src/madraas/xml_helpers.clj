(ns madraas.xml-helpers
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(xml/alias-uri 'xsi "http://www.w3.org/2001/XMLSchema-instance")

(defn children [node]
  (let [content (:content node)]
    (if (= 1 (count content))
      (first content)
      content)))

(defn get-in-xml* [xml path]
  (loop [node xml
         ks (seq path)]
    (cond (not ks) node

          (= (:tag node) (first ks))
          (recur (children node) (next ks))

          (seq? node)
          (->> (filter #(= (:tag %) (first ks)) node)
               (map #(get-in-xml* % ks))))))

(defn get-in-xml [xml path]
  (let [res (get-in-xml* xml path)]
    (if (sequential? res)
      (flatten res)
      res)))

(defn get-first [xml path]
  (let [res (get-in-xml xml path)]
    (if (sequential? res)
      (first res)
      res)))

(defn xsi-type
  ([node] (-> node :attrs ::xsi/type))
  ([node uri->ns-alias]
   (let [[ns-alias type] (some-> node :attrs ::xsi/type (str/split #":"))
         uri (-> (xml/element-nss node) :p->u (get ns-alias ns-alias))
         local-prefix (get uri->ns-alias uri uri)]
     (str local-prefix ":" type))))

(defn select-tags [xml tags]
  (let [tag-set (into #{} tags)
        xml (cond-> xml (associative? xml) vector)]
    (reduce (fn [res element]
              (cond-> res
                (tag-set (:tag element))
                (assoc (:tag element) (children element))))
            {}
            xml)))
