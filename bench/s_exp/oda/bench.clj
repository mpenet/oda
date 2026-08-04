(ns s-exp.oda.bench
  (:require [clojure.string :as str]
            [criterium.core :as cc]
            [jsonista.core :as j]
            [s-exp.oda :as oda])
  (:import (java.nio.file Files Path)))

(set! *warn-on-reflection* true)

(defn- slurp-bytes ^bytes [p]
  (Files/readAllBytes (Path/of p (make-array String 0))))

(def small-objects
  "Realistic API payload: many small objects with repeated keys."
  (j/write-value-as-bytes
   (vec (repeat 1000 {"id" 1234567
                      "name" "some entity name"
                      "active" true
                      "score" 12.53
                      "tags" ["alpha" "beta" "gamma"]
                      "created_at" "2026-08-03T12:00:00Z"}))))

(def string-heavy
  "Jackson-encoded: non-BMP chars become \\uXXXX surrogate pair escapes."
  (j/write-value-as-bytes
   (vec (repeat 500 (apply str (repeat 100 "the quick brown fox é🎵 "))))))

(def string-heavy-raw
  "Same content but raw UTF-8, as emitted by JS/Python/Go serializers."
  (let [s (apply str (repeat 100 "the quick brown fox é🎵 "))]
    (.getBytes (str "[" (str/join "," (repeat 500 (str "\"" s "\""))) "]")
               java.nio.charset.StandardCharsets/UTF_8)))

(def number-heavy
  (j/write-value-as-bytes
   (vec (concat (map #(* % 12345) (range 2500))
                (map #(* % 0.12345) (range 2500))))))

(defn payloads []
  {:small-objects small-objects
   :string-heavy string-heavy
   :string-heavy-raw string-heavy-raw
   :number-heavy number-heavy
   :twitter (slurp-bytes "bench-resources/twitter.json")
   :citm (slurp-bytes "bench-resources/citm_catalog.json")})

(defn- mean-us [f]
  (let [r (cc/quick-benchmark* f nil)]
    (* 1e6 (first (:mean r)))))

(defn compare-all
  "Returns {payload {:oda-kw µs :jsonista-kw µs :oda-str µs :jsonista-str µs}}"
  []
  (into (sorted-map)
        (map (fn [[k ^bytes bs]]
               [k {:oda-kw (mean-us #(oda/parse bs))
                   :jsonista-kw (mean-us #(j/read-value bs j/keyword-keys-object-mapper))
                   :oda-str (mean-us #(oda/parse bs {:keywordize false}))
                   :jsonista-str (mean-us #(j/read-value bs))}]))
        (payloads)))

(defn write-compare-all
  "Same shape as `compare-all` but for the write side: parses each payload in
  both key modes, then benches serializing it back to bytes."
  []
  (into (sorted-map)
        (map (fn [[k ^bytes bs]]
               (let [vkw (oda/parse bs)
                     vstr (oda/parse bs {:keywordize false})]
                 [k {:oda-kw (mean-us #(oda/write-bytes vkw))
                     :jsonista-kw (mean-us #(j/write-value-as-bytes vkw))
                     :oda-str (mean-us #(oda/write-bytes vstr))
                     :jsonista-str (mean-us #(j/write-value-as-bytes vstr))}])))
        (payloads)))

(defn- print-table [results]
  (doseq [[payload r] results]
    (printf "%-16s oda-kw %9.1fµs  jsonista-kw %9.1fµs (%.2fx)   oda-str %9.1fµs  jsonista-str %9.1fµs (%.2fx)%n"
            (name payload)
            (:oda-kw r) (:jsonista-kw r) (/ (:jsonista-kw r) (:oda-kw r))
            (:oda-str r) (:jsonista-str r) (/ (:jsonista-str r) (:oda-str r)))
    (flush)))

(defn -main [& [mode]]
  (case mode
    "write" (print-table (write-compare-all))
    "read" (print-table (compare-all))
    (do (println "=== read ===")
        (print-table (compare-all))
        (println "=== write ===")
        (print-table (write-compare-all)))))
