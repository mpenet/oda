(ns s-exp.oda-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as j]
            [s-exp.oda :as oda])
  (:import (com.s_exp.oda JsonParseException)
           (java.io ByteArrayInputStream)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(defn parse-bytes
  ([^bytes bs] (oda/parse bs))
  ([^bytes bs opts] (oda/parse bs opts)))

;; ---------------------------------------------------------------- unit tests

(deftest basic-values
  ;; default (no key-fn) leaves keys as strings
  (is (= {"a" 1 "b" [1.5 true nil "x"]} (oda/parse "{\"a\":1,\"b\":[1.5,true,null,\"x\"]}")))
  (is (= {:a 1 :b [1.5 true nil "x"]}
         (oda/parse "{\"a\":1,\"b\":[1.5,true,null,\"x\"]}" {:key-fn keyword})))
  (is (= {"a" 1} (oda/parse "{\"a\":1}")))
  (is (= [] (oda/parse "[]")))
  (is (= {} (oda/parse "{}")))
  (is (nil? (oda/parse "null")))
  (is (true? (oda/parse "true")))
  (is (false? (oda/parse "false")))
  (is (= "hello" (oda/parse "\"hello\"")))
  (is (= 42 (oda/parse "42")))
  (is (= 1.5 (oda/parse " 1.5 "))))

(deftest input-types
  (let [s "{\"a\":[1,2,3]}"]
    (is (= {"a" [1 2 3]} (oda/parse s)))
    (is (= {"a" [1 2 3]} (oda/parse (.getBytes s "UTF-8"))))
    (is (= {"a" [1 2 3]} (oda/parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))
    (is (thrown? IllegalArgumentException (oda/parse 42)))))

(deftest numbers
  (is (= 0 (oda/parse "0")))
  (is (= 0 (oda/parse "-0")))
  (is (= -123 (oda/parse "-123")))
  (is (= Long/MAX_VALUE (oda/parse "9223372036854775807")))
  (is (= Long/MIN_VALUE (oda/parse "-9223372036854775808")))
  (is (= (biginteger "123456789012345678901234567890")
         (oda/parse "123456789012345678901234567890")))
  (is (= 1.0E10 (oda/parse "1e10")))
  (is (= -1.5E-3 (oda/parse "-1.5e-3")))
  (is (= 0.0 (oda/parse "0.0e0"))))

(defn- bits= [^double expected ^double actual]
  (= (Double/doubleToLongBits expected) (Double/doubleToLongBits actual)))

(def double-torture-values
  ["2.2250738585072011e-308"
   "2.2250738585072014e-308"
   "4.9e-324" "5e-324"
   "2.4703282292062327e-324"
   "2.4703282292062328e-324"
   "1.7976931348623157e308"
   "1e308" "1e-308" "1e-322" "1e-324"
   "7.2057594037927933e16"
   "1e23" "9007199254740993e0"
   "3.141592653589793" "2.718281828459045"
   "-0.0" "0.0" "1.0" "-1.0" "0.1" "1e7" "1e-3" "123456.789"
   "0.000000000000000000000000000000000000000000001"
   "1.00000000000000011102230246251565404236316680908203125"
   "12345678901234567890123456789.012345678901234567890e-15"])

(deftest write-double-torture
  (doseq [s double-torture-values]
    (let [d (Double/parseDouble s)]
      (is (bits= d (oda/parse (oda/write-str d))) s)
      (is (bits= (- d) (oda/parse (oda/write-str (- d)))) (str "-" s)))))

(defspec write-double-round-trip 5000
  (prop/for-all [d (gen/double* {:infinite? false :NaN? false})]
                (= (Double/doubleToLongBits d)
                   (Double/doubleToLongBits (oda/parse (oda/write-str d))))))

(deftest double-torture
  (doseq [s ["2.2250738585072011e-308"  ; once hung Java's parser
             "2.2250738585072014e-308"  ; MIN_NORMAL
             "4.9e-324" "5e-324"        ; MIN_VALUE
             "2.4703282292062327e-324"  ; halfway boundary below MIN_VALUE
             "2.4703282292062328e-324"
             "1.7976931348623157e308"   ; MAX_VALUE
             "1.7976931348623159e308"   ; overflows to Infinity
             "1e308" "1e309" "1e310" "1e-308" "1e-322" "1e-324"
             "123.456e-789" "123.456e789"
             "7.2057594037927933e16"    ; EL mantissa round-up past 2^53
             "1e23" "9007199254740993e0"
             "3.141592653589793" "2.718281828459045"
             "-0.0" "0.0" "-1e-400" "1e400"
             "0.000000000000000000000000000000000000000000001"
             "1.00000000000000011102230246251565404236316680908203125"
             "0.999999999999999999999999999999999999999999999999999"
             "12345678901234567890123456789.012345678901234567890e-15"]]
    (let [expected (Double/parseDouble s)]
      (is (bits= expected (oda/parse s)) s)
      (when-not (str/starts-with? s "-")
        (is (bits= (- expected) (oda/parse (str "-" s))) (str "-" s))))))

(def gen-decimal-string
  "Random JSON double strings: 1-40 significant digits, optional fraction
  point, exponent in ±400 — exercises the EL fast path, the truncated
  >19-digit path and the out-of-range fallbacks."
  (gen/fmap (fn [[neg first-d rest-d dot-pos exp]]
              (let [ds (apply str first-d rest-d)
                    dp (inc (mod dot-pos (count ds)))
                    mant (if (< dp (count ds))
                           (str (subs ds 0 dp) "." (subs ds dp))
                           ds)]
                (str (when neg "-") mant "e" exp)))
            (gen/tuple gen/boolean
                       (gen/choose 1 9)
                       (gen/vector (gen/choose 0 9) 0 39)
                       gen/nat
                       (gen/choose -400 400))))

(defspec double-differential-vs-jdk 3000
  (prop/for-all [s gen-decimal-string]
                (= (Double/doubleToLongBits (Double/parseDouble s))
                   (Double/doubleToLongBits (oda/parse s)))))

(defspec double-shortest-repr-round-trip 3000
  (prop/for-all [d (gen/double* {:infinite? false :NaN? false})]
                (= (Double/doubleToLongBits d)
                   (Double/doubleToLongBits (oda/parse (str d))))))

(deftest strings-and-escapes
  (is (= "a\"b\\c/d\be\ff\ng\rh\ti" (oda/parse "\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"")))
  (is (= "é𝄞" (oda/parse "\"\\u00e9\\ud834\\udd1e\"")))
  (is (= "héllo🎵" (oda/parse "\"héllo🎵\"")))
  (is (= (str (char 0)) (oda/parse "\"\\u0000\"")))
  (is (= "" (oda/parse "\"\"")))
  (let [long-s (apply str (repeat 1000 "abcdefghij"))]
    (is (= long-s (oda/parse (str "\"" long-s "\""))))))

(deftest keys-and-map-representation
  (is (= {:a/b 1} (oda/parse "{\"a/b\":1}" {:key-fn keyword})))
  (is (= {(keyword "") 1} (oda/parse "{\"\":1}" {:key-fn keyword})))
  (is (= {:é 1} (oda/parse "{\"é\":1}" {:key-fn keyword})))
  (is (= {:a 1} (oda/parse "{\"\\u0061\":1}" {:key-fn keyword})))
  (is (instance? clojure.lang.PersistentArrayMap (oda/parse "{\"a\":1}")))
  (let [big (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 9))
        json (str "{" (clojure.string/join "," (map (fn [i] (str "\"k" i "\":" i)) (range 9))) "}")]
    (is (= big (oda/parse json {:key-fn keyword})))
    (is (instance? clojure.lang.PersistentHashMap (oda/parse json)))))

(deftest custom-key-fn
  (is (= {:a_b 1} (oda/parse "{\"a-b\":1}" {:key-fn #(keyword (str/replace % "-" "_"))})))
  (is (= {"A" 1 "B" 2} (oda/parse "{\"a\":1,\"b\":2}" {:key-fn str/upper-case})))
  ;; repeated keys must hit the per-parse cache and stay consistent
  (let [json (str "[" (str/join "," (repeat 50 "{\"k\":1}")) "]")
        calls (atom 0)
        res (oda/parse json {:key-fn (fn [s] (swap! calls inc) (keyword s))})]
    (is (= (repeat 50 {:k 1}) res))
    (is (= 1 @calls)))
  ;; escaped/non-ASCII keys go through the same fn
  (is (= {"É" 1} (oda/parse "{\"é\":1}" {:key-fn str/upper-case})))
  (is (= {"A" 1} (oda/parse "{\"\\u0061\":1}" {:key-fn str/upper-case})))
  ;; same fn instance gets cross-parse caching (global identity-keyed table)
  (let [calls (atom 0)
        f (fn [s] (swap! calls inc) (keyword s))]
    (is (= {:zqx1 1} (oda/parse "{\"zqx1\":1}" {:key-fn f})))
    (is (= {:zqx1 1} (oda/parse "{\"zqx1\":1}" {:key-fn f})))
    (is (= 1 @calls)))
  ;; keywords are IFns doing lookup -> would silently produce nil keys
  (is (thrown? IllegalArgumentException (oda/parse "{\"a\":1}" {:key-fn :keywords}))))

(deftest duplicate-keys-last-wins
  (is (= {:a 2} (oda/parse "{\"a\":1,\"a\":2}" {:key-fn keyword})))
  (is (= {"a" 2} (oda/parse "{\"a\":1,\"a\":2}")))
  (let [json (str "{" (clojure.string/join "," (map (fn [i] (str "\"k" (mod i 3) "\":" i)) (range 12))) "}")]
    (is (= {:k0 9 :k1 10 :k2 11} (oda/parse json {:key-fn keyword})))))

(deftest depth-limit
  (let [deep (str (apply str (repeat 100 "[")) (apply str (repeat 100 "]")))]
    (is (= (oda/parse deep) (j/read-value deep)))
    (is (thrown? JsonParseException (oda/parse deep {:max-depth 50})))))

(deftest invalid-inputs
  (doseq [s ["" "{" "[" "{\"a\":1,}" "[1,]" "01" "1." ".5" "+1" "truex" "nul"
             "{\"a\" 1}" "[1 2]" "\"ab" "[1]x" "-" "1e" "'a'" "{a:1}" "[,1]"
             "\"a\tb\"" "\"\\x\"" "\"\\u12\"" "NaN" "Infinity" "[1,,2]"]]
    (is (thrown? JsonParseException (oda/parse s)) (pr-str s))))

;; ------------------------------------------------------------- JSONTestSuite

(defn- suite-files []
  (->> (io/file "test-resources/json-test-suite")
       (.listFiles)
       (sort-by #(.getName ^java.io.File %))))

(defn- try-parse
  ([^bytes bs] (try-parse bs nil))
  ([^bytes bs opts]
   (try
     {:ok (parse-bytes bs opts)}
     (catch JsonParseException e
       {:error e}))))

(deftest json-test-suite
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)
                bs (Files/readAllBytes (.toPath f))]]
    (cond
      (.startsWith n "y_")
      (testing n
        (is (contains? (try-parse bs) :ok) n)
        ;; keyword mode must also not blow up on accepted docs
        (is (map? (try-parse bs {:key-fn keyword})) n))

      (.startsWith n "n_")
      (testing n
        (is (contains? (try-parse bs) :error) n))

      ;; i_ files: implementation-defined, only require no unexpected throwable
      (.startsWith n "i_")
      (testing n
        (is (map? (try-parse bs)) n)))))

(deftest json-test-suite-differential
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)]
          :when (.startsWith n "y_")
          :let [bs (Files/readAllBytes (.toPath f))
                jsonista-result (try {:ok (j/read-value bs)} (catch Exception _ nil))]
          :when jsonista-result]
    (testing n
      (is (= (:ok jsonista-result) (parse-bytes bs)) n))))

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
                  (= (j/read-value bs) (parse-bytes bs)))))

(defspec differential-keyword-keys 300
  (prop/for-all [v gen-json]
                (let [bs (j/write-value-as-bytes v)]
                  (= (j/read-value bs j/keyword-keys-object-mapper)
                     (parse-bytes bs {:key-fn keyword})))))

;; ----------------------------------------------------------------- writer

(deftest writer-basics
  (is (= "null" (oda/write-str nil)))
  (is (= "true" (oda/write-str true)))
  (is (= "[]" (oda/write-str [])))
  (is (= "{}" (oda/write-str {})))
  (is (= "{\"a\":1}" (oda/write-str {:a 1})))
  (is (= "{\"a/b\":1}" (oda/write-str {:a/b 1})))
  (is (= "{\"a\":1}" (oda/write-str {"a" 1})))
  (is (= "{\"a\":1}" (oda/write-str {'a 1})))
  (is (= "{\"1.5\":1}" (oda/write-str {1.5 1})))
  (is (= "[1,2]" (oda/write-str '(1 2))))
  (is (= "[1]" (oda/write-str #{1})))
  (is (= "\"a\\\"b\\\\c\\nd\\u0000\"" (oda/write-str (str "a\"b\\c\nd" (char 0)))))
  (is (= "\"é🎵\"" (oda/write-str "é🎵")))
  (is (= "-9223372036854775808" (oda/write-str Long/MIN_VALUE)))
  (is (= "123456789012345678901234567890" (oda/write-str 123456789012345678901234567890N)))
  (is (= "1.5" (oda/write-str 1.5M)))
  (is (= "\"c\"" (oda/write-str \c)))
  (is (= "{\"a\":1}" (oda/write-str (java.util.Map/of "a" 1))))
  (is (= "[1,2]" (oda/write-str (java.util.List/of 1 2))))
  (is (thrown? IllegalArgumentException (oda/write-str (Object.))))
  (is (thrown? IllegalArgumentException (oda/write-str ##NaN)))
  (is (thrown? IllegalArgumentException (oda/write-str ##Inf)))
  (is (thrown? IllegalArgumentException (oda/write-str {[1] "bad key"})))
  (is (= "\"x\"" (oda/write-str (Object.) {:default-fn (constantly "x")})))
  (let [u (random-uuid)]
    (is (= (str "\"" u "\"") (oda/write-str u))))
  (let [out (java.io.ByteArrayOutputStream.)]
    (oda/write {:a [1 2]} out)
    (is (= "{\"a\":[1,2]}" (String. (.toByteArray out) "UTF-8"))))
  (is (= {:a [1 2]} (oda/parse (oda/write-bytes {:a [1 2]}) {:key-fn keyword}))))

(deftest writer-streaming
  ;; streamed output must be byte-identical to buffered output
  (let [v (vec (repeatedly 5000 (fn [] {:id (rand-int 100000)
                                        :name (str "entity-" (rand-int 1000) "-é🎵")
                                        :tags ["a" "b"]})))
        out (java.io.ByteArrayOutputStream.)]
    (oda/write v out)
    (is (= (seq (oda/write-bytes v)) (seq (.toByteArray out)))))
  ;; single string larger than the 64KB stream buffer
  (let [s (apply str (repeat 200000 "x"))
        out (java.io.ByteArrayOutputStream.)]
    (oda/write [s] out)
    (is (= [s] (oda/parse (.toByteArray out))))))

(deftest writer-lone-surrogate
  ;; lone surrogates cannot be encoded as UTF-8; we emit U+FFFD
  (is (= "\"�\"" (oda/write-str (String. (char-array [(char 0xD800)]))))))

(def gen-json-kw
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner)
                  (gen/map (gen/fmap keyword gen/string) inner)]))
   gen-scalar))

(defspec write-parse-round-trip-strings 500
  (prop/for-all [v gen-json]
                (= v (oda/parse (oda/write-bytes v)))))

(defspec write-parse-round-trip-keywords 500
  (prop/for-all [v gen-json-kw]
                (= v (oda/parse (oda/write-bytes v) {:key-fn keyword}))))

(defspec write-differential-jsonista-reads-ours 300
  (prop/for-all [v gen-json]
                (= v (j/read-value (oda/write-bytes v)))))

(deftest corpus-round-trip
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)]
          :when (.startsWith n "y_")
          :let [v (parse-bytes (Files/readAllBytes (.toPath f)))]]
    (testing n
      (is (= v (oda/parse (oda/write-bytes v))) n))))
