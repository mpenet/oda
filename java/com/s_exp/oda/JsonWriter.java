package com.s_exp.oda;

import clojure.lang.AFn;
import clojure.lang.BigInt;
import clojure.lang.IFn;
import clojure.lang.IKVReduce;
import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.IReduceInit;
import clojure.lang.Keyword;
import clojure.lang.Ratio;
import clojure.lang.Symbol;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON writer building UTF-8 bytes directly from Clojure data structures.
 * Single monomorphic implementation: traversal and encoding are fused, type
 * dispatch is a flat instanceof chain (no per-type serializer lookup).
 */
public final class JsonWriter {

    private static final ConcurrentLinkedQueue<JsonWriter> POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger POOL_SIZE = new AtomicInteger();
    private static final int MAX_POOLED = 8;

    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] LONG_MIN_BYTES = "-9223372036854775808".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Escape action per ASCII char: 0 = write raw, 'u' = \\u00XX escape,
     * otherwise the char to emit after the backslash.
     */
    private static final byte[] ESCAPE = new byte[128];

    /** "00".."99" for pair-at-a-time long rendering. */
    private static final byte[] DIGIT_PAIRS = new byte[200];

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.ISO_8859_1);

    static {
        for (int i = 0; i < 0x20; i++) {
            ESCAPE[i] = 'u';
        }
        ESCAPE['"'] = '"';
        ESCAPE['\\'] = '\\';
        ESCAPE['\b'] = 'b';
        ESCAPE['\t'] = 't';
        ESCAPE['\n'] = 'n';
        ESCAPE['\f'] = 'f';
        ESCAPE['\r'] = 'r';
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2] = (byte) ('0' + i / 10);
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + i % 10);
        }
    }

    /** yyyy-MM-dd'T'HH:mm:ss'Z' at UTC, the cheshire/jsonista convention. */
    private static final DateTimeFormatter DEFAULT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** Pattern strings come from code, so this stays small in practice. */
    private static final ConcurrentHashMap<String, DateTimeFormatter> DATE_FORMATTERS =
            new ConcurrentHashMap<>();

    private static DateTimeFormatter dateFormatter(Object df) {
        if (df == null) {
            return DEFAULT_DATE_FORMAT;
        }
        if (df instanceof DateTimeFormatter f) {
            // Instant formatting needs a zone; default zone-less formatters to UTC
            return f.getZone() == null ? f.withZone(ZoneOffset.UTC) : f;
        }
        if (df instanceof String s) {
            return DATE_FORMATTERS.computeIfAbsent(s,
                    p -> DateTimeFormatter.ofPattern(p).withZone(ZoneOffset.UTC));
        }
        throw new IllegalArgumentException(
                "date-format must be a pattern String or DateTimeFormatter, got "
                        + df.getClass().getName());
    }

    private byte[] buf = new byte[1024];
    private int n;
    private IFn defaultFn;
    private DateTimeFormatter dateFormat;
    private OutputStream out; // non-null = streaming mode: flush on overflow

    // ------------------------------------------------------------ public API

    public static String writeString(Object x, IFn defaultFn, Object dateFormat) {
        JsonWriter w = acquire(defaultFn, dateFormat);
        try {
            w.writeValue(x);
            return new String(w.buf, 0, w.n, StandardCharsets.UTF_8);
        } finally {
            w.release();
        }
    }

    public static byte[] writeBytes(Object x, IFn defaultFn, Object dateFormat) {
        JsonWriter w = acquire(defaultFn, dateFormat);
        try {
            w.writeValue(x);
            return Arrays.copyOf(w.buf, w.n);
        } finally {
            w.release();
        }
    }

    /**
     * Writes progressively: the internal buffer is flushed to {@code out}
     * whenever it fills, so memory stays bounded by the buffer size (plus
     * the largest single string) regardless of document size.
     */
    public static void writeStream(Object x, IFn defaultFn, Object dateFormat, OutputStream out)
            throws IOException {
        JsonWriter w = acquire(defaultFn, dateFormat);
        try {
            if (w.buf.length < (1 << 16)) {
                w.buf = new byte[1 << 16];
            }
            w.out = out;
            w.writeValue(x);
            w.flushBuf();
            out.flush();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            w.out = null;
            w.release();
        }
    }

    private void flushBuf() {
        if (n > 0) {
            try {
                out.write(buf, 0, n);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            n = 0;
        }
    }

    private static JsonWriter acquire(IFn defaultFn, Object dateFormat) {
        JsonWriter w = POOL.poll();
        if (w == null) {
            w = new JsonWriter();
        } else {
            POOL_SIZE.decrementAndGet();
        }
        w.defaultFn = defaultFn;
        w.dateFormat = dateFormatter(dateFormat);
        w.n = 0;
        return w;
    }

    private void release() {
        defaultFn = null;
        dateFormat = null;
        // don't pin giant scratch buffers in the pool
        if (buf.length > (1 << 23)) {
            buf = new byte[1 << 16];
        }
        if (cscratch.length > (1 << 20)) {
            cscratch = new char[256];
        }
        if (POOL_SIZE.get() < MAX_POOLED) {
            POOL_SIZE.incrementAndGet();
            POOL.offer(this);
        }
    }

    // -------------------------------------------------------------- dispatch

    /**
     * Type dispatch ordered by expected frequency in typical Clojure JSON,
     * with structural types (IPersistentMap, Iterable) hoisted so that
     * vector/seq/set values don't fall through the numeric+temporal chain
     * on every element. IPersistentMap must precede Iterable because
     * APersistentMap implements Iterable.
     */
    private void writeValue(Object x) {
        if (x == null) {
            writeRaw(NULL_BYTES);
        } else if (x instanceof String s) {
            writeJsonString(s);
        } else if (x instanceof Long l) {
            writeLong(l);
        } else if (x instanceof Double d) {
            writeDouble(d);
        } else if (x instanceof IPersistentMap m) {
            writeMap(m);
        } else if (x instanceof Iterable<?> it) {
            writeIterable(it);
        } else if (x instanceof Keyword k) {
            writeKeywordValue(k);
        } else if (x instanceof Boolean b) {
            writeRaw(b ? TRUE_BYTES : FALSE_BYTES);
        } else if (x instanceof Integer i) {
            writeLong(i.longValue());
        } else if (x instanceof Map<?, ?> jm) {
            writeJavaMap(jm);
        } else if (x instanceof BigDecimal bd) {
            writeRawAscii(bd.toString());
        } else if (x instanceof BigInteger bi) {
            writeRawAscii(bi.toString());
        } else if (x instanceof BigInt bi) {
            writeRawAscii(bi.toString());
        } else if (x instanceof Float f) {
            float fv = f;
            if (Float.isNaN(fv) || Float.isInfinite(fv)) {
                throw new IllegalArgumentException("JSON cannot represent " + fv);
            }
            writeRawAscii(Float.toString(fv));
        } else if (x instanceof Short || x instanceof Byte) {
            writeLong(((Number) x).longValue());
        } else if (x instanceof Ratio r) {
            writeDouble(r.doubleValue());
        } else if (x instanceof Number num) {
            writeDouble(num.doubleValue());
        } else if (x instanceof CharSequence cs) {
            writeJsonString(cs.toString());
        } else if (x instanceof Character c) {
            writeChar(c);
        } else if (x instanceof UUID u) {
            writeUuid(u);
        } else if (x instanceof Symbol sym) {
            writeJsonString(sym.toString());
        } else if (x instanceof Date d) {
            writeDateValue(d.getTime());
        } else if (x instanceof Instant i) {
            writeInstantValue(i);
        } else if (defaultFn != null) {
            writeValue(defaultFn.invoke(x));
        } else {
            throw new IllegalArgumentException("Cannot write value of type " + x.getClass().getName());
        }
    }

    // ------------------------------------------------------------ structures

    private boolean needComma;

    /**
     * kvreduce passes keys/values directly, avoiding the MapEntry (and PHM
     * NodeIter) allocation per entry that map iterators cost. Single reused
     * instance keeps the kvreduce call site monomorphic.
     */
    private final IFn entryWriter = new AFn() {
        @Override
        public Object invoke(Object acc, Object k, Object v) {
            if (needComma) {
                ensure(1);
                buf[n++] = ',';
            }
            needComma = true;
            writeKey(k);
            writeValue(v);
            return acc;
        }
    };

    private void writeMap(IPersistentMap m) {
        ensure(2);
        buf[n++] = '{';
        if (m instanceof IKVReduce kv) {
            boolean saved = needComma; // nested maps reenter through writeValue
            needComma = false;
            kv.kvreduce(entryWriter, null);
            needComma = saved;
        } else {
            boolean first = true;
            for (Object o : (Iterable<?>) m) {
                IMapEntry e = (IMapEntry) o;
                if (!first) {
                    ensure(1);
                    buf[n++] = ',';
                }
                first = false;
                writeKey(e.key());
                writeValue(e.val());
            }
        }
        ensure(1);
        buf[n++] = '}';
    }

    private void writeJavaMap(Map<?, ?> m) {
        ensure(2);
        buf[n++] = '{';
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                ensure(1);
                buf[n++] = ',';
            }
            first = false;
            writeKey(e.getKey());
            writeValue(e.getValue());
        }
        ensure(1);
        buf[n++] = '}';
    }

    /**
     * Fixed-size lossy caches from map keys to their pre-escaped
     * {@code "key":} fragment; same benign-race design as {@link KeyCache}.
     * Keywords are interned so identity comparison suffices; strings need
     * equals and skip caching for long keys to bound retained memory.
     */
    private record KwFrag(Keyword kw, byte[] frag) {
    }

    private record StrFrag(String key, byte[] frag) {
    }

    /** Keyword VALUES (quoted, no colon): enum-like keywords repeat a lot. */
    private record KwVal(Keyword kw, byte[] frag) {
    }

    private static final int FRAG_MASK = 8191;
    private static final KwFrag[] KW_FRAGS = new KwFrag[FRAG_MASK + 1];
    private static final StrFrag[] STR_FRAGS = new StrFrag[FRAG_MASK + 1];
    private static final KwVal[] KW_VALS = new KwVal[FRAG_MASK + 1];
    private static final int MAX_CACHED_KEY_LENGTH = 64;

    private void writeKeywordKey(Keyword kw) {
        int i = kw.hashCode() & FRAG_MASK;
        KwFrag e = KW_FRAGS[i];
        if (e != null && e.kw() == kw) {
            writeRaw(e.frag());
            return;
        }
        byte[] frag = escapedKeyFragment(kw.sym.toString());
        KW_FRAGS[i] = new KwFrag(kw, frag);
        writeRaw(frag);
    }

    private void writeKeywordValue(Keyword kw) {
        int i = kw.hashCode() & FRAG_MASK;
        KwVal e = KW_VALS[i];
        if (e != null && e.kw() == kw) {
            writeRaw(e.frag());
            return;
        }
        byte[] frag = escapedJsonStringBytes(kw.sym.toString());
        KW_VALS[i] = new KwVal(kw, frag);
        writeRaw(frag);
    }

    private void writeStringKey(String s) {
        if (s.length() > MAX_CACHED_KEY_LENGTH) {
            writeJsonString(s);
            ensure(1);
            buf[n++] = ':';
            return;
        }
        int h = s.hashCode();
        int i = (h ^ (h >>> 16)) & FRAG_MASK;
        StrFrag e = STR_FRAGS[i];
        if (e != null && e.key().equals(s)) {
            writeRaw(e.frag());
            return;
        }
        byte[] frag = escapedKeyFragment(s);
        STR_FRAGS[i] = new StrFrag(s, frag);
        writeRaw(frag);
    }

    private void writeKey(Object k) {
        if (k instanceof Keyword kw) {
            writeKeywordKey(kw);
        } else if (k instanceof String s) {
            writeStringKey(s);
        } else if (k instanceof Symbol sym) {
            writeJsonString(sym.toString());
            ensure(1);
            buf[n++] = ':';
        } else if (k instanceof Number) {
            ensure(1);
            buf[n++] = '"';
            writeValue(k);
            ensure(2);
            buf[n++] = '"';
            buf[n++] = ':';
        } else {
            throw new IllegalArgumentException(
                    "Cannot write map key of type " + (k == null ? "nil" : k.getClass().getName()));
        }
    }

    /**
     * Same idea as entryWriter: reduce hands elements to the callback
     * directly, skipping the iterator allocation and the per-element
     * hasNext/next calls (PersistentVector reduces over its chunks).
     */
    private final IFn elementWriter = new AFn() {
        @Override
        public Object invoke(Object acc, Object x) {
            if (needComma) {
                ensure(1);
                buf[n++] = ',';
            }
            needComma = true;
            writeValue(x);
            return acc;
        }
    };

    private void writeIterable(Iterable<?> it) {
        ensure(2);
        buf[n++] = '[';
        if (it instanceof IReduceInit r) {
            boolean saved = needComma; // nested collections reenter
            needComma = false;
            r.reduce(elementWriter, null);
            needComma = saved;
        } else {
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    ensure(1);
                    buf[n++] = ',';
                }
                first = false;
                writeValue(o);
            }
        }
        ensure(1);
        buf[n++] = ']';
    }

    // --------------------------------------------------------------- strings

    private char[] cscratch = new char[256];

    private void writeJsonString(String s) {
        int len = s.length();
        if (len > cscratch.length) {
            cscratch = new char[Math.max(len, cscratch.length << 1)];
        }
        char[] cs = cscratch;
        s.getChars(0, len, cs, 0);
        // worst case 6 bytes per char (\ u00XX); paying it upfront removes
        // all capacity checks from the loop
        ensure(6 * len + 2);
        int p = n;
        buf[p++] = '"';
        if (JsonParser.VECTOR && len >= 32) {
            p = encodeVector(cs, len, p);
        } else {
            p = encodeScalar(cs, 0, len, p);
        }
        buf[p++] = '"';
        n = p;
    }

    /**
     * SIMD path: bulk-encodes clean ASCII runs, handling dirty chars one at
     * a time in between.
     *
     * A probe that returns fewer than a full vector of clean chars means
     * the next dirty char is close: one wasted mask round-trip per unit
     * eats the win on strings with regularly spaced unicode (e.g. ~21
     * ASCII + non-BMP repeated). On the first such probe we bail out to
     * the scalar path for the rest of the string.
     */
    private int encodeVector(char[] cs, int len, int p) {
        int i = 0;
        while (i < len && len - i >= VectorScan.SHORT_LANES) {
            int k = VectorScan.encodeAscii(cs, i, len, buf, p);
            i += k;
            p += k;
            if (i >= len) {
                return p;
            }
            if (k < VectorScan.SHORT_LANES) {
                // short clean run — vector setup cost dominates, hand the
                // rest (including this dirty char) to the scalar encoder
                return encodeScalar(cs, i, len, p);
            }
            long r = encodeDirty(cs, i, len, p);
            i = (int) (r >>> 32);
            p = (int) r;
            // regularly spaced unicode (e.g. surrogate pair after a BMP
            // symbol) shows up as two dirty chars in a row; the next probe
            // would return k=0 (wasted mask round-trip). Peek and bail.
            if (i < len) {
                char c = cs[i];
                if (c < 0x20 || c >= 0x80 || c == '"' || c == '\\') {
                    return encodeScalar(cs, i, len, p);
                }
            }
        }
        return encodeScalar(cs, i, len, p);
    }

    /** Encodes the single (dirty) char at i; returns (newI << 32) | newP. */
    private long encodeDirty(char[] cs, int i, int len, int p) {
        byte[] b = buf;
        char c = cs[i];
        if (c < 0x80) {
            byte esc = ESCAPE[c];
            if (esc == 'u') {
                b[p++] = '\\';
                b[p++] = 'u';
                b[p++] = '0';
                b[p++] = '0';
                b[p++] = HEX_DIGITS[(c >> 4) & 0xf];
                b[p++] = HEX_DIGITS[c & 0xf];
            } else if (esc != 0) {
                b[p++] = '\\';
                b[p++] = esc;
            } else {
                b[p++] = (byte) c; // vector stopped in the tail on a clean char
            }
            i++;
        } else if (c < 0x800) {
            b[p++] = (byte) (0xC0 | (c >> 6));
            b[p++] = (byte) (0x80 | (c & 0x3F));
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            if (c <= 0xDBFF && i + 1 < len) {
                char c2 = cs[i + 1];
                if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                    int cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                    b[p++] = (byte) (0xF0 | (cp >> 18));
                    b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    b[p++] = (byte) (0x80 | (cp & 0x3F));
                    return ((long) (i + 2) << 32) | p;
                }
            }
            b[p++] = (byte) 0xEF;
            b[p++] = (byte) 0xBF;
            b[p++] = (byte) 0xBD;
            i++;
        } else {
            b[p++] = (byte) (0xE0 | (c >> 12));
            b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
            b[p++] = (byte) (0x80 | (c & 0x3F));
            i++;
        }
        return ((long) i << 32) | p;
    }

    /**
     * Scalar encode: scans for the end of each clean-ASCII run, bulk-copies
     * it into the byte buffer (JIT-vectorized narrow-copy idiom), then
     * handles the single dirty char before restarting the scan. Splitting
     * the scan from the copy lets the copy loop stay branch-free.
     */
    private int encodeScalar(char[] cs, int i, int len, int p) {
        byte[] b = buf;
        while (i < len) {
            int j = i;
            while (j < len) {
                char c = cs[j];
                if (c < 0x20 || c >= 0x80 || c == '"' || c == '\\') {
                    break;
                }
                j++;
            }
            for (int k = i; k < j; k++) {
                b[p++] = (byte) cs[k];
            }
            i = j;
            if (i >= len) {
                return p;
            }
            char c = cs[i];
            if (c < 0x80) {
                byte esc = ESCAPE[c];
                if (esc == 'u') {
                    b[p++] = '\\';
                    b[p++] = 'u';
                    b[p++] = '0';
                    b[p++] = '0';
                    b[p++] = HEX_DIGITS[(c >> 4) & 0xf];
                    b[p++] = HEX_DIGITS[c & 0xf];
                } else {
                    b[p++] = '\\';
                    b[p++] = esc;
                }
                i++;
            } else if (c < 0x800) {
                b[p++] = (byte) (0xC0 | (c >> 6));
                b[p++] = (byte) (0x80 | (c & 0x3F));
                i++;
            } else if (c >= 0xD800 && c <= 0xDFFF) {
                // surrogate: valid pair -> 4-byte UTF-8, lone -> U+FFFD
                if (c <= 0xDBFF && i + 1 < len) {
                    char c2 = cs[i + 1];
                    if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                        int cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                        b[p++] = (byte) (0xF0 | (cp >> 18));
                        b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                        b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                        b[p++] = (byte) (0x80 | (cp & 0x3F));
                        i += 2;
                        continue;
                    }
                }
                b[p++] = (byte) 0xEF;
                b[p++] = (byte) 0xBF;
                b[p++] = (byte) 0xBD;
                i++;
            } else {
                b[p++] = (byte) (0xE0 | (c >> 12));
                b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                b[p++] = (byte) (0x80 | (c & 0x3F));
                i++;
            }
        }
        return p;
    }

    /** Builds the pre-escaped {@code "key":} fragment for the key caches. */
    static byte[] escapedKeyFragment(String s) {
        JsonWriter w = new JsonWriter();
        w.writeJsonString(s);
        w.ensure(1);
        w.buf[w.n++] = ':';
        return Arrays.copyOf(w.buf, w.n);
    }

    /** Builds the pre-escaped quoted string for the keyword value cache. */
    static byte[] escapedJsonStringBytes(String s) {
        JsonWriter w = new JsonWriter();
        w.writeJsonString(s);
        return Arrays.copyOf(w.buf, w.n);
    }

    // --------------------------------------------------------------- numbers

    private void writeLong(long v) {
        if (v == Long.MIN_VALUE) {
            writeRaw(LONG_MIN_BYTES);
            return;
        }
        ensure(20);
        byte[] b = buf;
        int p = n;
        if (v < 0) {
            b[p++] = '-';
            v = -v;
        }
        int digits = digitCount(v);
        int end = p + digits;
        int i = end;
        while (v >= 100) {
            int r = (int) (v % 100);
            v /= 100;
            i -= 2;
            b[i] = DIGIT_PAIRS[r * 2];
            b[i + 1] = DIGIT_PAIRS[r * 2 + 1];
        }
        if (v < 10) {
            b[--i] = (byte) ('0' + v);
        } else {
            i -= 2;
            b[i] = DIGIT_PAIRS[(int) v * 2];
            b[i + 1] = DIGIT_PAIRS[(int) v * 2 + 1];
        }
        n = end;
    }

    private static int digitCount(long v) {
        int d = 1;
        if (v >= 10000000000000000L) {
            d += 16;
            v /= 10000000000000000L;
        }
        if (v >= 100000000) {
            d += 8;
            v /= 100000000;
        }
        if (v >= 10000) {
            d += 4;
            v /= 10000;
        }
        if (v >= 100) {
            d += 2;
            v /= 100;
        }
        if (v >= 10) {
            d += 1;
        }
        return d;
    }

    private void writeDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON cannot represent " + d);
        }
        ensure(24);
        n = RyuDouble.write(buf, n, d);
    }

    /** Direct single-char write: skips String.valueOf's char[] + String alloc. */
    private void writeChar(char c) {
        // worst case: "\uXXXX" (6) + 2 quotes = 8
        ensure(8);
        byte[] b = buf;
        int p = n;
        b[p++] = '"';
        if (c < 0x80) {
            byte esc = ESCAPE[c];
            if (esc == 0) {
                b[p++] = (byte) c;
            } else if (esc == 'u') {
                b[p++] = '\\';
                b[p++] = 'u';
                b[p++] = '0';
                b[p++] = '0';
                b[p++] = HEX_DIGITS[(c >> 4) & 0xf];
                b[p++] = HEX_DIGITS[c & 0xf];
            } else {
                b[p++] = '\\';
                b[p++] = esc;
            }
        } else if (c < 0x800) {
            b[p++] = (byte) (0xC0 | (c >> 6));
            b[p++] = (byte) (0x80 | (c & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            // lone surrogate — no companion possible in a single char, U+FFFD
            b[p++] = (byte) 0xEF;
            b[p++] = (byte) 0xBF;
            b[p++] = (byte) 0xBD;
        } else {
            b[p++] = (byte) (0xE0 | (c >> 12));
            b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
            b[p++] = (byte) (0x80 | (c & 0x3F));
        }
        b[p++] = '"';
        n = p;
    }

    // ------------------------------------------------------------ UUID / dates

    /** Emits {@code "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"} directly, no alloc. */
    private void writeUuid(UUID u) {
        ensure(38);
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        byte[] b = buf;
        int p = n;
        b[p++] = '"';
        p = writeHex(b, p, msb >>> 32, 8);
        b[p++] = '-';
        p = writeHex(b, p, msb >>> 16, 4);
        b[p++] = '-';
        p = writeHex(b, p, msb, 4);
        b[p++] = '-';
        p = writeHex(b, p, lsb >>> 48, 4);
        b[p++] = '-';
        p = writeHex(b, p, lsb, 12);
        b[p++] = '"';
        n = p;
    }

    private static int writeHex(byte[] b, int p, long v, int nChars) {
        for (int i = nChars - 1; i >= 0; i--) {
            b[p + i] = HEX_DIGITS[(int) v & 0xf];
            v >>>= 4;
        }
        return p + nChars;
    }

    private void writeDateValue(long millis) {
        if (dateFormat == DEFAULT_DATE_FORMAT) {
            writeIsoInstant(millis);
        } else {
            // custom formatter: value-type Instant needed
            writeJsonString(dateFormat.format(Instant.ofEpochMilli(millis)));
        }
    }

    private void writeInstantValue(Instant i) {
        if (dateFormat == DEFAULT_DATE_FORMAT) {
            writeIsoInstant(i.toEpochMilli());
        } else {
            writeJsonString(dateFormat.format(i));
        }
    }

    /**
     * Emits {@code "yyyy-MM-ddTHH:mm:ssZ"} at UTC directly from epoch millis,
     * no Instant/String allocation. Year formatted in 4 digits (1000-9999);
     * outside that range falls back to {@code Integer.toString(year)} for
     * the year portion (bounded by ensure() reservation).
     *
     * epochDay-to-y/m/d algorithm from java.time.LocalDate.ofEpochDay.
     */
    private void writeIsoInstant(long millis) {
        long secondsSinceEpoch = Math.floorDiv(millis, 1000L);
        int secondOfDay = (int) Math.floorMod(secondsSinceEpoch, 86400L);
        long epochDay = Math.floorDiv(secondsSinceEpoch, 86400L);

        long zeroDay = epochDay + 719528L - 60L;
        long adjust = 0;
        if (zeroDay < 0) {
            long adjustCycles = (zeroDay + 1) / 146097L - 1;
            adjust = adjustCycles * 400;
            zeroDay += -adjustCycles * 146097L;
        }
        long yearEst = (400 * zeroDay + 591) / 146097L;
        long doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        if (doyEst < 0) {
            yearEst--;
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        }
        yearEst += adjust;
        int marchDoy0 = (int) doyEst;
        int marchMonth0 = (marchDoy0 * 5 + 2) / 153;
        int month = (marchMonth0 + 2) % 12 + 1;
        int dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1;
        yearEst += marchMonth0 / 10;
        int year = (int) yearEst;

        int hour = secondOfDay / 3600;
        int minute = (secondOfDay / 60) % 60;
        int second = secondOfDay % 60;

        // "yyyy-MM-ddTHH:mm:ssZ" + 2 quotes = 22 bytes for years 0-9999
        ensure(24);
        byte[] b = buf;
        int p = n;
        b[p++] = '"';
        if (year >= 0 && year <= 9999) {
            b[p++] = (byte) ('0' + year / 1000);
            int y3 = year % 1000;
            b[p++] = (byte) ('0' + y3 / 100);
            int y2 = y3 % 100;
            b[p++] = DIGIT_PAIRS[y2 * 2];
            b[p++] = DIGIT_PAIRS[y2 * 2 + 1];
        } else {
            // very rare, out-of-normal-range years: length varies; ensure it
            String yStr = Integer.toString(year);
            n = p;
            ensure(yStr.length() + 18);
            b = buf;
            p = n;
            for (int i = 0; i < yStr.length(); i++) {
                b[p++] = (byte) yStr.charAt(i);
            }
        }
        b[p++] = '-';
        b[p++] = DIGIT_PAIRS[month * 2];
        b[p++] = DIGIT_PAIRS[month * 2 + 1];
        b[p++] = '-';
        b[p++] = DIGIT_PAIRS[dom * 2];
        b[p++] = DIGIT_PAIRS[dom * 2 + 1];
        b[p++] = 'T';
        b[p++] = DIGIT_PAIRS[hour * 2];
        b[p++] = DIGIT_PAIRS[hour * 2 + 1];
        b[p++] = ':';
        b[p++] = DIGIT_PAIRS[minute * 2];
        b[p++] = DIGIT_PAIRS[minute * 2 + 1];
        b[p++] = ':';
        b[p++] = DIGIT_PAIRS[second * 2];
        b[p++] = DIGIT_PAIRS[second * 2 + 1];
        b[p++] = 'Z';
        b[p++] = '"';
        n = p;
    }

    // ------------------------------------------------------------------ misc

    private void writeRaw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, n, bytes.length);
        n += bytes.length;
    }

    private void writeRawAscii(String s) {
        // getBytes(ISO_8859_1) is intrinsified and cheaper than a per-char
        // narrow-copy loop for the sizes we see here (BigDecimal/BigInteger/
        // BigInt/Float toString outputs). Callers guarantee ASCII content.
        byte[] src = s.getBytes(StandardCharsets.ISO_8859_1);
        ensure(src.length);
        System.arraycopy(src, 0, buf, n, src.length);
        n += src.length;
    }

    private void ensure(int k) {
        if (n + k > buf.length) {
            overflow(k);
        }
    }

    private void overflow(int k) {
        if (out != null) {
            flushBuf();
            if (k > buf.length) {
                // single oversized value, e.g. a huge string reservation
                buf = new byte[k];
            }
        } else {
            buf = Arrays.copyOf(buf, Math.max(n + k, buf.length << 1));
        }
    }
}
