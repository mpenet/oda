(ns s-exp.oda.bench
  (:require [criterium.core :as cc]
            [jsonista.core :as j]
            [s-exp.oda :as oda]
            [s-exp.oda.payloads :as payloads]))

(set! *warn-on-reflection* true)

(def small-objects payloads/small-objects)
(def string-heavy payloads/string-heavy)
(def string-heavy-raw payloads/string-heavy-raw)
(def number-heavy payloads/number-heavy)
(def ascii-long payloads/ascii-long)

(defn payloads []
  (payloads/payloads))

(defn- mean-us [f]
  (let [r (cc/quick-benchmark* f nil)]
    (* 1e6 (first (:mean r)))))

(defn compare-all
  "Returns {payload {:oda-kw µs :jsonista-kw µs :oda-str µs :jsonista-str µs}}"
  []
  (into (sorted-map)
        (map (fn [[k ^bytes bs]]
               [k {:oda-kw (mean-us #(oda/parse bs {:key-fn keyword}))
                   :jsonista-kw (mean-us #(j/read-value bs j/keyword-keys-object-mapper))
                   :oda-str (mean-us #(oda/parse bs))
                   :jsonista-str (mean-us #(j/read-value bs))}]))
        (payloads)))

(defn write-compare-all
  "Same shape as `compare-all` but for the write side: parses each payload in
  both key modes, then benches serializing it back to bytes."
  []
  (into (sorted-map)
        (map (fn [[k ^bytes bs]]
               (let [vkw (oda/parse bs {:key-fn keyword})
                     vstr (oda/parse bs)]
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
