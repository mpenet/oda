// ABOUTME: Incremental JSON reader: one value per read from an InputStream or Reader.
// ABOUTME: Finds each value's end in a refillable buffer, then parses it with JsonParser.

package com.s_exp.oda;

import clojure.lang.IFn;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads successive JSON values from a stream without waiting for its end.
 * A value already buffered is parsed in place by {@link JsonParser}, in a
 * single pass. One running past the buffered input (about once per refill)
 * makes the reader pull input until the value's end, found by a light scan
 * tracking nesting and strings, then parse exactly that span. Bytes read
 * past a value stay buffered for the next read, so all reads of a stream
 * must go through the same reader. A top-level number needs one byte of
 * lookahead (or end of input) to know it ended. Not thread-safe.
 */
public final class JsonReader implements Closeable {

    private final InputStream in;
    private final IFn keyFn;
    private final int maxDepth;
    private final int maxNumberLength;
    private final boolean bigdec;

    private byte[] buf = new byte[8192];
    private int start; // next unread byte
    private int limit; // end of buffered input
    private boolean eof;
    private boolean started;
    private final int[] end = new int[1];

    // stream position of buf[0], for error reporting
    private long baseOffset;
    private int baseLine = 1;
    private int baseColumn = 1;

    public JsonReader(Object source, IFn keyFn, int maxDepth, int maxNumberLength, boolean bigdec) {
        if (source instanceof InputStream s) {
            this.in = s;
        } else if (source instanceof Reader r) {
            this.in = new Utf8Stream(r);
        } else {
            throw new IllegalArgumentException("reader source must be an InputStream or java.io.Reader, got "
                    + (source == null ? "nil" : source.getClass().getName()));
        }
        this.keyFn = keyFn;
        this.maxDepth = maxDepth;
        this.maxNumberLength = maxNumberLength;
        this.bigdec = bigdec;
    }

    /**
     * Reads the next value. At end of input (only whitespace left) returns
     * eofValue if hasEofValue, otherwise throws EOFException; a value cut
     * off by the end of input is a JsonParseException.
     */
    public Object read(boolean hasEofValue, Object eofValue) throws IOException {
        if (!started) {
            started = true;
            skipByteOrderMark();
        }
        if (!skipWhitespace()) {
            if (hasEofValue) {
                return eofValue;
            }
            throw new EOFException("end of JSON input");
        }
        // single pass when the value is already buffered: parse straight
        // from the buffer, the rest stays for the next read
        while (true) {
            try {
                Object v = JsonParser.parsePrefix(buf, start, limit - start, keyFn, maxDepth,
                        maxNumberLength, bigdec, end);
                int e = end[0];
                if (e == limit && !eof && isNumberStart(buf[start])) {
                    fill(); // a number reaching the end of buffered input may go on
                    continue;
                }
                start = e;
                return v;
            } catch (JsonParseException e) {
                if (eof) {
                    throw relocate(e, start);
                }
                break; // incomplete, or invalid: find the end before judging
            }
        }
        // the value runs past buffered input (about once per refill): read
        // up to its end, then parse exactly that span
        int len = valueLength();
        int at = start;
        start += len;
        try {
            return JsonParser.parse(buf, at, len, keyFn, maxDepth, maxNumberLength, bigdec);
        } catch (JsonParseException e) {
            throw relocate(e, at);
        }
    }

    private static boolean isNumberStart(byte c) {
        return c == '-' || (c >= '0' && c <= '9');
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ------------------------------------------------------------- buffering

    /** Reads more input; false at end of input. May move unread bytes to 0. */
    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        if (limit == buf.length) {
            if (start > 0) {
                compact();
            } else {
                buf = Arrays.copyOf(buf, buf.length << 1);
            }
        }
        int n;
        do {
            n = in.read(buf, limit, buf.length - limit);
        } while (n == 0);
        if (n < 0) {
            eof = true;
            return false;
        }
        limit += n;
        return true;
    }

    /** Drops consumed bytes, keeping the stream position of buf[0] current. */
    private void compact() {
        advanceBase(start);
        System.arraycopy(buf, start, buf, 0, limit - start);
        limit -= start;
        start = 0;
    }

    private void advanceBase(int to) {
        long lc = lineColumn(baseLine, baseColumn, to);
        baseLine = (int) (lc >>> 32);
        baseColumn = (int) lc;
        baseOffset += to;
    }

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long NEWLINES = 0x0A0A0A0A0A0A0A0AL;
    private static final long TOP_TWO_BITS = 0xC0C0C0C0C0C0C0C0L;
    private static final long CONTINUATION = 0x8080808080808080L;

    /**
     * Line and column (code points) after buf[0, to), starting from the given
     * ones at buf[0]: (line << 32) | column. Newlines are counted 8 bytes at
     * a time by popcount; columns only over the bytes after the last one.
     */
    private long lineColumn(int line, int column, int to) {
        byte[] b = buf;
        int newlines = 0;
        int i = 0;
        for (; to - i >= 8; i += 8) {
            newlines += Long.bitCount(JsonParser.zeroMask((long) LONG_LE.get(b, i) ^ NEWLINES));
        }
        for (; i < to; i++) {
            if (b[i] == '\n') {
                newlines++;
            }
        }
        if (newlines == 0) {
            return ((long) line << 32) | (column + codePoints(0, to));
        }
        int last = to - 1;
        while (b[last] != '\n') {
            last--;
        }
        return ((long) (line + newlines) << 32) | (1 + codePoints(last + 1, to));
    }

    /** Code points in buf[from, to): bytes that are not UTF-8 continuations. */
    private int codePoints(int from, int to) {
        byte[] b = buf;
        int continuations = 0;
        int i = from;
        for (; to - i >= 8; i += 8) {
            long w = (long) LONG_LE.get(b, i);
            continuations += Long.bitCount(JsonParser.zeroMask((w & TOP_TWO_BITS) ^ CONTINUATION));
        }
        for (; i < to; i++) {
            if ((b[i] & 0xC0) == 0x80) {
                continuations++;
            }
        }
        return to - from - continuations;
    }

    private void skipByteOrderMark() throws IOException {
        while (limit - start < 3 && fill()) {
            // need three bytes to tell
        }
        if (limit - start >= 3 && buf[start] == (byte) 0xEF && buf[start + 1] == (byte) 0xBB
                && buf[start + 2] == (byte) 0xBF) {
            start += 3;
        }
    }

    /** Skips whitespace; false if input ended first. */
    private boolean skipWhitespace() throws IOException {
        while (true) {
            while (start < limit) {
                byte c = buf[start];
                if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
                    return true;
                }
                start++;
            }
            if (!fill()) {
                return false;
            }
        }
    }

    // --------------------------------------------------------------- framing

    /**
     * Length of the value starting at buf[start], reading input until its
     * end is buffered. Malformed or truncated input just ends the span early
     * (at the offending byte or at end of input): JsonParser then reports
     * the precise error.
     */
    private int valueLength() throws IOException {
        int c = buf[start] & 0xff;
        switch (c) {
            case '{':
            case '[':
                return containerLength();
            case '"':
                return stringEnd(1);
            case 't':
            case 'n':
                return fixedLength(4);
            case 'f':
                return fixedLength(5);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return numberLength();
                }
                return 1;
        }
    }

    private int fixedLength(int n) throws IOException {
        while (limit - start < n && fill()) {
            // literals are self-delimiting: no lookahead past them
        }
        return Math.min(n, limit - start);
    }

    private int numberLength() throws IOException {
        int k = 1;
        while (true) {
            while (start + k < limit) {
                byte b = buf[start + k];
                if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '-' || b == '+') {
                    k++;
                } else {
                    return k;
                }
            }
            if (!fill()) {
                return k;
            }
        }
    }

    /** Length up to and including the closing quote of a string whose content starts at k. */
    private int stringEnd(int k) throws IOException {
        while (true) {
            while (start + k < limit) {
                byte b = buf[start + k];
                if (b == '"') {
                    return k + 1;
                }
                k += b == '\\' ? 2 : 1;
            }
            if (!fill()) {
                return limit - start;
            }
        }
    }

    private int containerLength() throws IOException {
        int depth = 0;
        int k = 0;
        while (true) {
            while (start + k < limit) {
                byte b = buf[start + k];
                if (b == '"') {
                    k = stringEnd(k + 1);
                    continue;
                }
                if (b == '{' || b == '[') {
                    if (++depth > maxDepth) {
                        return k + 1; // the parser reports the depth error
                    }
                } else if (b == '}' || b == ']') {
                    if (--depth == 0) {
                        return k + 1;
                    }
                }
                k++;
            }
            if (!fill()) {
                return limit - start;
            }
        }
    }

    // ---------------------------------------------------------------- errors

    /**
     * Rebases an error reported relative to the value at buf[at] onto the
     * whole stream. Cold path: scans the buffer prefix for line/column.
     */
    private JsonParseException relocate(JsonParseException e, int at) {
        long lc = lineColumn(baseLine, baseColumn, at);
        int line = (int) (lc >>> 32);
        int column = (int) lc;
        if (e.line == 1) {
            column += e.column - 1;
        } else {
            line += e.line - 1;
            column = e.column;
        }
        return new JsonParseException(e.reason, baseOffset + e.position, line, column);
    }

    // ----------------------------------------------------------- Reader input

    /**
     * Encodes a Reader's chars to UTF-8 bytes. Surrogate pairs split across
     * reads are kept until complete; lone surrogates become U+FFFD, as in
     * the writer.
     */
    private static final class Utf8Stream extends InputStream {
        private final Reader reader;
        private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD});
        private final CharBuffer chars = CharBuffer.allocate(4096).flip();
        private final ByteBuffer bytes = ByteBuffer.allocate(16384).flip();
        private boolean readerEof;
        private boolean flushed;

        Utf8Stream(Reader reader) {
            this.reader = reader;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!bytes.hasRemaining() && !encodeMore()) {
                return -1;
            }
            int n = Math.min(len, bytes.remaining());
            bytes.get(b, off, n);
            return n;
        }

        /** Refills the (drained) byte buffer; false at end of input. */
        private boolean encodeMore() throws IOException {
            if (flushed) {
                return false;
            }
            bytes.clear();
            while (true) {
                encoder.encode(chars, bytes, readerEof);
                if (readerEof && !chars.hasRemaining()) {
                    encoder.flush(bytes);
                    flushed = true;
                    bytes.flip();
                    return bytes.hasRemaining();
                }
                if (bytes.position() > 0) {
                    bytes.flip();
                    return true;
                }
                chars.compact(); // keeps a high surrogate awaiting its pair
                int m = reader.read(chars);
                chars.flip();
                if (m < 0) {
                    readerEof = true;
                }
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
