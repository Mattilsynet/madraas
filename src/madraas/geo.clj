(ns madraas.geo
  (:import (java.math MathContext)
           (org.osgeo.proj4j CoordinateTransformFactory CRSFactory ProjCoordinate))
  (:require [clojure.math :as math]))

(def koordinatsystemer
  {"25832" {:navn "ETRS89 32N"
            :presisjon 1}
   "25833" {:navn "ETRS89 33N"
            :presisjon 1}
   "25835" {:navn "ETRS89 35N"
            :presisjon 1}
   "4258" {:navn "ETRS89 Geodetisk"
           :presisjon (math/pow 10 6)}
   "3857" {:navn "WGS84 Pseudo-Mercator"
           :presisjon 1}
   "4326" {:navn "WGS84"
           :presisjon (math/pow 10 6)}})

(def factory (CRSFactory.))

(def transform-factory (CoordinateTransformFactory.))

(defn- lag-koordinatsystem* [epsg]
  (.createFromName factory (str "EPSG:" epsg)))

(def lag-koordinatsystem (memoize lag-koordinatsystem*))

(defn- lag-transformerer* [fra-epsg til-epsg]
  (let [fra (lag-koordinatsystem fra-epsg)
        til (lag-koordinatsystem til-epsg)]
    (.createTransform transform-factory fra til)))

(def lag-transformerer (memoize lag-transformerer*))

(defn konverter-koordinater [fra-epsg til-epsg koordinat]
  (let [transformerer (lag-transformerer fra-epsg til-epsg)
        koordinat (ProjCoordinate. (:x koordinat)
                                   (:y koordinat)
                                   (or (:z koordinat) 0))
        presisjon (get-in koordinatsystemer [til-epsg :presisjon])]
    (when (not= fra-epsg til-epsg)
      (.transform transformerer koordinat koordinat))
    {:x (-> (.-x koordinat) (* presisjon) math/round (/ presisjon) double)
     :y (-> (.-y koordinat) (* presisjon) math/round (/ presisjon) double)
     :z (-> (.-z koordinat) (* presisjon) math/round (/ presisjon) double)}))
