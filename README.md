# oda

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)

Fast JSON parser/writer for Clojure. Zero dependencies, JDK 25+.

oda works directly on UTF-8 bytes and builds Clojure persistent data
structures without intermediate representations. It is **faster than
Jackson-backed libraries** (jsonista, cheshire) across typical workloads, on
both read and write, with **optional SIMD string scanning via the Vector API**
(`--add-modules jdk.incubator.vector`) for an extra boost on string-heavy
documents — see [Optional SIMD](#optional-simd).

> Status: alpha. API may still move.

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)

Requires JDK 25+. The jar ships the compiled Java core; when working from a
git checkout instead, compile it once with `clj -T:build javac`.

## Usage

```clojure
(require '[s-exp.oda :as oda])

;; parse: byte-array, String or InputStream
(oda/parse "{\"a\":1,\"b\":[1.5,true,null]}")
;; => {"a" 1, "b" [1.5 true nil]}

(oda/parse "{\"a\":1}" {:key-fn keyword})
;; => {:a 1}

(oda/parse "{\"first-name\":\"Ada\"}"
           {:key-fn #(keyword (clojure.string/replace % "-" "_"))})
;; => {:first_name "Ada"}

;; write
(oda/write-str {:a 1 :b [1.5 true nil]})
;; => "{\"a\":1,\"b\":[1.5,true,null]}"

(oda/write-bytes {:a 1})            ;; => byte[]
(oda/write {:a 1} output-stream)    ;; progressive, bounded memory
```

### Options

`parse`:

| option | default | |
|---|---|---|
| `:key-fn` | `nil` | fn of `String -> key` applied to object keys; `nil` keeps strings. `clojure.core/keyword` is recognized and takes an optimized interning path. Must be pure: results are cached by fn identity |
| `:max-depth` | `1000` | maximum nesting depth (stack-overflow/DoS guard) |

`write-str` / `write-bytes` / `write`:

| option | default | |
|---|---|---|
| `:default-fn` | `nil` | called on values of unsupported types, must return a writable value; without it unsupported types throw |

### Supported types (write)

Maps (keys: keyword, string, symbol, number), vectors, sets, seqs,
`java.util.Map`/`Iterable`, strings, keywords, symbols, chars, UUIDs, all
JVM numbers (Ratio written as double), booleans, nil. NaN/Infinity throw.

## Performance

Criterium means, Apple M-series, JDK 25, vs jsonista (Jackson). Read,
keyword keys:

| payload | oda | jsonista | speedup |
|---|---|---|---|
| number-heavy | 144µs | 349µs | 2.4x |
| citm_catalog | 1889µs | 3066µs | 1.6x |
| small objects, repeated keys | 374µs | 562µs | 1.5x |
| string-heavy (raw UTF-8) | 1106µs | 1292µs | 1.2x |
| string-heavy (\uXXXX escapes) | 1480µs | 1616µs | 1.1x |
| twitter.json | 1275µs | 1388µs | 1.1x |

Write is 1.3-1.5x faster than jsonista on the same payloads except
long-unicode-string documents (~0.9x). Run `clj -M:bench -m s-exp.oda.bench`
to reproduce.

### Optional SIMD

With the (incubating) Vector API enabled, long-string payloads improve
further (twitter +10%, raw string-heavy +21%):

```shell
clj -J--add-modules -Jjdk.incubator.vector ...
```

Without the module oda silently uses its scalar (SWAR) paths.
`-Doda.vector=false` forces scalar.

## Correctness

- full [JSONTestSuite](https://github.com/nst/JSONTestSuite) corpus
- differential testing against jsonista (corpus + generative)
- doubles are correctly rounded (Eisel-Lemire, validated bit-exact against
  `Double/parseDouble` on torture values and generative corpora)
- duplicate object keys: last wins

## Design notes

- single-pass parser over `byte[]`, fused tokenizer/builder, no token objects
- SWAR (8-byte) string scanning, optional 16-byte SIMD via Vector API
- object keys canonicalized through fixed-size lossy caches (no locks, no
  thread-locals, bounded memory, virtual-thread friendly)
- `PersistentArrayMap` built directly for small objects, transient
  `PersistentHashMap` above 8 keys
- numbers: inline long accumulation; doubles via a fused Eisel-Lemire
  conversion (ported from [FastDoubleParser](https://github.com/wrandelshofer/FastDoubleParser), MIT)
- writer: flat instanceof dispatch, pre-escaped key fragment caches,
  pair-table long rendering; streaming writes flush a 64KB buffer
