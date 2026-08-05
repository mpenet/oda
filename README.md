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

JMH (forked, average time, gc-profiled), Apple M-series, JDK 25, keyword
keys. All multipliers are oda's speedup relative to jsonista (Jackson);
higher is better, below 1.0x jsonista is faster. SIMD columns have the
Vector API enabled (see below).

| payload | read | read (SIMD) | write | write (SIMD) |
|---|---|---|---|---|
| number-heavy | **2.2x** | **2.3x** | 1.3x | 1.2x |
| small objects, repeated keys | **2.0x** | **1.8x** | 1.2x | 1.3x |
| citm_catalog | **1.5x** | **1.6x** | **2.6x** | **2.4x** |
| long ASCII strings | **1.5x** | **3.6x** | 1.2x | **4.4x** |
| twitter.json | 1.1x | 1.2x | **1.9x** | **2.3x** |
| string-heavy (raw UTF-8) | 1.1x | 1.3x | 0.9x | 0.9x |
| string-heavy (\uXXXX escapes) | 0.9x | 1.0x | 0.9x | 0.9x |

Jackson's escaped-string writer is bimodal across JVM forks (~1.5ms or
~4ms per op on the string payloads); the ratios above use its fast mode.

oda also allocates 2-5x less than jsonista per operation on most payloads
(e.g. citm read: 1.4MB vs 7.9MB per op). **Writes allocate nothing beyond
the returned array** (numbers included, via a Ryū port). Reproduce with:

```shell
clj -M:jmh quick vector    # or: full, scalar, plus payload/benchmark names
```

### Optional SIMD

With the (incubating) Vector API enabled, string scanning and encoding go
16 bytes at a time. 

Measured A/B deltas against oda's own scalar paths: 

* long pure-ASCII strings: read **~2.3x**, write **~4.4x**
* raw string-heavy read **+22%**
* string-heavy writes **+5-6%**
* short-string payloads unaffected (a run-length heuristic keeps them on the scalar path). 


Enable with:

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

## License

Copyright © Max Penet. Distributed under the
[Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/)
(see `LICENSE`).

`EiselLemire.java` is ported from
[FastDoubleParser](https://github.com/wrandelshofer/FastDoubleParser),
Copyright © Werner Randelshofer, MIT License. `RyuDouble.java` is adapted
from [ryu](https://github.com/ulfjack/ryu), Copyright © Ulf Adams,
Apache License 2.0.
