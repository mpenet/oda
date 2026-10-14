;; ABOUTME: Tests for oda: unit, JSONTestSuite conformance and generative tests.
;; ABOUTME: Differential checks against jsonista and the JDK number codecs.

(ns s-exp.oda-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
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

(deftest number-length-limit
  ;; BigInteger/BigDecimal parsing is superlinear: hostile documents with
  ;; huge numbers must fail fast instead of burning CPU
  (let [digits (fn [n] (apply str "1" (repeat (dec n) "0")))]
    (is (= (bigint (digits 1000)) (bigint (oda/parse (digits 1000)))))
    (is (thrown-with-msg? JsonParseException #"number exceeds max length"
                          (oda/parse (digits 1001))))
    (is (thrown? JsonParseException (oda/parse (str "[1," (digits 1001) "]"))))
    (is (thrown? JsonParseException (oda/parse (str "-0." (digits 999)))))
    (is (thrown? JsonParseException (oda/parse (digits 11) {:max-number-length 10})))
    (is (= 1234567890 (oda/parse "1234567890" {:max-number-length 10})))
    ;; explicit nil means default
    (is (thrown? JsonParseException (oda/parse (digits 1001) {:max-number-length nil})))
    ;; a 1MB number is rejected quickly rather than parsed for seconds
    (let [t0 (System/nanoTime)]
      (is (thrown? JsonParseException (oda/parse (digits 1000000))))
      (is (< (/ (- (System/nanoTime) t0) 1e6) 500)))))

(defn- colliding-strings
  "n distinct strings sharing one String.hashCode (\"Aa\" and \"BB\" collide)."
  [n]
  (->> (iterate #(for [a % b ["Aa" "BB"]] (str a b)) ["Aa" "BB"])
       (drop-while #(< (count %) n))
       first
       (take n)))

(defn- object-json [ks]
  (str "{" (str/join "," (map-indexed #(str "\"" %2 "\":" %1) ks)) "}"))

(deftest hash-collision-guard
  ;; keys sharing one hash degrade PersistentHashMap to quadratic time: a
  ;; large object with many of them is rejected before the cost explodes
  (is (thrown-with-msg? JsonParseException #"colliding"
                        (oda/parse (object-json (colliding-strings 100)))))
  (is (thrown? JsonParseException
               ;; a common prefix preserves the collisions
               (oda/parse (object-json (colliding-strings 100)) {:key-fn #(str "x" %)})))
  (let [json (object-json (colliding-strings 50000))
        t0 (System/nanoTime)]
    (is (thrown? JsonParseException (oda/parse json)))
    (is (< (/ (- (System/nanoTime) t0) 1e6) 200)))
  ;; small objects are never checked: their worst case is bounded
  (is (= 64 (count (oda/parse (object-json (colliding-strings 64))))))
  ;; a few legitimate collisions in a large object are fine
  (let [ks (concat (colliding-strings 16) (map #(str "k" %) (range 1000)))]
    (is (= 1016 (count (oda/parse (object-json ks))))))
  (is (= 10000 (count (oda/parse (object-json (map #(str "key" %) (range 10000)))))))
  ;; duplicates of one key are not distinct colliding keys
  (is (= {"a" 999} (oda/parse (object-json (repeat 1000 "a")))))
  (is (= {:k 999} (oda/parse (object-json (map #(str "k" %) (range 1000)))
                             {:key-fn (constantly :k)}))))

(deftest byte-order-mark
  ;; a leading UTF-8 BOM (common in files from Windows tools) is skipped
  (is (= {} (oda/parse (byte-array [0xEF 0xBB 0xBF 0x7B 0x7D]))))
  (is (= {"a" 1} (oda/parse "﻿{\"a\":1}")))
  (is (= [1] (oda/parse (ByteArrayInputStream. (byte-array [0xEF 0xBB 0xBF 0x5B 0x31 0x5D])))))
  ;; only at the very start
  (is (thrown? JsonParseException (oda/parse (byte-array [0x20 0xEF 0xBB 0xBF 0x7B 0x7D]))))
  (is (thrown? JsonParseException (oda/parse (byte-array [0xEF 0xBB 0xBF]))))
  (is (thrown? JsonParseException (oda/parse (byte-array [0xEF 0xBB 0x7B 0x7D])))))

(deftest bigdec-option
  (is (= 1.5M (oda/parse "1.5" {:bigdec true})))
  (is (instance? BigDecimal (oda/parse "1.5" {:bigdec true})))
  (is (= 100M (oda/parse "1e2" {:bigdec true})))
  (is (= -0.000123M (oda/parse "-1.23E-4" {:bigdec true})))
  ;; exactly the written digits, no binary rounding
  (is (= (BigDecimal. "0.1000000000000000055511151231257827")
         (oda/parse "0.1000000000000000055511151231257827" {:bigdec true})))
  ;; integers keep their usual representation
  (is (= 42 (oda/parse "42" {:bigdec true})))
  (is (instance? Long (oda/parse "42" {:bigdec true})))
  (is (= {"a" [1.5M 2]} (oda/parse "{\"a\":[1.5,2]}" {:bigdec true})))
  (is (instance? Double (oda/parse "1.5" {:bigdec false})))
  (is (thrown? JsonParseException
               (oda/parse (str "1." (apply str (repeat 1000 "1"))) {:bigdec true})))
  ;; exponents beyond BigDecimal's int scale are invalid input, not a crash
  (is (thrown-with-msg? JsonParseException #"out of range"
                        (oda/parse "0.4e99999999999" {:bigdec true})))
  (is (thrown? JsonParseException (oda/parse "[1e-99999999999]" {:bigdec true}))))

(defn- parse-error [s]
  (try (oda/parse s) nil (catch JsonParseException e e)))

(deftest error-positions
  (let [e (parse-error "{\n  \"a\": 1,\n  }")]
    (is (= {:offset 14 :line 3 :column 3} (ex-data e)))
    (is (= 14 (.-position ^JsonParseException e)))
    (is (= 3 (.-line ^JsonParseException e)))
    (is (= 3 (.-column ^JsonParseException e)))
    (is (re-find #"at line 3, column 3 \(offset 14\)" (ex-message e))))
  ;; columns count characters (code points), offsets count bytes
  (is (= {:offset 8 :line 1 :column 7} (ex-data (parse-error "[\"éé\",x]"))))
  (is (= {:offset 0 :line 1 :column 1} (ex-data (parse-error "x"))))
  (is (= {:offset 3 :line 2 :column 1} (ex-data (parse-error "1\r\nx"))))
  (is (= {:offset 0 :line 1 :column 1} (ex-data (parse-error "")))))

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

(deftest array-sizes
  ;; around the 32-element vector tail boundary and its multiples
  (doseq [n [0 1 31 32 33 64 65 1025]]
    (let [v (vec (range n))
          parsed (oda/parse (oda/write-str v))]
      (is (= v parsed) (str n))
      (is (vector? parsed) (str n)))))

(deftest strings-and-escapes
  (is (= "a\"b\\c/d\be\ff\ng\rh\ti" (oda/parse "\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"")))
  (is (= "é𝄞" (oda/parse "\"\\u00e9\\ud834\\udd1e\"")))
  (is (= "héllo🎵" (oda/parse "\"héllo🎵\"")))
  (is (= (str (char 0)) (oda/parse "\"\\u0000\"")))
  (is (= "" (oda/parse "\"\"")))
  (let [long-s (apply str (repeat 1000 "abcdefghij"))]
    (is (= long-s (oda/parse (str "\"" long-s "\""))))))

(def gen-string-content-bytes
  "Raw string-content bytes biased towards (often malformed) UTF-8:
  continuation bytes, every lead-byte class, and ASCII between them.
  Excludes '\"', '\\\\' and control bytes."
  (gen/fmap #(apply concat %)
            (gen/vector (gen/one-of [(gen/fmap vector (gen/elements (map int "aZ0 /")))
                                     (gen/fmap vector (gen/choose 0x80 0xbf))
                                     (gen/fmap vector (gen/choose 0xc0 0xdf))
                                     (gen/fmap vector (gen/choose 0xe0 0xef))
                                     (gen/fmap vector (gen/choose 0xf0 0xff))
                                     ;; well-formed é, €, 🎵 next to malformed bytes
                                     (gen/elements [[0xc3 0xa9] [0xe2 0x82 0xac]
                                                    [0xf0 0x9f 0x8e 0xb5]])])
                        0 12)))

(defspec malformed-utf8-decodes-same-with-or-without-escapes 3000
  ;; the escape-handling path must replace malformed UTF-8 exactly like
  ;; the clean fast path does
  (prop/for-all [content gen-string-content-bytes]
                (let [quoted (fn [prefix] (byte-array (concat [0x22] prefix content [0x22])))
                      clean (oda/parse (quoted []))
                      escaped (oda/parse (quoted [0x5c 0x6e]))]
                  (= (str "\n" clean) escaped))))

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
  ;; a var key-fn follows redefinition instead of serving cached results
  ;; of the fn it held when first used
  (let [v (intern *ns* (gensym "key-fn-") (fn [s] (keyword "x" s)))]
    (is (= {:x/a 1} (oda/parse "{\"a\":1}" {:key-fn v})))
    (alter-var-root v (constantly (fn [s] (keyword "y" s))))
    (is (= {:y/a 1} (oda/parse "{\"a\":1}" {:key-fn v})))
    (alter-var-root v (constantly keyword))
    (is (= {:a 1} (oda/parse "{\"a\":1}" {:key-fn v}))))
  ;; keywords are IFns doing lookup -> would silently produce nil keys
  (is (thrown? IllegalArgumentException (oda/parse "{\"a\":1}" {:key-fn :keywords}))))

(deftest duplicate-keys-last-wins
  (is (= {:a 2} (oda/parse "{\"a\":1,\"a\":2}" {:key-fn keyword})))
  (is (= {"a" 2} (oda/parse "{\"a\":1,\"a\":2}")))
  (let [json (str "{" (clojure.string/join "," (map (fn [i] (str "\"k" (mod i 3) "\":" i)) (range 12))) "}")]
    (is (= {:k0 9 :k1 10 :k2 11} (oda/parse json {:key-fn keyword})))))

(defn- static-field ^Object [^String class-name ^String field-name]
  (.get (doto (.getDeclaredField (Class/forName class-name) field-name)
          (.setAccessible true))
        nil))

(defn- cached-key-bytes
  "Key byte spans currently held by a KeyCache table."
  [^objects table]
  (let [bytes-field (doto (.getDeclaredField (Class/forName "com.s_exp.oda.KeyCache$Entry") "bytes")
                      (.setAccessible true))]
    (into #{}
          (comp (remove nil?) (map #(String. ^bytes (.get bytes-field %) "ISO-8859-1")))
          table)))

(deftest key-cache-bounds-retained-key-length
  ;; the global caches outlive parses: only short keys may be retained, or
  ;; hostile input could pin arbitrarily large keys in memory
  (let [short-k (str "short-" (apply str (repeat 58 "s")))
        long-k (str "long-" (apply str (repeat 60 "l")))
        json (str "{\"" short-k "\":1,\"" long-k "\":2}")
        custom-fn (fn [s] (str "x" s))]
    (is (= 64 (count short-k)))
    (is (= 65 (count long-k)))
    (is (= {short-k 1 long-k 2} (oda/parse json)))
    (is (= {(keyword short-k) 1 (keyword long-k) 2} (oda/parse json {:key-fn keyword})))
    (is (= {(custom-fn short-k) 1 (custom-fn long-k) 2} (oda/parse json {:key-fn custom-fn})))
    (doseq [table [(static-field "com.s_exp.oda.KeyCache" "STRINGS")
                   (static-field "com.s_exp.oda.KeyCache" "KEYWORDS")]]
      (is (contains? (cached-key-bytes table) short-k))
      (is (not (contains? (cached-key-bytes table) long-k))))))

(deftest depth-limit
  (let [deep (str (apply str (repeat 100 "[")) (apply str (repeat 100 "]")))]
    (is (= (oda/parse deep) (j/read-value deep)))
    (is (thrown? JsonParseException (oda/parse deep {:max-depth 50})))
    ;; explicit nil means default, like an absent key
    (is (= (oda/parse deep) (oda/parse deep {:max-depth nil})))))

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

(deftest writer-stream-does-not-flush
  ;; all bytes reach `out` before write returns; flushing its own buffering
  ;; is the caller's call (per-value flushes cost a packet each through
  ;; gzip or a socket)
  (let [flushes (atom 0)
        out (proxy [java.io.ByteArrayOutputStream] []
              (flush [] (swap! flushes inc)))]
    (oda/write {:a 1} out)
    (oda/write {:a 2} out)
    (is (zero? @flushes))
    (is (= "{\"a\":1}{\"a\":2}" (str out)))))

(defn- pooled-writer-buffer-sizes []
  (let [buf-field (doto (.getDeclaredField com.s_exp.oda.JsonWriter "buf")
                    (.setAccessible true))]
    (map #(alength ^bytes (.get buf-field %))
         (static-field "com.s_exp.oda.JsonWriter" "POOL"))))

(deftest writer-streaming-memory-bounded
  ;; a huge string must be encoded in chunks: the stream buffer may not grow
  ;; to the string's worst-case escaped size
  (let [^java.util.Collection pool (static-field "com.s_exp.oda.JsonWriter" "POOL")
        ^java.util.concurrent.atomic.AtomicInteger pool-size
        (static-field "com.s_exp.oda.JsonWriter" "POOL_SIZE")
        s (apply str (repeat 100000 "abcdé🎵\"\n"))]
    (.clear pool)
    (.set pool-size 0)
    (oda/write [s] (java.io.OutputStream/nullOutputStream))
    (is (seq (pooled-writer-buffer-sizes)))
    (is (every? #(<= % 65536) (pooled-writer-buffer-sizes)))))

(def gen-string-char
  "Chars across every encoder branch: clean ASCII, escapes, control chars,
  2- and 3-byte UTF-8, valid surrogate pairs and lone surrogates."
  (gen/one-of [(gen/fmap char (gen/choose 0x20 0x7e))
               (gen/elements [\" \\ \newline (char 0) (char 0x1f)])
               (gen/fmap char (gen/choose 0x80 0x7ff))
               (gen/fmap char (gen/choose 0x800 0xd7ff))
               (gen/return "🎵")
               (gen/fmap char (gen/choose 0xd800 0xdfff))]))

(defspec writer-long-string-differential 200
  ;; long strings cross the encoder's chunk boundaries, with surrogate pairs
  ;; and escapes landing on and around them
  (prop/for-all [parts (gen/vector gen-string-char 0 3000)
                 pad (gen/choose 8180 8200)]
                (let [s (str (apply str (repeat pad "a")) (apply str parts))
                      ;; lone surrogates have no UTF-8 encoding: written as U+FFFD
                      expected (str/replace s #"[\uD800-\uDFFF]" "�")]
                  (and (= expected (oda/parse (oda/write-str s)))
                       (= expected (oda/parse (oda/write-bytes s)))))))

(deftest writer-char-direct
  ;; Character path bypasses String.valueOf allocation; must match
  ;; String encoding on every char class
  (doseq [c [\a \0 \space \" \\ \newline \tab \backspace \return
             (char 0x1F) (char 0)
             \é (char 0x0800) (char 0xD800)]]
    (is (= (oda/write-str (str c)) (oda/write-str c)) (str "char " (int c)))))

(deftest writer-uuid-vs-tostring
  ;; UUID direct-encode must match java.util.UUID.toString exactly on a
  ;; wide range of bit patterns including corner cases
  (doseq [msb [0 -1 1 Long/MAX_VALUE Long/MIN_VALUE (unchecked-long 0x1234567890abcdef)]
          lsb [0 -1 1 (unchecked-long 0xfedcba0987654321) Long/MAX_VALUE]]
    (let [u (java.util.UUID. msb lsb)]
      (is (= (str "\"" u "\"") (oda/write-str u)))))
  (dotimes [_ 200]
    (let [u (random-uuid)]
      (is (= (str "\"" u "\"") (oda/write-str u))))))

(deftest writer-iso-instant-vs-formatter
  ;; zero-alloc ISO writer must match the DateTimeFormatter output exactly
  (let [f (.withZone (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
                     java.time.ZoneOffset/UTC)]
    (doseq [ms [0                          ; epoch
                1000                       ; 1970-01-01T00:00:01
                (- (* 365 86400 1000))     ; year 1969
                (* 1000 86400 400)         ; leap-day cycle boundary
                (long 1e12)
                (long 4e12)
                253402300799000            ; 9999-12-31T23:59:59
                -30610224000000]]          ; 1000-01-01T00:00:00
      (let [i (java.time.Instant/ofEpochMilli ms)
            expected (str "\"" (.format f i) "\"")]
        (is (= expected (oda/write-str (java.util.Date. (long ms)))) (str "Date " ms))
        (is (= expected (oda/write-str i)) (str "Instant " ms))))))

(defspec iso-instant-random-differential 500
  (prop/for-all [ms (gen/choose -62135596800000 253402300799000)]  ; 0000..9999 CE range
                (let [f (.withZone (java.time.format.DateTimeFormatter/ofPattern
                                    "yyyy-MM-dd'T'HH:mm:ss'Z'")
                                   java.time.ZoneOffset/UTC)
                      i (java.time.Instant/ofEpochMilli ms)
                      expected (str "\"" (.format f i) "\"")]
                  (= expected (oda/write-str i)))))

(deftest writer-default-fn-on-nan-inf
  ;; :default-fn must catch NaN/Infinity on both Double and Float
  (is (thrown? IllegalArgumentException (oda/write-str ##NaN)))
  (is (= "\"NaN\"" (oda/write-str ##NaN {:default-fn str})))
  (is (= "\"Infinity\"" (oda/write-str ##Inf {:default-fn str})))
  (is (= "\"-Infinity\"" (oda/write-str ##-Inf {:default-fn str})))
  (is (= "null" (oda/write-str ##NaN {:default-fn (constantly nil)})))
  ;; nested in a map — :default-fn fires inside dispatch
  (is (= "{\"a\":\"NaN\"}" (oda/write-str {:a ##NaN} {:default-fn str})))
  ;; Float NaN too
  (is (= "\"NaN\"" (oda/write-str (Float/valueOf Float/NaN) {:default-fn str})))
  (is (= "\"Infinity\"" (oda/write-str (Float/valueOf Float/POSITIVE_INFINITY)
                                       {:default-fn str}))))

(deftest writer-default-fn-must-return-writable
  ;; default-fn applies once per value: an unwritable result is an error,
  ;; not another round of default-fn (which would recurse forever)
  (is (thrown-with-msg? IllegalArgumentException #"default-fn returned"
                        (oda/write-str (Object.) {:default-fn identity})))
  (is (thrown-with-msg? IllegalArgumentException #"default-fn returned"
                        (oda/write-str ##NaN {:default-fn identity})))
  (is (thrown-with-msg? IllegalArgumentException #"default-fn returned"
                        (oda/write-str [(Object.)] {:default-fn (fn [_] (Object.))})))
  ;; values nested inside a default-fn result still get default-fn
  (let [outer (Object.)
        inner (Object.)]
    (is (= "{\"v\":\"x\"}"
           (oda/write-str outer {:default-fn #(if (identical? % outer) {:v inner} "x")}))))
  (is (= "[\"NaN\"]" (oda/write-str (Object.) {:default-fn #(if (double? %) (str %) [##NaN])}))))

(deftest writer-depth-limit
  ;; same convention as the parser: up to max-depth nested collections
  (let [nest (fn [n] (nth (iterate vector []) (dec n)))]
    (is (= (str (apply str (repeat 1000 "[")) (apply str (repeat 1000 "]")))
           (oda/write-str (nest 1000))))
    (is (thrown-with-msg? IllegalArgumentException #"max nesting depth"
                          (oda/write-str (nest 1001))))
    (is (thrown? IllegalArgumentException (oda/write-str (nest 11) {:max-depth 10})))
    (is (= "[[]]" (oda/write-str (nest 2) {:max-depth 2})))
    (is (thrown? IllegalArgumentException (oda/write-bytes (nest 3) {:max-depth 2})))
    (is (thrown? IllegalArgumentException
                 (oda/write (nest 3) (java.io.OutputStream/nullOutputStream) {:max-depth 2})))
    (is (thrown? IllegalArgumentException (oda/write-str {:a {:b {:c 1}}} {:max-depth 2})))
    (is (= "{\"a\":[1]}" (oda/write-str {:a [1]} {:max-depth nil}))))
  ;; self-similar or cyclic values fail cleanly instead of overflowing the stack
  (is (thrown-with-msg? IllegalArgumentException #"max nesting depth"
                        (oda/write-str (java.nio.file.Path/of "a" (into-array String ["b"])))))
  (let [cyclic (java.util.ArrayList.)]
    (.add cyclic cyclic)
    (is (thrown-with-msg? IllegalArgumentException #"max nesting depth"
                          (oda/write-str cyclic))))
  (let [cyclic (java.util.HashMap.)]
    (.put cyclic "self" cyclic)
    (is (thrown-with-msg? IllegalArgumentException #"max nesting depth"
                          (oda/write-str cyclic)))))

(deftest writer-key-fn
  ;; :key-fn transforms keys before dispatch
  (is (= "{\"a\":1}" (oda/write-str {:a 1} {:key-fn name})))
  (is (= "{\"A\":1,\"B\":2}"
         (oda/write-str {:a 1 :b 2} {:key-fn (comp str/upper-case name)})))
  ;; handles nil key — currently a nil key would throw
  (is (thrown? IllegalArgumentException (oda/write-str {nil 1})))
  (is (= "{\"__nil__\":1}"
         (oda/write-str {nil 1} {:key-fn #(if (nil? %) "__nil__" %)})))
  ;; identity fn — no change
  (is (= "{\"a\":1}" (oda/write-str {:a 1} {:key-fn identity})))
  ;; returning a keyword — normal keyword dispatch (via KwFrag cache)
  (is (= "{\"prefixed/x\":1}"
         (oda/write-str {:x 1} {:key-fn #(keyword "prefixed" (name %))})))
  ;; combined with :default-fn: key-fn fires first, unusual keys still fail
  ;; unless converted properly
  (is (= "{\"1\":\"NaN\"}"
         (oda/write-str {1 ##NaN} {:key-fn str :default-fn str}))))

(deftest writer-unrepresentable-number-keys
  ;; NaN/Infinity keys go through :default-fn, whose result must itself be
  ;; a valid key; it is never spliced raw between the key quotes
  (is (thrown? IllegalArgumentException (oda/write-str {##NaN 1})))
  (is (= "{\"NaN\":1}" (oda/write-str {##NaN 1} {:default-fn str})))
  (is (= "{\"-Infinity\":1}" (oda/write-str {##-Inf 1} {:default-fn str})))
  (is (= "{\"inf\":1}" (oda/write-str {(Float/valueOf Float/POSITIVE_INFINITY) 1}
                                      {:default-fn (constantly :inf)})))
  (is (= "{\"NaN\":1}" (oda/write-str {:a 1} {:key-fn (constantly ##NaN) :default-fn str})))
  (is (thrown? IllegalArgumentException
               (oda/write-str {##Inf 1} {:default-fn (fn [_] {:x 1})}))))

(deftest writer-dates
  (let [epoch (java.util.Date. 0)]
    (is (= "\"1970-01-01T00:00:00Z\"" (oda/write-str epoch)))
    (is (= "\"1970-01-01T00:00:00Z\"" (oda/write-str (java.time.Instant/EPOCH))))
    (is (= "\"1970-01-01T00:00:00Z\"" (oda/write-str (java.sql.Timestamp. 0))))
    (is (= "\"1970-01-01\"" (oda/write-str epoch {:date-format "yyyy-MM-dd"})))
    (is (= "\"1970/01/01 00:00\""
           (oda/write-str (java.time.Instant/EPOCH)
                          {:date-format (java.time.format.DateTimeFormatter/ofPattern
                                         "yyyy/MM/dd HH:mm")})))
    (is (= "{\"at\":\"1970-01-01T00:00:00Z\"}" (oda/write-str {:at epoch})))
    (is (thrown? IllegalArgumentException (oda/write-str epoch {:date-format 42})))))

(def java-time-values
  (let [ldt (java.time.LocalDateTime/of 2020 1 2 3 4 5 6000000)]
    [(java.time.LocalDate/of 2020 1 2)
     ldt
     (java.time.LocalDateTime/of 2020 1 2 3 4)
     (java.time.LocalTime/of 3 4 5)
     (java.time.LocalTime/of 3 4)
     (java.time.OffsetDateTime/of ldt (java.time.ZoneOffset/ofHours 2))
     (java.time.OffsetDateTime/of 2020 1 2 3 4 0 0 java.time.ZoneOffset/UTC)
     (java.time.ZonedDateTime/of ldt (java.time.ZoneId/of "Europe/Paris"))
     (java.time.ZonedDateTime/of 2020 1 2 3 4 0 0 (java.time.ZoneId/of "Europe/Paris"))
     (java.time.OffsetTime/of 3 4 5 0 java.time.ZoneOffset/UTC)
     (java.time.Period/of 1 2 3)
     (java.time.YearMonth/of 2020 1)
     (java.time.MonthDay/of 1 2)
     (java.time.ZoneId/of "Europe/Paris")
     (java.time.ZoneOffset/ofHours -5)]))

(deftest writer-java-time
  ;; ISO-8601 strings, identical to jsonista (Jackson JavaTimeModule)
  (doseq [v java-time-values]
    (is (= (j/write-value-as-string v) (oda/write-str v)) (pr-str v)))
  ;; Jackson writes durations as decimal seconds; ISO-8601 is kept instead
  (is (= "\"PT1H1M1S\"" (oda/write-str (java.time.Duration/ofSeconds 3661))))
  (is (= "\"PT0.5S\"" (oda/write-str (java.time.Duration/ofMillis 500))))
  ;; every time type carries seconds (Jackson drops zero seconds for OffsetTime only)
  (is (= "\"03:04:00Z\"" (oda/write-str (java.time.OffsetTime/of 3 4 0 0 java.time.ZoneOffset/UTC))))
  (is (= "[\"2020-01-02\"]" (oda/write-str [(java.time.LocalDate/of 2020 1 2)])))
  (is (= "\"2020-01-02T03:04:05.006+01:00\""
         (oda/write-str (java.time.ZonedDateTime/of 2020 1 2 3 4 5 6000000
                                                    (java.time.ZoneId/of "Europe/Paris"))))))

(deftest writer-enums
  ;; by name(), like Jackson; java.time enums too (they are also temporals)
  (doseq [v [java.util.concurrent.TimeUnit/SECONDS
             java.time.DayOfWeek/MONDAY
             java.time.Month/MARCH
             java.math.RoundingMode/HALF_EVEN
             [java.time.DayOfWeek/FRIDAY]
             {:unit java.util.concurrent.TimeUnit/DAYS}]]
    (is (= (j/write-value-as-string v) (oda/write-str v)) (pr-str v)))
  (is (= "\"SECONDS\"" (oda/write-str java.util.concurrent.TimeUnit/SECONDS)))
  (is (= "{\"MONDAY\":1}" (oda/write-str {java.time.DayOfWeek/MONDAY 1})))
  (is (= (j/write-value-as-string {java.time.Month/MAY 1})
         (oda/write-str {java.time.Month/MAY 1}))))

(deftest writer-map-key-types
  (let [u (random-uuid)]
    (is (= (str "{\"" u "\":1}") (oda/write-str {u 1}))))
  (is (= "{\"true\":1}" (oda/write-str {true 1})))
  (is (= "{\"false\":1}" (oda/write-str (java.util.Map/of false 1))))
  (is (= "{\"c\":1}" (oda/write-str {\c 1})))
  (is (= "{\"\\\"\":1}" (oda/write-str {\" 1})))
  (is (= "{\"é\":1}" (oda/write-str {\é 1})))
  ;; same as jsonista
  (is (= (j/write-value-as-string {true 1 \c 2}) (oda/write-str {true 1 \c 2}))))

(deftest writer-java-arrays
  (is (= "[1,\"a\",null]" (oda/write-str (object-array [1 "a" nil]))))
  (is (= "[[1,2],[]]" (oda/write-str (to-array [(long-array [1 2]) (int-array 0)]))))
  (is (= "[1,2]" (oda/write-str (int-array [1 2]))))
  (is (= "[1,2]" (oda/write-str (long-array [1 2]))))
  (is (= "[1,2]" (oda/write-str (short-array [1 2]))))
  (is (= "[1.5,2.0]" (oda/write-str (double-array [1.5 2]))))
  (is (= "[1.5,2.0]" (oda/write-str (float-array [1.5 2]))))
  (is (= "[true,false]" (oda/write-str (boolean-array [true false]))))
  (is (= "[\"a\",\"b\"]" (oda/write-str (into-array String ["a" "b"]))))
  (is (= "{\"a\":[1]}" (oda/write-str {:a (long-array [1])})))
  ;; like Jackson: byte[] as base64, char[] as a string
  (is (= "\"AQL/\"" (oda/write-str (byte-array [1 2 -1]))))
  (is (= "\"hé\\\"\"" (oda/write-str (char-array "hé\""))))
  (doseq [arr [(object-array [1 "a"]) (long-array [1 2]) (double-array [1.5])
               (byte-array [1 2 3]) (char-array "abc") (boolean-array [true])]]
    (is (= (j/write-value-as-string arr) (oda/write-str arr)) (str (class arr))))
  ;; non-finite array elements still go through default-fn
  (is (= "[\"NaN\"]" (oda/write-str (double-array [##NaN]) {:default-fn str})))
  (is (thrown? IllegalArgumentException (oda/write-str (double-array [##NaN]))))
  ;; arrays count towards the depth limit
  (is (thrown? IllegalArgumentException
               (oda/write-str (to-array [(to-array [1])]) {:max-depth 1}))))

(deftest writer-lone-surrogate
  ;; lone surrogates cannot be encoded as UTF-8; we emit U+FFFD
  (is (= "\"�\"" (oda/write-str (String. (char-array [(char 0xD800)]))))))

;; ------------------------------------------------------------------ encoders

(defrecord Localized [format-string args])

(defrecord Wrapped [v])

(deftest writer-encoders-override-built-in-types
  ;; records are maps, byte[] has a built-in base64 encoding: encoders win
  (let [encoders {Localized (fn [l] (apply format (:format-string l) (:args l)))
                  (Class/forName "[B") (fn [^bytes b] (str "0x" (count b)))}]
    (is (= "\"a 1\"" (oda/write-str (->Localized "a %s" [1]) {:encoders encoders})))
    (is (= "{\"m\":[\"a 1\",\"0x2\"]}"
           (oda/write-str {:m [(->Localized "a %s" [1]) (byte-array 2)]} {:encoders encoders})))
    (is (= "\"0x3\"" (String. (oda/write-bytes (byte-array 3) {:encoders encoders}) "UTF-8")))
    (let [out (java.io.ByteArrayOutputStream.)]
      (oda/write (object-array [(byte-array 1)]) out {:encoders encoders})
      (is (= "[\"0x1\"]" (String. (.toByteArray out) "UTF-8")))))
  ;; without encoders, records stay maps
  (is (= "{\"format-string\":\"a\",\"args\":[]}" (oda/write-str (->Localized "a" [])))))

(deftest writer-encoders-match-supertypes
  ;; an interface encoder covers every implementing class, including the
  ;; java.time types written natively otherwise
  (let [encoders {java.time.temporal.Temporal (fn [_] "T")}]
    (is (= "[\"T\",\"T\",\"T\"]"
           (oda/write-str [(java.time.LocalDate/of 2020 1 2) (java.time.Instant/EPOCH)
                           (java.time.OffsetDateTime/now)]
                          {:encoders encoders})))
    (is (= "\"1970-01-01T00:00:00Z\"" (oda/write-str (java.util.Date. 0) {:encoders encoders}))))
  ;; most specific wins: exact class, then superclass chain, then the most
  ;; specific interface, then Object
  (let [encoders {Number (fn [_] "number")
                  Long (fn [_] "long")
                  Comparable (fn [_] "comparable")}]
    ;; Object[] matches none of these, its elements do
    (is (= "[\"long\",\"number\",\"comparable\"]"
           (oda/write-str (object-array [1 (int 1) "s"]) {:encoders encoders}))))
  ;; Object comes last, and matches every value, containers included
  (let [encoders {Object (fn [_] "object") Comparable (fn [_] "comparable")}]
    (is (= "\"comparable\"" (oda/write-str "s" {:encoders encoders})))
    (is (= "\"object\"" (oda/write-str (Object.) {:encoders encoders})))
    (is (= "\"object\"" (oda/write-str {:a 1} {:encoders encoders}))))
  (let [encoders {java.util.Collection (fn [_] "collection")
                  java.util.List (fn [_] "list")}]
    (is (= "\"list\"" (oda/write-str [1] {:encoders encoders})))
    (is (= "\"collection\"" (oda/write-str #{1} {:encoders encoders})))))

(deftest writer-encoder-results
  ;; the result is written natively: it does not go through encoders again,
  ;; values nested inside it do
  (is (= "\"a!\"" (oda/write-str "a" {:encoders {String #(str % "!")}})))
  (is (= "{\"v\":{\"v\":1}}"
         (oda/write-str (->Wrapped (->Wrapped 1)) {:encoders {Wrapped (fn [w] {:v (:v w)})}})))
  (is (= "null" (oda/write-str (->Wrapped 1) {:encoders {Wrapped (constantly nil)}})))
  ;; a result that cannot be written natively goes to default-fn
  (is (= "\"x\"" (oda/write-str (->Wrapped 1) {:encoders {Wrapped (fn [_] (Object.))}
                                               :default-fn (constantly "x")})))
  (is (thrown? IllegalArgumentException
               (oda/write-str (->Wrapped 1) {:encoders {Wrapped (fn [_] (Object.))}}))))

(deftest writer-encoders-on-common-types
  ;; the common value types are matched like any other, through their
  ;; superclasses and interfaces too
  (is (= "[\"cs\",\"n\",\"n\",\"named\",\"cmp\"]"
         (oda/write-str (object-array ["s" 1 1.5 :k true])
                        {:encoders {CharSequence (fn [_] "cs")
                                    Number (fn [_] "n")
                                    clojure.lang.Named (fn [_] "named")
                                    Boolean (fn [_] "cmp")}})))
  (is (= "[\"s\",\"1\"]" (oda/write-str (object-array ["s" 1]) {:encoders {Comparable str}})))
  (is (= "[\"s\",1,\"x\"]" (oda/write-str ["s" 1 (->Wrapped 1)] {:encoders {Wrapped (fn [_] "x")}})))
  ;; and the common collections
  (let [encoders {clojure.lang.IPersistentMap (fn [_] "m") java.util.RandomAccess (fn [_] "v")}]
    (is (= "\"v\"" (oda/write-str [{:a 1}] {:encoders encoders})))
    (is (= "[\"m\",\"m\"]"
           (oda/write-str (object-array [{:a 1} (into {} (map vector (range 20) (range 20)))])
                          {:encoders encoders})))))

(deftest writer-encoders-skip-map-keys
  ;; keys go through key-fn only
  (is (= "{\"1\":\"long\"}" (oda/write-str {1 2} {:encoders {Long (fn [_] "long")}})))
  (is (= "{\"a\":\"string\"}" (oda/write-str {"a" "b"} {:encoders {String (fn [_] "string")}}))))

(deftest writer-encoders-primitive-array-elements-are-written-directly
  (is (= "[1.5]" (oda/write-str (float-array [1.5]) {:encoders {Float (fn [_] "float")}})))
  (is (= "[1]" (oda/write-str (long-array [1]) {:encoders {Long (fn [_] "long")}}))))

(deftest writer-encoders-validation
  (is (thrown? IllegalArgumentException (oda/write-str 1 {:encoders {"Long" str}})))
  (is (thrown? IllegalArgumentException (oda/write-str 1 {:encoders {Long "str"}}))))

(deftest writer-encoders-resolved-per-map
  ;; resolutions are cached per encoders map: a different map never sees
  ;; another map's resolutions
  (let [a {Wrapped (fn [_] "a")}
        b {Wrapped (fn [_] "b")}]
    (dotimes [_ 3]
      (is (= "\"a\"" (oda/write-str (->Wrapped 1) {:encoders a})))
      (is (= "\"b\"" (oda/write-str (->Wrapped 1) {:encoders b})))
      (is (= "{\"v\":1}" (oda/write-str (->Wrapped 1)))))))

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

;; --------------------------------------------------------------- concurrency

(defn- run-concurrently
  "Runs (f thread-index) on n threads released together; returns results."
  [n f]
  (let [start (java.util.concurrent.CountDownLatch. 1)
        futs (mapv (fn [i] (future (.await start) (f i))) (range n))]
    (.countDown start)
    (mapv deref futs)))

(deftest writer-encoders-under-contention
  ;; many classes resolved concurrently into one shared map's cache
  (let [classes [Long Integer Short Byte Double Float String clojure.lang.Keyword
                 clojure.lang.Symbol java.util.UUID Boolean Character]
        encoders (into {} (map (fn [c] [c (fn [_] (.getSimpleName ^Class c))])) classes)
        values (object-array [1 (int 1) (short 1) (byte 1) 1.5 (float 1.5) "s" :k 's
                              (random-uuid) true \c])
        expected (oda/write-str (mapv #(.getSimpleName (class %)) values))]
    (is (every? #(= #{expected} %)
                (run-concurrently 16 (fn [_]
                                       (into #{} (repeatedly 200 #(oda/write-str values {:encoders encoders}))))))
        "every thread sees the same, correct, result")))

(defn- upper-keys [m]
  (walk/postwalk #(if (map? %) (update-keys % str/upper-case) %) m))

(def shared-upper-key-fn
  "One shared custom key-fn: every thread hits the same identity-keyed table."
  (fn [s] (str/upper-case s)))

(deftest shared-caches-under-contention
  ;; 20k distinct keys over 8192-slot lossy tables: slots are overwritten
  ;; constantly while other threads read them. Racy reads must never
  ;; return a wrong key or a torn entry.
  (let [docs (vec (for [d (range 200)]
                    (j/write-value-as-bytes
                     (into {} (map (fn [k] [(str "key-" d "-" k "-" (mod (* d k) 97))
                                            [k (str "v" k) (keyword (str "kv" (mod k 50)))]]))
                           (range 100)))))
        expected-str (mapv j/read-value docs)
        expected-kw (mapv #(j/read-value % j/keyword-keys-object-mapper) docs)
        expected-upper (mapv upper-keys expected-str)
        values (mapv #(update-keys % keyword) expected-str)
        expected-writes (mapv oda/write-str values)
        results (run-concurrently
                 16
                 (fn [t]
                   (every? true?
                           (for [round (range 3)
                                 i (range (count docs))
                                 :let [i (mod (+ i (* t 13) round) (count docs))
                                       bs (docs i)]]
                             (and (= (expected-str i) (oda/parse bs))
                                  (= (expected-kw i) (oda/parse bs {:key-fn keyword}))
                                  (= (expected-upper i) (oda/parse bs {:key-fn shared-upper-key-fn}))
                                  (= (expected-writes i) (oda/write-str (values i)))
                                  (= (expected-writes i) (String. ^bytes (oda/write-bytes (values i)) "UTF-8")))))))]
    (is (every? true? results))))

(deftest custom-key-fn-tables-never-shared
  ;; per-fn key tables are found by fn identity: closures of one fn class
  ;; with different captured values must never see each other's results,
  ;; also when created and dropped concurrently
  (let [doc (.getBytes "{\"a\":1,\"b\":{\"c\":2}}" "UTF-8")
        results (run-concurrently
                 8
                 (fn [t]
                   (every? true?
                           (for [i (range 2000)
                                 :let [prefix (str t "-" i "-")
                                       key-fn (fn [s] (str prefix s))]]
                             (= {(str prefix "a") 1 (str prefix "b") {(str prefix "c") 2}}
                                (oda/parse doc {:key-fn key-fn}))))))]
    (is (every? true? results))))

;; ------------------------------------------------------------------- fuzzing

(def fuzz-seeds
  "Suite documents plus synthetic ones reaching the special paths: long
  strings (SIMD), keys around the 64-byte cache cap, escapes, raw and
  malformed UTF-8, every number shape, deep nesting."
  (let [long-key (apply str (repeat 70 "k"))
        mid-key (apply str (repeat 40 "m"))
        long-s (apply str (repeat 50 "abcdefgh"))]
    (into (mapv #(Files/readAllBytes (.toPath ^java.io.File %)) (suite-files))
          (map #(.getBytes ^String % "UTF-8"))
          [(str "{\"" long-key "\":1,\"" mid-key "\":[1,2],\"a\":{\"" long-key "\":null}}")
           (str "[\"" long-s "\",\"" long-s "\\n" long-s "é🎵\",\"\\ud834\\udd1e\"]")
           "[0,-0,1.5e10,-2.5E-3,123456789012345678901234567890,1e400,0.1,9007199254740993]"
           (str (apply str (repeat 40 "[{\"a\":")) "1" (apply str (repeat 40 "}]")))
           "{\"a\":\"\\u0000\\u001f\\\"\\\\\\/\\b\\f\\n\\r\\t\",\"é\":true,\"\":false}"])))

(def gen-mutated-doc
  "A seed document with a few random byte flips, insertions, deletions,
  or a truncation."
  (gen/let [seed (gen/elements fuzz-seeds)
            muts (gen/vector (gen/tuple (gen/elements [:flip :insert :delete :truncate])
                                        gen/nat
                                        (gen/choose 0 255))
                             1 4)]
    (byte-array
     (reduce (fn [bs [op at b]]
               (let [n (count bs)
                     i (if (pos? n) (mod at n) 0)]
                 (case op
                   :flip (if (pos? n) (assoc bs i (unchecked-byte b)) bs)
                   :insert (into (conj (subvec bs 0 i) (unchecked-byte b)) (subvec bs i))
                   :delete (if (pos? n) (into (subvec bs 0 i) (subvec bs (inc i))) bs)
                   :truncate (subvec bs 0 i))))
             (vec seed)
             muts))))

(def fuzz-opts
  [nil {:key-fn keyword} {:key-fn str/upper-case} {:bigdec true}])

(defn- parses-or-rejects?
  "True when parsing either succeeds or fails with JsonParseException; any
  other throwable (index out of bounds, NPE, stack overflow...) is a bug."
  [^bytes bs opts]
  (try
    (oda/parse bs opts)
    true
    (catch JsonParseException _ true)
    (catch Throwable t
      (println "unexpected" (class t) (ex-message t) "for" (pr-str (String. bs "UTF-8")) opts)
      false)))

(defspec fuzz-random-bytes 2000
  (prop/for-all [bs gen/bytes
                 opts (gen/elements fuzz-opts)]
                (parses-or-rejects? bs opts)))

(defspec fuzz-mutated-documents 5000
  (prop/for-all [bs gen-mutated-doc
                 opts (gen/elements fuzz-opts)]
                (parses-or-rejects? bs opts)))

(defn- valid-utf8? [^bytes bs]
  (try
    (.decode (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
             (java.nio.ByteBuffer/wrap bs))
    true
    (catch java.nio.charset.CharacterCodingException _ false)))

(defspec fuzz-mutated-documents-differential 3000
  ;; whenever both oda and jsonista accept a mutated, well-formed UTF-8
  ;; document, they agree. Malformed UTF-8 is excluded: Jackson decodes
  ;; overlong forms (C0 AF -> "/") and out-of-range sequences leniently,
  ;; where oda replaces them with U+FFFD as Unicode requires.
  (prop/for-all [bs gen-mutated-doc]
                (let [ours (try {:ok (oda/parse bs)} (catch JsonParseException _ nil))
                      theirs (try {:ok (j/read-value bs)} (catch Exception _ nil))]
                  (or (nil? ours) (nil? theirs) (not (valid-utf8? bs)) (= ours theirs)
                      (println "mismatch on" (mapv #(format "%02x" (bit-and % 0xff)) bs)
                               "ours" (pr-str ours) "jsonista" (pr-str theirs))))))

;; ---------------------------------------------------------- incremental reads

(defn- trickle-stream
  "InputStream handing out at most `chunk` bytes per read, like a socket."
  ^java.io.InputStream [^bytes bs chunk]
  (let [pos (volatile! 0)]
    (proxy [java.io.InputStream] []
      (read
        ([] (let [p (int @pos)]
              (if (< p (alength bs))
                (do (vswap! pos inc) (bit-and (aget bs p) 0xff))
                -1)))
        ([^bytes b off len]
         (let [p (int @pos)
               n (min (int len) (int chunk) (- (alength bs) p))]
           (cond
             (zero? (int len)) 0
             (<= n 0) -1
             :else (do (System/arraycopy bs p b (int off) n)
                       (vswap! pos + n)
                       n))))))))

(defn- trickle-reader
  "Reader handing out at most `chunk` chars per read."
  ^java.io.Reader [^String s chunk]
  (let [pos (volatile! 0)]
    (proxy [java.io.Reader] []
      ;; proxy replaces every arity of read: route them all to the chunked one
      (read
        ([] (let [a (char-array 1)]
              (if (pos? (.read ^java.io.Reader this a 0 1)) (int (aget a 0)) -1)))
        ([^java.nio.CharBuffer cb]
         (let [a (char-array (max 1 (.remaining cb)))
               n (.read ^java.io.Reader this a 0 (alength a))]
           (when (pos? n) (.put cb a 0 n))
           n))
        ([^chars cbuf off len]
         (let [p (int @pos)
               n (min (int len) (int chunk) (- (count s) p))]
           (cond
             (zero? (int len)) 0
             (<= n 0) -1
             :else (do (.getChars s p (+ p n) cbuf (int off))
                       (vswap! pos + n)
                       n)))))
      (close []))))

(defn- read-all
  "Reads values until eof through a fresh oda reader over `src`."
  [src opts]
  (with-open [r (oda/reader src opts)]
    (into [] (take-while #(not= ::eof %)) (repeatedly #(oda/read r {:eof-value ::eof})))))

(deftest reader-sketch
  (is (= [{:a 1} {:a 2} ::eof]
         (with-open [reader (oda/reader (java.io.StringReader. "{\"a\":1}\n{\"a\":2}")
                                        {:key-fn keyword})]
           [(oda/read reader)
            (oda/read reader)
            (oda/read reader {:eof-value ::eof})]))))

(deftest reader-values-and-separators
  (let [src "{\"a\":[1,2]} [3]\"s\"\t42\n-1.5e3 true false null{}[]\r\n\"\\\"}\" 7"]
    (doseq [chunk [1 2 3 1000]
            input [(trickle-stream (.getBytes src "UTF-8") chunk)
                   (trickle-reader src chunk)]]
      (is (= [{"a" [1 2]} [3] "s" 42 -1500.0 true false nil {} [] "\"}" 7]
             (read-all input nil))
          (str chunk " " (class input)))))
  (is (= [] (read-all (java.io.StringReader. "") nil)))
  (is (= [] (read-all (java.io.StringReader. " \n\t ") nil)))
  ;; options apply to every value
  (is (= [{:a 1.5M} {:b 2}] (read-all (java.io.StringReader. "{\"a\":1.5}{\"b\":2}")
                                      {:key-fn keyword :bigdec true}))))

(deftest reader-non-ascii-across-chunk-boundaries
  (let [s "[\"é🎵€\",\"\\u00e9\\ud834\\udd1e\"] {\"ключ\":\"值\"} \"🎵\""
        expected [["é🎵€" "é𝄞"] {"ключ" "值"} "🎵"]]
    (doseq [chunk [1 2 3 5]]
      (is (= expected (read-all (trickle-reader s chunk) nil)) (str "reader " chunk))
      (is (= expected (read-all (trickle-stream (.getBytes s "UTF-8") chunk) nil))
          (str "stream " chunk))))
  ;; lone surrogates from a Reader become U+FFFD, like the writer
  (is (= ["a�b"] (read-all (java.io.StringReader. (str "\"a" (char 0xD800) "b\"")) nil))))

(deftest reader-eof
  (with-open [r (oda/reader (java.io.StringReader. "1 "))]
    (is (= 1 (oda/read r)))
    (is (thrown? java.io.EOFException (oda/read r)))
    (is (nil? (oda/read r {:eof-value nil})))
    (is (= ::eof (oda/read r {:eof-value ::eof}))))
  ;; a value cut off by eof is invalid input, not a clean eof
  (doseq [s ["{\"a\":1" "[1," "\"abc" "tru" "-" "{\"a\":1}{"]]
    (is (thrown? JsonParseException (read-all (java.io.StringReader. s) nil)) s)))

(deftest reader-does-not-wait-for-eof
  (let [out (java.io.PipedOutputStream.)
        in (java.io.PipedInputStream. out)
        r (oda/reader in {:key-fn keyword})
        write! #(do (.write out (.getBytes ^String % "UTF-8")) (.flush out))]
    (write! "{\"a\":1}")
    (is (= {:a 1} (deref (future (oda/read r)) 2000 ::timeout)))
    (write! "[2] \"three\"")
    (is (= [2] (deref (future (oda/read r)) 2000 ::timeout)))
    (is (= "three" (deref (future (oda/read r)) 2000 ::timeout)))
    ;; a top-level number needs one byte of lookahead to know it ended
    (write! " 42")
    (let [pending (future (oda/read r))]
      (is (= ::timeout (deref pending 300 ::timeout)))
      (write! "\n")
      (is (= 42 (deref pending 2000 ::timeout))))
    (.close out)
    (is (= ::eof (deref (future (oda/read r {:eof-value ::eof})) 2000 ::timeout)))))

(deftest reader-errors
  ;; positions count from the start of the stream, not of the value
  (let [e (try (read-all (java.io.StringReader. "{\"a\":1}\n{\"a\" 1}") nil)
               (catch JsonParseException e e))]
    (is (= {:offset 13 :line 2 :column 6} (ex-data e)))
    (is (re-find #"expected ':' at line 2, column 6 \(offset 13\)" (ex-message e))))
  ;; also far into a stream, after the reader's buffer has been recycled
  (let [lines (str (str/join "\n" (repeat 20000 "{\"k\":\"vvvvvvvvvvvvvvvvvvvvvv\"}")) "\n  x")
        e (try (read-all (trickle-stream (.getBytes lines "UTF-8") 4096) nil)
               (catch JsonParseException e e))]
    (is (= {:offset (- (count lines) 1) :line 20001 :column 3} (ex-data e))))
  ;; garbage between values fails on the read that reaches it
  (with-open [r (oda/reader (java.io.StringReader. "{} x {}"))]
    (is (= {} (oda/read r)))
    (is (thrown? JsonParseException (oda/read r))))
  (is (thrown? IllegalArgumentException (oda/reader "not a stream"))))

(deftest reader-hostile-input
  ;; endless nesting fails at max-depth instead of buffering forever
  (let [endless (proxy [java.io.InputStream] []
                  (read ([] (int \[))
                    ([^bytes b off len]
                     (java.util.Arrays/fill b (int off) (+ (int off) (int len)) (byte (int \[)))
                     len)))]
    (is (thrown-with-msg? JsonParseException #"max nesting depth"
                          (oda/read (oda/reader endless {:max-depth 100}))))))

(deftest reader-byte-order-mark
  (is (= [{} [1]] (read-all (java.io.ByteArrayInputStream.
                             (byte-array (concat [0xEF 0xBB 0xBF] (.getBytes "{} [1]" "UTF-8"))))
                            nil))))

(deftest reader-close-closes-source
  (let [closed (atom false)
        src (proxy [java.io.StringReader] ["{}"]
              (close [] (reset! closed true)))]
    (with-open [r (oda/reader src)]
      (is (= {} (oda/read r))))
    (is @closed)))

(def gen-separator
  (gen/elements [" " "\n" "\t" "\r\n" "  \n  "]))

(defn- expected-position
  "{:offset :line :column} of the end of string s, computed the slow way."
  [^String s]
  (let [last-nl (.lastIndexOf s "\n")
        tail (subs s (inc last-nl))]
    {:offset (alength (.getBytes s "UTF-8"))
     :line (inc (count (filter #{\newline} s)))
     :column (inc (.codePointCount tail 0 (count tail)))}))

(defspec reader-error-positions-across-buffer-refills 100
  ;; positions of an error after many values (beyond the 8KB buffer, so
  ;; consumed input is dropped and counted 8 bytes at a time) match the
  ;; reference count, with multi-byte chars and newlines anywhere
  (prop/for-all [vs (gen/vector (gen/one-of [gen-json (gen/elements ["é🎵\n€" "ключ" "\n\n"])])
                                1 30)
                 pretty (gen/vector gen/boolean 30)
                 seps (gen/vector gen-separator 30)
                 chunk (gen/choose 1 4096)]
                (let [pretty-mapper (j/object-mapper {:pretty true})
                      pad (j/write-value-as-string (vec (repeat 400 "pad é🎵 pad\n")) pretty-mapper)
                      docs (cons pad (map (fn [v p] (if p (j/write-value-as-string v pretty-mapper)
                                                        (j/write-value-as-string v)))
                                          vs pretty))
                      prefix (str (apply str (interleave docs seps)) " \"é🎵\" ")
                      e (try (read-all (trickle-stream (.getBytes (str prefix "x") "UTF-8") chunk) nil)
                             nil
                             (catch JsonParseException e e))]
                  (= (expected-position prefix) (ex-data e)))))

(defspec reader-equals-parse-per-document 300
  ;; documents written compact or pretty, separated by whitespace and read
  ;; through chunks of any size equal parsing each document on its own
  (prop/for-all [vs (gen/vector gen-json 0 8)
                 pretty (gen/vector gen/boolean 8)
                 seps (gen/vector gen-separator 8)
                 chunk (gen/choose 1 9)]
                (let [pretty-mapper (j/object-mapper {:pretty true})
                      docs (map (fn [v p] (if p (j/write-value-as-string v pretty-mapper)
                                              (j/write-value-as-string v)))
                                vs pretty)
                      src (apply str (interleave docs seps))]
                  (= (mapv oda/parse docs)
                     (read-all (trickle-stream (.getBytes ^String src "UTF-8") chunk) nil)
                     (read-all (trickle-reader src chunk) nil)))))

(defspec fuzz-reader-mutated-streams 3000
  ;; the incremental reader over mutated input, in odd-sized chunks: values
  ;; or JsonParseException, nothing else; and when parse accepts the whole
  ;; document, reading it as a stream yields exactly that one value
  (prop/for-all [bs gen-mutated-doc
                 chunk (gen/choose 1 7)
                 opts (gen/elements fuzz-opts)]
                (let [streamed (try {:ok (read-all (trickle-stream bs chunk) opts)}
                                    (catch JsonParseException _ :rejected)
                                    (catch Throwable t
                                      (println "unexpected" (class t) (ex-message t)
                                               (mapv #(format "%02x" (bit-and % 0xff)) bs))
                                      :bug))
                      parsed (try {:ok [(oda/parse bs opts)]} (catch JsonParseException _ nil))]
                  (and (not= :bug streamed)
                       (or (nil? parsed) (= parsed streamed))))))

(deftest corpus-round-trip
  (doseq [^java.io.File f (suite-files)
          :let [n (.getName f)]
          :when (.startsWith n "y_")
          :let [v (parse-bytes (Files/readAllBytes (.toPath f)))]]
    (testing n
      (is (= v (oda/parse (oda/write-bytes v))) n))))
