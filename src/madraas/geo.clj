(ns madraas.geo
  (:import (org.osgeo.proj4j CoordinateTransformFactory CRSFactory ProjCoordinate)))

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
                                   (or (:z koordinat) 0))]
    (when (not= fra-epsg til-epsg)
      (.transform transformerer koordinat koordinat))
    {:x (.-x koordinat)
     :y (.-y koordinat)
     :z (.-z koordinat)}))
