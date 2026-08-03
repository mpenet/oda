(ns s-exp.jzon
  "Fast JSON parser building Clojure data structures directly from UTF-8 bytes."
  (:import (com.s_exp.jzon JsonParser)
           (java.io InputStream)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn parse
  "Parses JSON from `input` (byte-array, String or InputStream) into Clojure
  data structures.

  Options:
  * `:keywordize` - convert object keys to keywords (default true)
  * `:max-depth`  - maximum nesting depth (default 1000)

  Throws `com.s_exp.jzon.JsonParseException` on invalid input."
  ([input]
   (parse input nil))
  ([input {:keys [keywordize max-depth]
           :or {keywordize true max-depth 1000}}]
   (let [^bytes bs (cond
                     (bytes? input) input
                     (string? input) (.getBytes ^String input StandardCharsets/UTF_8)
                     (instance? InputStream input) (.readAllBytes ^InputStream input)
                     :else (throw (IllegalArgumentException.
                                   (str "Unsupported input type: " (some-> input class .getName)))))]
     (JsonParser/parse bs 0 (alength bs) (boolean keywordize) (int max-depth)))))
