(ns s-exp.oda.jmh
  "JMH benchmarks: forked JVMs, gc profiler (alloc rate per op), oda and
  jsonista measured under the same harness.

  usage: clj -M:jmh [quick|full] [scalar|vector] [payload ...]
  e.g.   clj -M:jmh quick vector small-objects twitter"
  (:require [clojure.pprint]
            [clojure.string :as str]
            [jmh.core :as jmh]
            [jsonista.core :as j]
            [s-exp.oda :as oda]
            [s-exp.oda.payloads :as payloads])
  (:gen-class))

;; ------------------------------------------------------- states (per fork)

(defn payload-bytes ^bytes [payload]
  (or (get (payloads/payloads) (keyword payload))
      (throw (IllegalArgumentException. (str "unknown payload " payload)))))

(defn payload-value-str [payload]
  (oda/parse (payload-bytes payload)))

(defn payload-value-kw [payload]
  (oda/parse (payload-bytes payload) {:key-fn keyword}))

;; ------------------------------------------------------------- benchmarks

(defn oda-read-str [bs] (oda/parse bs))
(defn oda-read-kw [bs] (oda/parse bs {:key-fn keyword}))
(defn oda-write-str-values [v] (oda/write-bytes v))
(defn oda-write-kw-values [v] (oda/write-bytes v))

(defn jsonista-read-str [bs] (j/read-value bs))
(defn jsonista-read-kw [bs] (j/read-value bs j/keyword-keys-object-mapper))
(defn jsonista-write-str-values [v] (j/write-value-as-bytes v))
(defn jsonista-write-kw-values [v] (j/write-value-as-bytes v))

(def spec
  {:benchmarks
   [{:name :oda-read-str, :fn `oda-read-str, :args [:state/bytes]}
    {:name :oda-read-kw, :fn `oda-read-kw, :args [:state/bytes]}
    {:name :oda-write-str, :fn `oda-write-str-values, :args [:state/value-str]}
    {:name :oda-write-kw, :fn `oda-write-kw-values, :args [:state/value-kw]}
    {:name :jsonista-read-str, :fn `jsonista-read-str, :args [:state/bytes]}
    {:name :jsonista-read-kw, :fn `jsonista-read-kw, :args [:state/bytes]}
    {:name :jsonista-write-str, :fn `jsonista-write-str-values, :args [:state/value-str]}
    {:name :jsonista-write-kw, :fn `jsonista-write-kw-values, :args [:state/value-kw]}]

   :states
   {:bytes {:fn `payload-bytes, :args [:param/payload]}
    :value-str {:fn `payload-value-str, :args [:param/payload]}
    :value-kw {:fn `payload-value-kw, :args [:param/payload]}}

   :params
   {:payload ["small-objects" "twitter" "citm" "number-heavy"
              "string-heavy" "string-heavy-raw" "ascii-long"]}})

;; ------------------------------------------------------------------ runner

(def ^:private base-opts
  {:mode :average
   :output-time-unit :us
   :profilers ["gc"]
   :status true
   :fail-on-error true
   ;; generated benchmark classes; on the :jmh alias classpath, kept out of
   ;; target/classes so dev/release artifacts stay clean
   :compile-path "target/jmh-classes"})

(defn- run-opts [preset mode]
  (let [fork-args (cond-> ["-Xms1g" "-Xmx1g"]
                    (= mode "vector") (conj "--add-modules" "jdk.incubator.vector"))
        iter (case preset
               "full" {:warmup {:iterations 5 :time [2 :s]}
                       :measurement {:iterations 5 :time [2 :s]}
                       :fork {:count 2 :jvm {:append-args fork-args}}}
               {:warmup {:iterations 3 :time [1 :s]}
                :measurement {:iterations 3 :time [1 :s]}
                :fork {:count 1 :jvm {:append-args fork-args}}})]
    (merge base-opts iter)))

(defn- report [results]
  (println)
  (printf "%-22s %-18s %12s %14s%n" "benchmark" "payload" "µs/op" "alloc B/op")
  (doseq [{:keys [name params statistics secondary] :as r} results]
    (if (and name statistics)
      (printf "%-22s %-18s %12.2f %14.0f%n"
              (clojure.core/name name)
              (str (get params :payload ""))
              (double (or (:mean statistics) -1.0))
              (double (or (get-in secondary ["gc.alloc.rate.norm" :score 0])
                          (get-in secondary [:gc.alloc.rate.norm :score 0])
                          -1.0)))
      (clojure.pprint/pprint r)))
  (flush))

(def ^:private bench-names
  (into #{} (map (comp name :name)) (:benchmarks spec)))

(defn -main
  "args: [preset] [mode] then any mix of payload names and benchmark names,
  e.g.: quick vector string-heavy oda-read-kw jsonista-read-kw"
  [& args]
  (let [[preset mode & rest-args] args
        preset (or preset "quick")
        mode (or mode "scalar")
        {selects true payloads false} (group-by #(contains? bench-names %) rest-args)
        spec (if (seq payloads)
               (assoc-in spec [:params :payload] (vec payloads))
               spec)
        opts (cond-> (run-opts preset mode)
               (seq selects) (assoc :select (mapv keyword selects)))]
    (println "jmh:" preset mode
             (str/join "," (get-in spec [:params :payload]))
             (if (seq selects) (str "select=" (str/join "," selects)) ""))
    (report (jmh/run spec opts))
    (shutdown-agents)))
