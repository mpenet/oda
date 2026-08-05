# oda

Fast JSON parser/writer for Clojure. Zero dependencies, JDK 25+.

oda is **fast, low-allocation, and correct**: it outperforms Jackson-backed
libraries (jsonista, cheshire) on every workload we bench, read and write,
allocates 2-5x less per operation, and is validated against the full
[JSONTestSuite](https://github.com/nst/JSONTestSuite) corpus with bit-exact
double parsing and writing (see [Correctness](#correctness)).

Enabling the (incubating) Vector API adds SIMD string scanning for an
extra boost on string-heavy documents — see [Optional SIMD](#optional-simd).

> Status: alpha. API may still move.

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/oda.svg)](https://clojars.org/com.s-exp/oda)

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
| `:date-format` | `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC) | format for `java.util.Date`/`java.time.Instant` values: a pattern string or a `DateTimeFormatter` (zone-less formatters default to UTC) |

### Supported types (write)

Maps (keys: keyword, string, symbol, number), vectors, sets, seqs,
`java.util.Map`/`Iterable`, strings, keywords, symbols, chars, UUIDs,
`java.util.Date`/`java.time.Instant` (see `:date-format`), all JVM numbers
(Ratio written as double), booleans, nil. NaN/Infinity throw.

## Performance

JMH (forked, average time, gc-profiled), Apple M-series, JDK 25, keyword
keys. All multipliers are oda's speedup relative to jsonista (Jackson);
higher is better, below 1.0x jsonista is faster. SIMD columns have the
Vector API enabled (see below).

| payload | read | read (SIMD) | write | write (SIMD) |
|---|---|---|---|---|
| number-heavy | **2.4x** | **2.3x** | 1.3x | 1.2x |
| citm_catalog | **1.9x** | **1.9x** | **2.5x** | **2.6x** |
| small objects, repeated keys | **1.8x** | **1.8x** | 1.1x | 1.2x |
| long ASCII strings | **1.5x** | **3.6x** | 1.1x | **5.0x** |
| twitter.json | 1.2x | 1.2x | **1.9x** | **2.1x** |
| string-heavy (raw UTF-8) | 1.1x | **1.4x** | 1.1x | 1.1x |
| string-heavy (\uXXXX escapes) | 1.0x | 1.0x | **1.2x** | 1.1x |

Jackson's escaped-string writer is bimodal across JVM forks (~1.5ms or
~4ms per op on the string payloads); the ratios above use its fast mode.

oda also **allocates 2-5x less than jsonista per operation on most payloads**
(e.g. citm read: 1.4MB vs 7.9MB per op). **Writes allocate nothing beyond
the returned array** (numbers included, via a Ryū port). Reproduce with:

```shell
clj -M:jmh quick vector    # or: full, scalar, plus payload/benchmark names
```

### Optional SIMD

With the (incubating) Vector API enabled, string scanning and encoding go
16 bytes at a time. Standout deltas vs oda's own scalar paths: long
pure-ASCII strings read **~2.4x**, write **~4.4x**; raw UTF-8 string-heavy
read **+27%**. Short-string and mixed-unicode payloads see no benefit (a
run-length heuristic keeps them on the scalar path).

Enable with:

```shell
clj -J--add-modules -Jjdk.incubator.vector ...
```

Without the module oda silently uses its scalar (SWAR) paths.
`-Doda.vector=false` forces scalar.

## Correctness

- full [JSONTestSuite](https://github.com/nst/JSONTestSuite) corpus (all
  `y_`/`n_`/`i_` cases)
- differential testing against jsonista on the corpus and on generative
  payloads (test.check)
- doubles are correctly rounded on both sides: Eisel-Lemire parsing and
  Ryū writing, validated bit-exact against `Double/parseDouble` on
  torture values plus thousands of generated round-trips
- write→parse round-trip properties on random Clojure structures
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
