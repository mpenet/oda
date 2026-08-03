(ns s-exp.jzon-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as j]
            [s-exp.jzon :as jzon])
  (:import (com.s_exp.jzon JsonParseException)
           (java.io ByteArrayInputStream)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(defn parse-bytes
  ([^bytes bs] (jzon/parse bs))
  ([^bytes bs opts] (jzon/parse bs opts)))

;; ---------------------------------------------------------------- unit tests

(deftest basic-values
  (is (= {:a 1 :b [1.5 true nil "x"]} (jzon/parse "{\"a\":1,\"b\":[1.5,true,null,\"x\"]}")))
  (is (= {"a" 1} (jzon/parse "{\"a\":1}" {:keywordize false})))
  (is (= [] (jzon/parse "[]")))
  (is (= {} (jzon/parse "{}")))
  (is (nil? (jzon/parse "null")))
  (is (true? (jzon/parse "true")))
  (is (false? (jzon/parse "false")))
  (is (= "hello" (jzon/parse "\"hello\"")))
  (is (= 42 (jzon/parse "42")))
  (is (= 1.5 (jzon/parse " 1.5 "))))

(deftest input-types
  (let [s "{\"a\":[1,2,3]}"]
    (is (= {:a [1 2 3]} (jzon/parse s)))
    (is (= {:a [1 2 3]} (jzon/parse (.getBytes s "UTF-8"))))
    (is (= {:a [1 2 3]} (jzon/parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))
    (is (thrown? IllegalArgumentException (jzon/parse 42)))))

(deftest numbers
  (is (= 0 (jzon/parse "0")))
  (is (= 0 (jzon/parse "-0")))
  (is (= -123 (jzon/parse "-123")))
  (is (= Long/MAX_VALUE (jzon/parse "9223372036854775807")))
  (is (= Long/MIN_VALUE (jzon/parse "-9223372036854775808")))
  (is (= (biginteger "123456789012345678901234567890")
         (jzon/parse "123456789012345678901234567890")))
  (is (= 1.0E10 (jzon/parse "1e10")))
  (is (= -1.5E-3 (jzon/parse "-1.5e-3")))
  (is (= 0.0 (jzon/parse "0.0e0"))))

(deftest strings-and-escapes
  (is (= "a\"b\\c/d\be\ff\ng\rh\ti" (jzon/parse "\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"")))
  (is (= "é𝄞" (jzon/parse "\"\\u00e9\\ud834\\udd1e\"")))
  (is (= "héllo🎵" (jzon/parse "\"héllo🎵\"")))
  (is (= (str (char 0)) (jzon/parse "\"\\u0000\"")))
  (is (= "" (jzon/parse "\"\"")))
  (let [long-s (apply str (repeat 1000 "abcdefghij"))]
    (is (= long-s (jzon/parse (str "\"" long-s "\""))))))

(deftest keys-and-map-representation
  (is (= {:a/b 1} (jzon/parse "{\"a/b\":1}")))
  (is (= {(keyword "") 1} (jzon/parse "{\"\":1}")))
  (is (= {:é 1} (jzon/parse "{\"é\":1}")))
  (is (= {:a 1} (jzon/parse "{\"\\u0061\":1}")))
  (is (instance? clojure.lang.PersistentArrayMap (jzon/parse "{\"a\":1}")))
  (let [big (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 9))
        json (str "{" (clojure.string/join "," (map (fn [i] (str "\"k" i "\":" i)) (range 9))) "}")]
    (is (= big (jzon/parse json)))
    (is (instance? clojure.lang.PersistentHashMap (jzon/parse json)))))

(deftest duplicate-keys-last-wins
  (is (= {:a 2} (jzon/parse "{\"a\":1,\"a\":2}")))
  (let [json (str "{" (clojure.string/join "," (map (fn [i] (str "\"k" (mod i 3) "\":" i)) (range 12))) "}")]
    (is (= {:k0 9 :k1 10 :k2 11} (jzon/parse json)))))

(deftest depth-limit
  (let [deep (str (apply str (repeat 100 "[")) (apply str (repeat 100 "]")))]
    (is (= (jzon/parse deep) (j/read-value deep)))
    (is (thrown? JsonParseException (jzon/parse deep {:max-depth 50})))))

(deftest invalid-inputs
  (doseq [s ["" "{" "[" "{\"a\":1,}" "[1,]" "01" "1." ".5" "+1" "truex" "nul"
             "{\"a\" 1}" "[1 2]" "\"ab" "[1]x" "-" "1e" "'a'" "{a:1}" "[,1]"
             "\"a\tb\"" "\"\\x\"" "\"\\u12\"" "NaN" "Infinity" "[1,,2]"]]
    (is (thrown? JsonParseException (jzon/parse s)) (pr-str s))))

;; ------------------------------------------------------------- JSONTestSuite

(defn- suite-files []
  (->> (io/file "test-resources/json-test-suite")
       (.listFiles)
       (sort-by #(.getName ^java.io.File %))))

(defn- try-parse [^bytes bs opts]
  (try
    {:ok (parse-bytes bs opts)}
    (catch JsonParseException e
      {:error e})))

(deftest json-test-suite
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)
                bs (Files/readAllBytes (.toPath f))]]
    (cond
      (.startsWith n "y_")
      (testing n
        (is (contains? (try-parse bs {:keywordize false}) :ok) n)
        ;; keyword mode must also not blow up on accepted docs
        (is (map? (try-parse bs nil)) n))

      (.startsWith n "n_")
      (testing n
        (is (contains? (try-parse bs {:keywordize false}) :error) n))

      ;; i_ files: implementation-defined, only require no unexpected throwable
      (.startsWith n "i_")
      (testing n
        (is (map? (try-parse bs {:keywordize false})) n)))))

(deftest json-test-suite-differential
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)]
          :when (.startsWith n "y_")
          :let [bs (Files/readAllBytes (.toPath f))
                jsonista-result (try {:ok (j/read-value bs)} (catch Exception _ nil))]
          :when jsonista-result]
    (testing n
      (is (= (:ok jsonista-result) (parse-bytes bs {:keywordize false})) n))))

;; ------------------------------------------------------- generative testing

(def gen-scalar
  (gen/one-of [gen/small-integer
               gen/large-integer
               (gen/double* {:infinite? false :NaN? false})
               gen/string
               gen/boolean
               (gen/return nil)]))

(def gen-json
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner)
                  (gen/map gen/string inner)]))
   gen-scalar))

(defspec differential-string-keys 300
  (prop/for-all [v gen-json]
                (let [bs (j/write-value-as-bytes v)]
                  (= (j/read-value bs) (parse-bytes bs {:keywordize false})))))

(defspec differential-keyword-keys 300
  (prop/for-all [v gen-json]
                (let [bs (j/write-value-as-bytes v)]
                  (= (j/read-value bs j/keyword-keys-object-mapper)
                     (parse-bytes bs nil)))))
