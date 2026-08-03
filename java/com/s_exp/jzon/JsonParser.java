package com.s_exp.jzon;

import ch.randelshofer.fastdoubleparser.JsonDoubleParser;
import clojure.lang.ITransientMap;
import clojure.lang.Keyword;
import clojure.lang.LazilyPersistentVector;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.Util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Recursive-descent JSON parser building Clojure persistent data structures
 * directly from UTF-8 bytes. Single monomorphic implementation: tokenizer and
 * builder are fused so the JIT can inline the whole pipeline.
 */
public final class JsonParser {

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final ThreadLocal<KeywordCache> KEYWORD_CACHE =
            ThreadLocal.withInitial(KeywordCache::new);

    private final byte[] buf;
    private final int end;
    private final boolean keywordize;
    private final int maxDepth;
    private final KeywordCache keywords;
    private int pos;

    private char[] cbuf = new char[64];
    private Object[][] kvBufs = new Object[16][];
    private Object[][] valBufs = new Object[16][];

    private JsonParser(byte[] buf, int offset, int length, boolean keywordize, int maxDepth) {
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
        this.keywordize = keywordize;
        this.maxDepth = maxDepth;
        this.keywords = keywordize ? KEYWORD_CACHE.get() : null;
    }

    public static Object parse(byte[] buf, int offset, int length, boolean keywordize, int maxDepth) {
        JsonParser p = new JsonParser(buf, offset, length, keywordize, maxDepth);
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
        if (!keywordize) {
            return parseString();
        }
        byte[] b = buf;
        int start = pos;
        int p = start;
        while (end - p >= 8) {
            long w = (long) LONG_LE.get(b, p);
            long m = specialMask(w);
            if (m != 0) {
                p += Long.numberOfTrailingZeros(m) >>> 3;
                break;
            }
            p += 8;
        }
        while (p < end) {
            int c = b[p] & 0xff;
            if (c == '"') {
                pos = p + 1;
                return keywords.intern(b, start, p - start);
            }
            if (c == '\\' || c >= 0x80 || c < 0x20) {
                // escapes / non-ASCII / errors: rescan via the string path
                return Keyword.intern(parseString());
            }
            p++;
        }
        throw err("unterminated string");
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
        boolean utf8 = false;
        while (true) {
            while (end - p >= 8) {
                long w = (long) LONG_LE.get(b, p);
                long m = specialMask(w);
                if (m != 0) {
                    p += Long.numberOfTrailingZeros(m) >>> 3;
                    break;
                }
                p += 8;
            }
            if (p >= end) {
                throw err("unterminated string");
            }
            int c = b[p] & 0xff;
            if (c == '"') {
                String s = new String(b, start, p - start,
                        utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                pos = p + 1;
                return s;
            } else if (c == '\\') {
                return parseStringSlow(start);
            } else if (c >= 0x80) {
                utf8 = true;
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
            if (p >= end) {
                throw err("unterminated string");
            }
            if (n + 2 > out.length) {
                out = Arrays.copyOf(out, out.length << 1);
                cbuf = out;
            }
            int c = b[p] & 0xff;
            if (c == '"') {
                pos = p + 1;
                return new String(out, 0, n);
            } else if (c == '\\') {
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
                        // hex() returns -1 on invalid input; -1 survives the shifts
                        // with sign bit set, so one v < 0 check covers all four digits
                        int v = (hex(b[p]) << 12) | (hex(b[p + 1]) << 8) | (hex(b[p + 2]) << 4) | hex(b[p + 3]);
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
            } else if (c < 0x80) {
                out[n++] = (char) c;
                p++;
            } else {
                // lenient UTF-8 decode: malformed sequences become U+FFFD,
                // matching the behavior of the no-escape fast path (String ctor)
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
                if (cp >= 0x10000) {
                    out[n++] = (char) (0xD800 + ((cp - 0x10000) >>> 10));
                    out[n++] = (char) (0xDC00 + ((cp - 0x10000) & 0x3FF));
                } else {
                    out[n++] = (char) cp;
                }
            }
        }
    }

    private static boolean cont(byte b) {
        return (b & 0xC0) == 0x80;
    }

    private static int hex(byte b) {
        int c = b & 0xff;
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * Returns a mask with 0x80 set in each lane holding a byte that is
     * '"', '\\', &lt; 0x20 or &gt;= 0x80. Borrow-chain false positives can only
     * occur in lanes above a true positive, so the lowest set bit is exact.
     */
    private static long specialMask(long w) {
        long quotes = w ^ 0x2222222222222222L;
        quotes = (quotes - 0x0101010101010101L) & ~quotes;
        long bs = w ^ 0x5C5C5C5C5C5C5C5CL;
        bs = (bs - 0x0101010101010101L) & ~bs;
        long ctrl = (w - 0x2020202020202020L) & ~w;
        long high = w;
        return (quotes | bs | ctrl | high) & 0x8080808080808080L;
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
        long acc = 0;
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
                acc = acc * 10 + (b[p] - '0');
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
                p++;
            } while (p < end && isDigit(b[p]));
        }
        if (p < end && (b[p] == 'e' || b[p] == 'E')) {
            isInt = false;
            p++;
            if (p < end && (b[p] == '+' || b[p] == '-')) {
                p++;
            }
            if (p >= end || !isDigit(b[p])) {
                pos = p;
                throw err("invalid number: expected digit in exponent");
            }
            do {
                p++;
            } while (p < end && isDigit(b[p]));
        }
        pos = p;
        if (isInt) {
            if (intDigits <= 18) {
                return neg ? -acc : acc;
            }
            BigInteger bi = new BigInteger(new String(b, start, p - start, StandardCharsets.ISO_8859_1));
            if (bi.bitLength() < 64) {
                return bi.longValue();
            }
            return bi;
        }
        return JsonDoubleParser.parseDouble(b, start, p - start);
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
            } else {
                break;
            }
        }
        pos = p;
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
