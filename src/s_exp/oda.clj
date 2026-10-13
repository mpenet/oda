;; ABOUTME: Public Clojure API of oda: parse JSON, write JSON.
;; ABOUTME: Thin option-handling layer over the Java JsonParser and JsonWriter.

(ns s-exp.oda
  "Fast JSON parser/writer working directly on UTF-8 bytes."
  (:import (clojure.lang IFn)
           (com.s_exp.oda JsonParser JsonWriter)
           (java.io InputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def ^:private default-max-depth 1000)
(def ^:private default-max-number-length 1000)

(defn parse
  "Parses JSON from `input` (byte-array, String or InputStream) into Clojure
  data structures. A leading UTF-8 byte order mark is skipped.

  Options:
  * `:key-fn`            - fn of String -> map key, applied to object keys.
    Unset or nil leaves keys as strings. `clojure.core/keyword` is recognized
    and uses an optimized interning path. Must be pure: results are cached by
    fn identity, so a def'd fn gets cross-parse caching while a fresh lambda
    is only cached within a single parse
  * `:max-depth`         - maximum nesting depth (default 1000)
  * `:max-number-length` - maximum length of a number in chars (default
    1000): converting huge numbers is superlinear
  * `:bigdec`            - read non-integer numbers as exact BigDecimals
    instead of doubles (default false)

  Throws `com.s_exp.oda.JsonParseException` on invalid input; its `ex-data`
  is `{:offset :line :column}` (byte offset, 1-based line and column)."
  ([input]
   (parse input nil))
  ([input {:keys [key-fn max-depth max-number-length bigdec]}]
   (let [^bytes bs (cond
                     (bytes? input) input
                     (string? input) (.getBytes ^String input StandardCharsets/UTF_8)
                     (instance? InputStream input) (.readAllBytes ^InputStream input)
                     :else (throw (IllegalArgumentException.
                                   (str "Unsupported input type: " (some-> input class .getName)))))]
     (JsonParser/parse bs 0 (alength bs) ^IFn key-fn
                       (int (or max-depth default-max-depth))
                       (int (or max-number-length default-max-number-length))
                       (boolean bigdec)))))

(defn write-str
  "Writes `x` as a JSON String.

  Options:
  * `:default-fn`  - called on values that cannot be written natively — both
    unsupported types AND supported types with unrepresentable values
    (NaN/Infinity Float/Double, also as map keys). Must return a natively
    writable value: it is not applied again to its own result (values nested
    inside the result do get it). Without it, those cases throw.
  * `:key-fn`      - transforms each map key before dispatch. Use to handle
    nil keys, custom types, or normalize keys. Takes the key, returns any
    writable key (String, Keyword, Symbol, Number, UUID, Boolean, Character).
  * `:date-format` - format for java.util.Date and java.time.Instant values:
    a pattern string or a java.time.format.DateTimeFormatter (zone-less
    formatters default to UTC). Default: yyyy-MM-dd'T'HH:mm:ss'Z' at UTC.
    Other java.time values are written as ISO-8601 strings.
  * `:max-depth`   - maximum nesting depth (default 1000); deeper or cyclic
    values throw instead of overflowing the stack."
  (^String [x]
   (JsonWriter/writeString x nil nil nil default-max-depth))
  (^String [x {:keys [default-fn key-fn date-format max-depth]}]
   (JsonWriter/writeString x ^IFn default-fn ^IFn key-fn date-format
                           (int (or max-depth default-max-depth)))))

(defn write-bytes
  "Writes `x` as JSON UTF-8 encoded byte-array. See `write-str` for options."
  (^bytes [x]
   (JsonWriter/writeBytes x nil nil nil default-max-depth))
  (^bytes [x {:keys [default-fn key-fn date-format max-depth]}]
   (JsonWriter/writeBytes x ^IFn default-fn ^IFn key-fn date-format
                          (int (or max-depth default-max-depth)))))

(defn write
  "Writes `x` as JSON to `out` (OutputStream), flushes, does not close.
  See `write-str` for options."
  ([x ^OutputStream out]
   (JsonWriter/writeStream x nil nil nil default-max-depth out))
  ([x ^OutputStream out {:keys [default-fn key-fn date-format max-depth]}]
   (JsonWriter/writeStream x ^IFn default-fn ^IFn key-fn date-format
                           (int (or max-depth default-max-depth)) out)))
