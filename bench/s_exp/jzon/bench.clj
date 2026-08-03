(ns s-exp.jzon.bench
  (:require [criterium.core :as cc]
            [jsonista.core :as j]
            [s-exp.jzon :as jzon]))

(set! *warn-on-reflection* true)

(def small-objects
  "Realistic API payload: many small objects with repeated keys."
  (j/write-value-as-bytes
   (vec (repeat 1000 {"id" 1234567
                      "name" "some entity name"
                      "active" true
                      "score" 12.53
                      "tags" ["alpha" "beta" "gamma"]
                      "created_at" "2026-08-03T12:00:00Z"}))))

(defn -main [& _]
  (println "=== jzon keyword keys ===")
  (cc/quick-bench (jzon/parse ^bytes small-objects))
  (println "=== jsonista keyword keys ===")
  (cc/quick-bench (j/read-value ^bytes small-objects j/keyword-keys-object-mapper))
  (println "=== jzon string keys ===")
  (cc/quick-bench (jzon/parse ^bytes small-objects {:keywordize false}))
  (println "=== jsonista string keys ===")
  (cc/quick-bench (j/read-value ^bytes small-objects)))
