package com.s_exp.oda;

import clojure.lang.IFn;
import clojure.lang.ITransientMap;
import clojure.lang.Keyword;
import clojure.lang.LazilyPersistentVector;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Recursive-descent JSON parser building Clojure persistent data structures
 * directly from UTF-8 bytes. Single monomorphic implementation: tokenizer and
 * builder are fused so the JIT can inline the whole pipeline.
 */
public final class JsonParser {

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int KEY_STRINGS = 0;
    private static final int KEY_KEYWORDS = 1;
    private static final int KEY_CUSTOM = 2;

    /** clojure.core/keyword, recognized by identity for the interning fast path. */
    private static final Object CORE_KEYWORD = RT.var("clojure.core", "keyword").deref();

    /**
     * Per-fn-instance key tables for custom key fns, keyed by identity.
     * Requires key fns to be pure. Weak keys: tables of discarded lambdas
     * get collected; a def'd fn keeps its table and gets cross-parse
     * caching like the built-in modes.
     */
    private static final Map<IFn, KeyCache.Entry[]> CUSTOM_TABLES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final byte[] buf;
    private final int end;
    private final int keyMode;
    private final IFn keyFn;
    private final KeyCache.Entry[] keyTable;
    private final int maxDepth;
    private int pos;

    private char[] cbuf = new char[64];
    private Object[][] kvBufs = new Object[16][];
    private Object[][] valBufs = new Object[16][];

    private JsonParser(byte[] buf, int offset, int length, IFn keyFn, int maxDepth) {
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
        this.maxDepth = maxDepth;
        if (keyFn == null) {
            this.keyMode = KEY_STRINGS;
            this.keyTable = KeyCache.STRINGS;
            this.keyFn = null;
        } else if (keyFn == CORE_KEYWORD) {
            this.keyMode = KEY_KEYWORDS;
            this.keyTable = KeyCache.KEYWORDS;
            this.keyFn = null;
        } else if (keyFn instanceof Keyword) {
            // a keyword invoked as key-fn would silently produce nil keys
            throw new IllegalArgumentException(
                    "key-fn must be a fn of String -> key, got keyword: " + keyFn);
        } else {
            this.keyMode = KEY_CUSTOM;
            this.keyTable = CUSTOM_TABLES.computeIfAbsent(keyFn,
                    f -> new KeyCache.Entry[KeyCache.CUSTOM_SIZE]);
            this.keyFn = keyFn;
        }
    }

    /**
     * Parses the given UTF-8 span. keyFn transforms object keys: null leaves
     * them as Strings, clojure.core/keyword (recognized by identity) interns
     * them as Keywords without invoking the fn, any other fn is invoked with
     * the key String on cache misses (and must therefore be pure).
     */
    public static Object parse(byte[] buf, int offset, int length, IFn keyFn, int maxDepth) {
        JsonParser p = new JsonParser(buf, offset, length, keyFn, maxDepth);
        p.skipWs();
        Object v = p.parseValue(0);
        p.skipWs();
        if (p.pos != p.end) {
            throw p.err("trailing characters after JSON value");
        }
        return v;
    }

    private Object parseValue(int depth) {
        if (pos >= end) {
            throw err("unexpected end of input");
        }
        byte b = buf[pos];
        switch (b) {
            case '{': return parseObject(depth);
            case '[': return parseArray(depth);
            case '"': pos++; return parseString();
            case 't': return parseTrue();
            case 'f': return parseFalse();
            case 'n': return parseNull();
            default:
                if (b == '-' || (b >= '0' && b <= '9')) {
                    return parseNumber();
                }
                throw err("unexpected character");
        }
    }

    // ---------------------------------------------------------------- objects

    private Object parseObject(int depth) {
        if (depth >= maxDepth) {
            throw err("max nesting depth exceeded");
        }
        pos++; // '{'
        skipWs();
        if (pos < end && buf[pos] == '}') {
            pos++;
            return PersistentArrayMap.EMPTY;
        }
        Object[] kvs = kvBuf(depth);
        int n = 0;
        while (true) {
            if (pos >= end || buf[pos] != '"') {
                throw err("expected string key");
            }
            pos++;
            Object k = parseKey();
            skipWs();
            if (pos >= end || buf[pos] != ':') {
                throw err("expected ':'");
            }
            pos++;
            skipWs();
            Object v = parseValue(depth + 1);
            if (n == kvs.length) {
                kvs = Arrays.copyOf(kvs, n << 1);
                kvBufs[depth] = kvs;
            }
            kvs[n] = k;
            kvs[n + 1] = v;
            n += 2;
            skipWs();
            if (pos >= end) {
                throw err("unterminated object");
            }
            byte c = buf[pos];
            if (c == ',') {
                pos++;
                skipWs();
            } else if (c == '}') {
                pos++;
                return buildMap(kvs, n);
            } else {
                throw err("expected ',' or '}'");
            }
        }
    }

    private static Object buildMap(Object[] kvs, int n) {
        if (n <= 16) {
            // last-wins duplicate handling; identity check hits first for interned keywords
            Object[] arr = new Object[n];
            int m = 0;
            outer:
            for (int i = 0; i < n; i += 2) {
                Object k = kvs[i];
                for (int j = 0; j < m; j += 2) {
                    if (Util.equiv(arr[j], k)) {
                        arr[j + 1] = kvs[i + 1];
                        continue outer;
                    }
                }
                arr[m] = k;
                arr[m + 1] = kvs[i + 1];
                m += 2;
            }
            if (m != n) {
                arr = Arrays.copyOf(arr, m);
            }
            return new PersistentArrayMap(arr);
        }
        ITransientMap t = (ITransientMap) PersistentHashMap.EMPTY.asTransient();
        for (int i = 0; i < n; i += 2) {
            t = t.assoc(kvs[i], kvs[i + 1]);
        }
        return t.persistent();
    }

    private Object parseKey() {
        byte[] b = buf;
        int start = pos;
        int p = start;
        long high = 0;
        while (true) {
            while (end - p >= 8) {
                long w = (long) LONG_LE.get(b, p);
                long m = structMask(w);
                if (m != 0) {
                    int i = Long.numberOfTrailingZeros(m) >>> 3;
                    high |= w & HIGH_BITS & ((1L << (i << 3)) - 1);
                    p += i;
                    break;
                }
                high |= w & HIGH_BITS;
                p += 8;
            }
            if (p >= end) {
                throw err("unterminated string");
            }
            int c = b[p] & 0xff;
            if (c == '"') {
                if (high == 0) {
                    pos = p + 1;
                    int len = p - start;
                    int h = KeyCache.hash(b, start, len);
                    Object k = KeyCache.lookup(keyTable, b, start, len, h);
                    if (k != null) {
                        return k;
                    }
                    return missKey(b, start, len, h);
                }
                // non-ASCII key: rare, take the String path (pos untouched)
                return finishKeySlow();
            } else if (c == '\\' || c < 0x20) {
                // escapes / errors: rescan via the string path
                return finishKeySlow();
            } else if (c >= 0x80) {
                high = 1;
                p++;
            } else {
                p++;
            }
        }
    }

    private Object missKey(byte[] b, int start, int len, int h) {
        String s = new String(b, start, len, StandardCharsets.ISO_8859_1);
        Object k = keyFor(s);
        KeyCache.store(keyTable, b, start, len, h, k);
        return k;
    }

    private Object finishKeySlow() {
        return keyFor(parseString());
    }

    private Object keyFor(String s) {
        return switch (keyMode) {
            case KEY_KEYWORDS -> Keyword.intern(s);
            case KEY_STRINGS -> s;
            default -> keyFn.invoke(s);
        };
    }

    // ----------------------------------------------------------------- arrays

    private Object parseArray(int depth) {
        if (depth >= maxDepth) {
            throw err("max nesting depth exceeded");
        }
        pos++; // '['
        skipWs();
        if (pos < end && buf[pos] == ']') {
            pos++;
            return PersistentVector.EMPTY;
        }
        Object[] vals = valBuf(depth);
        int n = 0;
        while (true) {
            Object v = parseValue(depth + 1);
            if (n == vals.length) {
                vals = Arrays.copyOf(vals, n << 1);
                valBufs[depth] = vals;
            }
            vals[n++] = v;
            skipWs();
            if (pos >= end) {
                throw err("unterminated array");
            }
            byte c = buf[pos];
            if (c == ',') {
                pos++;
                skipWs();
            } else if (c == ']') {
                pos++;
                return LazilyPersistentVector.createOwning(Arrays.copyOf(vals, n));
            } else {
                throw err("expected ',' or ']'");
            }
        }
    }

    // ---------------------------------------------------------------- strings

    /** Called with pos just past the opening quote. */
    private String parseString() {
        byte[] b = buf;
        int start = pos;
        int p = start;
        long high = 0;
        while (true) {
            while (end - p >= 8) {
                long w = (long) LONG_LE.get(b, p);
                long m = structMask(w);
                if (m != 0) {
                    int i = Long.numberOfTrailingZeros(m) >>> 3;
                    high |= w & HIGH_BITS & ((1L << (i << 3)) - 1);
                    p += i;
                    break;
                }
                high |= w & HIGH_BITS;
                p += 8;
            }
            if (p >= end) {
                throw err("unterminated string");
            }
            int c = b[p] & 0xff;
            if (c == '"') {
                String s = new String(b, start, p - start,
                        high != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                pos = p + 1;
                return s;
            } else if (c == '\\') {
                return parseStringSlow(start);
            } else if (c >= 0x80) {
                high = 1;
                p++;
            } else if (c < 0x20) {
                pos = p;
                throw err("unescaped control character in string");
            } else {
                p++; // clean byte in the <8-byte tail
            }
        }
    }

    /** Escape-handling path; decodes into a reusable char buffer. */
    private String parseStringSlow(int start) {
        byte[] b = buf;
        int p = start;
        char[] out = cbuf;
        int n = 0;
        while (true) {
            // bulk-copy the clean ASCII run before the next special byte;
            // the copy loop is the JIT's byte->char inflate idiom
            int q = p;
            while (end - q >= 8) {
                long w = (long) LONG_LE.get(b, q);
                long m = structMask(w) | (w & HIGH_BITS);
                if (m != 0) {
                    q += Long.numberOfTrailingZeros(m) >>> 3;
                    break;
                }
                q += 8;
            }
            while (q < end) {
                int c = b[q] & 0xff;
                if (c == '"' || c == '\\' || c < 0x20 || c >= 0x80) {
                    break;
                }
                q++;
            }
            int run = q - p;
            if (n + run + 2 > out.length) {
                out = Arrays.copyOf(out, Math.max(n + run + 2, out.length << 1));
                cbuf = out;
            }
            for (int i = 0; i < run; i++) {
                out[n + i] = (char) (b[p + i] & 0xff);
            }
            n += run;
            p = q;
            // consume consecutive special bytes (escapes / UTF-8 sequences
            // often come back-to-back, e.g. surrogate pair escapes) without
            // paying the bulk-scan restart for each
            while (true) {
                if (p >= end) {
                    throw err("unterminated string");
                }
                int c = b[p] & 0xff;
                if (c == '"') {
                    pos = p + 1;
                    return new String(out, 0, n);
                }
                if (n + 2 > out.length) {
                    out = Arrays.copyOf(out, out.length << 1);
                    cbuf = out;
                }
                if (c == '\\') {
                    p++;
                    if (p >= end) {
                        throw err("unterminated string");
                    }
                    int e = b[p] & 0xff;
                    p++;
                    switch (e) {
                        case '"': out[n++] = '"'; break;
                        case '\\': out[n++] = '\\'; break;
                        case '/': out[n++] = '/'; break;
                        case 'b': out[n++] = '\b'; break;
                        case 'f': out[n++] = '\f'; break;
                        case 'n': out[n++] = '\n'; break;
                        case 'r': out[n++] = '\r'; break;
                        case 't': out[n++] = '\t'; break;
                        case 'u': {
                            if (p + 4 > end) {
                                throw err("incomplete unicode escape");
                            }
                            // HEX is -1 on invalid input; -1 survives the shifts
                            // with sign bit set, so one v < 0 check covers all four digits
                            int v = (HEX[b[p] & 0xff] << 12) | (HEX[b[p + 1] & 0xff] << 8)
                                    | (HEX[b[p + 2] & 0xff] << 4) | HEX[b[p + 3] & 0xff];
                            if (v < 0) {
                                pos = p;
                                throw err("invalid unicode escape");
                            }
                            p += 4;
                            out[n++] = (char) v;
                            break;
                        }
                        default:
                            pos = p - 1;
                            throw err("invalid escape character");
                    }
                } else if (c < 0x20) {
                    pos = p;
                    throw err("unescaped control character in string");
                } else {
                    long r = decodeUtf8(p, c);
                    p = (int) (r >>> 32);
                    int cp = (int) r;
                    if (cp >= 0x10000) {
                        out[n++] = (char) (0xD800 + ((cp - 0x10000) >>> 10));
                        out[n++] = (char) (0xDC00 + ((cp - 0x10000) & 0x3FF));
                    } else {
                        out[n++] = (char) cp;
                    }
                }
                if (p < end) {
                    int c2 = b[p] & 0xff;
                    if (c2 != '"' && c2 != '\\' && c2 >= 0x20 && c2 < 0x80) {
                        break; // clean ASCII: back to bulk scanning
                    }
                }
            }
        }
    }

    /**
     * Lenient UTF-8 decode of the sequence starting at p: malformed sequences
     * become U+FFFD, matching the behavior of the no-escape fast path (String
     * ctor). Returns new position in the high 32 bits, code point in the low.
     * Kept out of parseStringSlow to preserve its inline budget.
     */
    private long decodeUtf8(int p, int c) {
        byte[] b = buf;
        int cp;
        if ((c & 0xE0) == 0xC0 && p + 1 < end && cont(b[p + 1])) {
            cp = ((c & 0x1F) << 6) | (b[p + 1] & 0x3F);
            if (cp < 0x80) {
                cp = 0xFFFD;
            }
            p += 2;
        } else if ((c & 0xF0) == 0xE0 && p + 2 < end && cont(b[p + 1]) && cont(b[p + 2])) {
            cp = ((c & 0x0F) << 12) | ((b[p + 1] & 0x3F) << 6) | (b[p + 2] & 0x3F);
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) {
                cp = 0xFFFD;
            }
            p += 3;
        } else if ((c & 0xF8) == 0xF0 && p + 3 < end && cont(b[p + 1]) && cont(b[p + 2]) && cont(b[p + 3])) {
            cp = ((c & 0x07) << 18) | ((b[p + 1] & 0x3F) << 12) | ((b[p + 2] & 0x3F) << 6) | (b[p + 3] & 0x3F);
            if (cp < 0x10000 || cp > 0x10FFFF) {
                cp = 0xFFFD;
            }
            p += 4;
        } else {
            cp = 0xFFFD;
            p++;
        }
        return ((long) p << 32) | cp;
    }

    private static boolean cont(byte b) {
        return (b & 0xC0) == 0x80;
    }

    private static final byte[] HEX = new byte[256];

    static {
        Arrays.fill(HEX, (byte) -1);
        for (int i = '0'; i <= '9'; i++) {
            HEX[i] = (byte) (i - '0');
        }
        for (int i = 'a'; i <= 'f'; i++) {
            HEX[i] = (byte) (i - 'a' + 10);
        }
        for (int i = 'A'; i <= 'F'; i++) {
            HEX[i] = (byte) (i - 'A' + 10);
        }
    }

    private static final long HIGH_BITS = 0x8080808080808080L;

    /**
     * Returns a mask with 0x80 set in each lane holding a byte that is
     * structurally significant inside a string: '"', '\\' or &lt; 0x20.
     * Bytes &gt;= 0x80 are NOT flagged (UTF-8 content is skipped wholesale;
     * presence is tracked separately via HIGH_BITS accumulation).
     * Borrow-chain false positives can only occur in lanes above a true
     * positive, so the lowest set bit is exact.
     */
    private static long structMask(long w) {
        long quotes = w ^ 0x2222222222222222L;
        quotes = (quotes - 0x0101010101010101L) & ~quotes;
        long bs = w ^ 0x5C5C5C5C5C5C5C5CL;
        bs = (bs - 0x0101010101010101L) & ~bs;
        long ctrl = (w - 0x2020202020202020L) & ~w;
        return (quotes | bs | ctrl) & HIGH_BITS;
    }

    // ---------------------------------------------------------------- numbers

    private Object parseNumber() {
        byte[] b = buf;
        int start = pos;
        int p = start;
        boolean neg = false;
        if (b[p] == '-') {
            neg = true;
            p++;
            if (p >= end) {
                throw err("invalid number");
            }
        }
        // significand and decimal exponent are accumulated during the single
        // validation scan so the double path needs no re-parse of the span
        long mant = 0;
        int digits = 0;     // significant digits in mant, capped at 19
        int decExp = 0;
        boolean truncated = false;
        int intDigits = 0;
        byte c = b[p];
        if (c == '0') {
            p++;
            intDigits = 1;
            if (p < end && isDigit(b[p])) {
                pos = p;
                throw err("leading zero in number");
            }
        } else if (c >= '1' && c <= '9') {
            do {
                int d = b[p] - '0';
                if (digits < 19) {
                    mant = mant * 10 + d;
                    digits++;
                } else {
                    decExp++;
                    truncated |= d != 0;
                }
                intDigits++;
                p++;
            } while (p < end && isDigit(b[p]));
        } else {
            throw err("invalid number");
        }
        boolean isInt = true;
        if (p < end && b[p] == '.') {
            isInt = false;
            p++;
            if (p >= end || !isDigit(b[p])) {
                pos = p;
                throw err("invalid number: expected digit after '.'");
            }
            do {
                int d = b[p] - '0';
                if (digits < 19) {
                    mant = mant * 10 + d;
                    decExp--;
                    if (mant != 0) {
                        digits++; // leading fraction zeros don't consume the cap
                    }
                } else {
                    truncated |= d != 0;
                }
                p++;
            } while (p < end && isDigit(b[p]));
        }
        if (p < end && (b[p] == 'e' || b[p] == 'E')) {
            isInt = false;
            p++;
            boolean expNeg = false;
            if (p < end && (b[p] == '+' || b[p] == '-')) {
                expNeg = b[p] == '-';
                p++;
            }
            if (p >= end || !isDigit(b[p])) {
                pos = p;
                throw err("invalid number: expected digit in exponent");
            }
            int expVal = 0;
            do {
                if (expVal < 1_000_000) { // saturate, real exponents are tiny
                    expVal = expVal * 10 + (b[p] - '0');
                }
                p++;
            } while (p < end && isDigit(b[p]));
            decExp += expNeg ? -expVal : expVal;
        }
        pos = p;
        if (isInt) {
            if (intDigits <= 18) {
                return neg ? -mant : mant;
            }
            BigInteger bi = new BigInteger(new String(b, start, p - start, StandardCharsets.ISO_8859_1));
            if (bi.bitLength() < 64) {
                return bi.longValue();
            }
            return bi;
        }
        return toDouble(neg, mant, decExp, truncated, start, p - start);
    }

    private double toDouble(boolean neg, long mant, int decExp, boolean truncated, int start, int len) {
        if (mant == 0) {
            return neg ? -0.0 : 0.0;
        }
        double d = Double.NaN;
        if (EiselLemire.MIN_POWER_OF_TEN <= decExp && decExp <= EiselLemire.MAX_POWER_OF_TEN) {
            if (truncated) {
                // rounding of the dropped digits can only matter if mant and
                // mant+1 straddle a double boundary
                double lo = EiselLemire.tryToDouble(neg, mant, decExp);
                double hi = EiselLemire.tryToDouble(neg, mant + 1, decExp);
                if (lo == hi) {
                    d = lo;
                }
            } else {
                d = EiselLemire.tryToDouble(neg, mant, decExp);
            }
        }
        if (Double.isNaN(d)) {
            d = Double.parseDouble(new String(buf, start, len, StandardCharsets.ISO_8859_1));
        }
        return d;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    // --------------------------------------------------------------- literals

    private Object parseTrue() {
        if (end - pos >= 4 && buf[pos + 1] == 'r' && buf[pos + 2] == 'u' && buf[pos + 3] == 'e') {
            pos += 4;
            return Boolean.TRUE;
        }
        throw err("invalid literal");
    }

    private Object parseFalse() {
        if (end - pos >= 5 && buf[pos + 1] == 'a' && buf[pos + 2] == 'l' && buf[pos + 3] == 's'
                && buf[pos + 4] == 'e') {
            pos += 5;
            return Boolean.FALSE;
        }
        throw err("invalid literal");
    }

    private Object parseNull() {
        if (end - pos >= 4 && buf[pos + 1] == 'u' && buf[pos + 2] == 'l' && buf[pos + 3] == 'l') {
            pos += 4;
            return null;
        }
        throw err("invalid literal");
    }

    // ------------------------------------------------------------------ misc

    private void skipWs() {
        byte[] b = buf;
        int p = pos;
        while (p < end) {
            byte c = b[p];
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                p++;
                // bulk-skip whitespace runs (indentation in pretty-printed
                // JSON); compact JSON exits on the first check above
                while (end - p >= 8) {
                    long w = (long) LONG_LE.get(b, p);
                    long m = nonWsMask(w);
                    if (m != 0) {
                        p += Long.numberOfTrailingZeros(m) >>> 3;
                        break;
                    }
                    p += 8;
                }
            } else {
                break;
            }
        }
        pos = p;
    }

    private static final long SEVEN_F = 0x7F7F7F7F7F7F7F7FL;

    /** Exact per-lane zero test (no inter-lane carries): 0x80 where byte == 0. */
    private static long zeroMask(long v) {
        return ~(((v & SEVEN_F) + SEVEN_F) | v | SEVEN_F);
    }

    /**
     * 0x80 set in each lane holding a byte that is NOT JSON whitespace.
     * Must be exact: a false whitespace positive would skip real content.
     */
    private static long nonWsMask(long w) {
        long ws = zeroMask(w ^ 0x2020202020202020L)
                | zeroMask(w ^ 0x0909090909090909L)
                | zeroMask(w ^ 0x0A0A0A0A0A0A0A0AL)
                | zeroMask(w ^ 0x0D0D0D0D0D0D0D0DL);
        return ~ws & HIGH_BITS;
    }

    private Object[] kvBuf(int depth) {
        Object[][] bufs = kvBufs;
        if (depth >= bufs.length) {
            kvBufs = bufs = Arrays.copyOf(bufs, Math.max(depth + 1, bufs.length << 1));
        }
        Object[] a = bufs[depth];
        if (a == null) {
            bufs[depth] = a = new Object[16];
        }
        return a;
    }

    private Object[] valBuf(int depth) {
        Object[][] bufs = valBufs;
        if (depth >= bufs.length) {
            valBufs = bufs = Arrays.copyOf(bufs, Math.max(depth + 1, bufs.length << 1));
        }
        Object[] a = bufs[depth];
        if (a == null) {
            bufs[depth] = a = new Object[16];
        }
        return a;
    }

    private JsonParseException err(String msg) {
        return new JsonParseException(msg, pos);
    }
}
