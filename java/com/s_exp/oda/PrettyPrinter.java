// ABOUTME: Reformats compact JSON bytes in the layout of Jackson's DefaultPrettyPrinter.
// ABOUTME: Works chunk by chunk, so streamed output is formatted as it is flushed.

package com.s_exp.oda;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Pretty prints the writer's own compact output (no whitespace, valid
 * JSON), as a separate pass so the compact path pays nothing for it.
 * Layout of Jackson's DefaultPrettyPrinter, the one cheshire and jsonista
 * use: one object entry per line, indented two spaces per enclosing object,
 * {@code " : "} after keys; arrays inline as {@code [ 1, 2 ]}; empty
 * containers {@code { }} and {@code [ ]}; {@code \n} line ends.
 *
 * Input may be split anywhere (inside strings, escapes, between a
 * container's open and close): all decisions are carried over in fields.
 */
final class PrettyPrinter {

    private byte[] buf = new byte[1024];
    private int n;
    private OutputStream out; // non-null: flush to it when full

    private boolean inString;
    private boolean escaped;
    /** A container just opened: its first byte decides "{ }" or a line break. */
    private boolean opened;
    /** Per open container, innermost last: true for objects. */
    private boolean[] objects = new boolean[64];
    private int depth;
    private int indent; // open objects

    void reset(OutputStream out) {
        this.out = out;
        if (out != null && buf.length < (1 << 16)) {
            buf = new byte[1 << 16];
        }
        n = 0;
        inString = false;
        escaped = false;
        opened = false;
        depth = 0;
        indent = 0;
    }

    byte[] buf() {
        return buf;
    }

    int length() {
        return n;
    }

    void feed(byte[] src, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end) {
            if (inString) {
                i = copyString(src, i, end);
                continue;
            }
            byte b = src[i++];
            if (opened) {
                opened = false;
                if (b == '}' || b == ']') {
                    if (b == '}') {
                        indent--;
                    }
                    depth--;
                    put((byte) ' ');
                    put(b);
                    continue;
                }
                if (objects[depth - 1]) {
                    newline();
                } else {
                    put((byte) ' ');
                }
            }
            switch (b) {
                case '"' -> {
                    inString = true;
                    put(b);
                }
                case '{' -> {
                    open(true);
                    indent++;
                    put(b);
                }
                case '[' -> {
                    open(false);
                    put(b);
                }
                case ',' -> {
                    put(b);
                    if (objects[depth - 1]) {
                        newline();
                    } else {
                        put((byte) ' ');
                    }
                }
                case ':' -> {
                    ensure(3);
                    buf[n++] = ' ';
                    buf[n++] = ':';
                    buf[n++] = ' ';
                }
                case '}' -> {
                    indent--;
                    depth--;
                    newline();
                    put(b);
                }
                case ']' -> {
                    depth--;
                    ensure(2);
                    buf[n++] = ' ';
                    buf[n++] = ']';
                }
                default -> put(b);
            }
        }
    }

    /** Copies string content up to and including its closing quote. */
    private int copyString(byte[] src, int i, int end) {
        int start = i;
        boolean esc = escaped;
        while (i < end) {
            byte b = src[i++];
            if (esc) {
                esc = false;
            } else if (b == '\\') {
                esc = true;
            } else if (b == '"') {
                inString = false;
                break;
            }
        }
        escaped = esc;
        int k = i - start;
        ensure(k);
        System.arraycopy(src, start, buf, n, k);
        n += k;
        return i;
    }

    private void open(boolean object) {
        if (depth == objects.length) {
            objects = Arrays.copyOf(objects, depth << 1);
        }
        objects[depth++] = object;
        opened = true;
    }

    private void newline() {
        int k = 1 + 2 * indent;
        ensure(k);
        buf[n] = '\n';
        Arrays.fill(buf, n + 1, n + k, (byte) ' ');
        n += k;
    }

    private void put(byte b) {
        ensure(1);
        buf[n++] = b;
    }

    private void ensure(int k) {
        if (n + k > buf.length) {
            if (out != null) {
                flush();
            }
            if (n + k > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(n + k, buf.length << 1));
            }
        }
    }

    void flush() {
        if (n > 0) {
            try {
                out.write(buf, 0, n);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            n = 0;
        }
    }

    /** Drops the stream and oversized buffers before pooling. */
    void release() {
        out = null;
        if (buf.length > (1 << 23)) {
            buf = new byte[1 << 16];
        }
        if (objects.length > 1024) {
            objects = new boolean[64];
        }
    }
}
