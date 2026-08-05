(ns s-exp.oda
  "Fast JSON parser/writer working directly on UTF-8 bytes."
  (:import (clojure.lang IFn)
           (com.s_exp.oda JsonParser JsonWriter)
           (java.io InputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn parse
  "Parses JSON from `input` (byte-array, String or InputStream) into Clojure
  data structures.

  Options:
  * `:key-fn`    - fn of String -> map key, applied to object keys. Unset or
    nil leaves keys as strings. `clojure.core/keyword` is recognized and uses
    an optimized interning path. Must be pure: results are cached by fn
    identity, so a def'd fn gets cross-parse caching while a fresh lambda is
    only cached within a single parse
  * `:max-depth` - maximum nesting depth (default 1000)

  Throws `com.s_exp.oda.JsonParseException` on invalid input."
  ([input]
   (parse input nil))
  ([input {:keys [key-fn max-depth]
           :or {max-depth 1000}}]
   (let [^bytes bs (cond
                     (bytes? input) input
                     (string? input) (.getBytes ^String input StandardCharsets/UTF_8)
                     (instance? InputStream input) (.readAllBytes ^InputStream input)
                     :else (throw (IllegalArgumentException.
                                   (str "Unsupported input type: " (some-> input class .getName)))))]
     (JsonParser/parse bs 0 (alength bs) ^IFn key-fn (int max-depth)))))

(defn write-str
  "Writes `x` as a JSON String.

  Options:
  * `:default-fn`  - called on values of unsupported types, must return a
    writable value. Without it, unsupported types throw.
  * `:date-format` - format for java.util.Date and java.time.Instant values:
    a pattern string or a java.time.format.DateTimeFormatter (zone-less
    formatters default to UTC). Default: yyyy-MM-dd'T'HH:mm:ss'Z' at UTC."
  (^String [x]
   (JsonWriter/writeString x nil nil))
  (^String [x {:keys [default-fn date-format]}]
   (JsonWriter/writeString x ^IFn default-fn date-format)))

(defn write-bytes
  "Writes `x` as JSON UTF-8 encoded byte-array. See `write-str` for options."
  (^bytes [x]
   (JsonWriter/writeBytes x nil nil))
  (^bytes [x {:keys [default-fn date-format]}]
   (JsonWriter/writeBytes x ^IFn default-fn date-format)))

(defn write
  "Writes `x` as JSON to `out` (OutputStream), flushes, does not close.
  See `write-str` for options."
  ([x ^OutputStream out]
   (JsonWriter/writeStream x nil nil out))
  ([x ^OutputStream out {:keys [default-fn date-format]}]
   (JsonWriter/writeStream x ^IFn default-fn date-format out)))
