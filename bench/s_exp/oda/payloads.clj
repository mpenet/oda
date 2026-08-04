(ns s-exp.oda.payloads
  "Benchmark payload matrix, shared by the criterium and JMH harnesses."
  (:require [clojure.string :as str]
            [jsonista.core :as j])
  (:import (java.nio.file Files Path)))

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

(def ascii-long
  "Long pure-ASCII strings: log lines, base64 blobs, CSV-in-JSON etc."
  (j/write-value-as-bytes
   (vec (repeat 100 (apply str (repeat 2400 "abcdefgh "))))))

(defn payloads []
  {:small-objects small-objects
   :string-heavy string-heavy
   :string-heavy-raw string-heavy-raw
   :number-heavy number-heavy
   :ascii-long ascii-long
   :twitter (slurp-bytes "bench-resources/twitter.json")
   :citm (slurp-bytes "bench-resources/citm_catalog.json")})
