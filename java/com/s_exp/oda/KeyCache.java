package com.s_exp.oda;

import clojure.lang.Keyword;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Open-addressing cache from raw ASCII byte spans to interned key objects
 * (Keyword or String depending on mode). Avoids materializing a String per
 * key on the hot path. Bounded to MAX_ENTRIES to prevent adversarial blowup;
 * past that, misses fall through to plain interning without caching.
 */
final class KeyCache {
    private static final int MAX_ENTRIES = 4096;

    private final boolean keyword;
    private byte[][] keys = new byte[1024][];
    private Object[] vals = new Object[1024];
    private int size;

    KeyCache(boolean keyword) {
        this.keyword = keyword;
    }

    Object intern(byte[] buf, int off, int len) {
        byte[][] ks = keys;
        int mask = ks.length - 1;
        int i = hash(buf, off, len) & mask;
        while (true) {
            byte[] k = ks[i];
            if (k == null) {
                break;
            }
            if (k.length == len && Arrays.equals(k, 0, len, buf, off, off + len)) {
                return vals[i];
            }
            i = (i + 1) & mask;
        }
        String s = new String(buf, off, len, StandardCharsets.ISO_8859_1);
        Object v = keyword ? Keyword.intern(s) : s;
        if (size < MAX_ENTRIES) {
            if ((size + 1) * 2 > ks.length) {
                rehash();
                ks = keys;
                mask = ks.length - 1;
                i = hash(buf, off, len) & mask;
                while (ks[i] != null) {
                    i = (i + 1) & mask;
                }
            }
            ks[i] = Arrays.copyOfRange(buf, off, off + len);
            vals[i] = v;
            size++;
        }
        return v;
    }

    private void rehash() {
        byte[][] oldKeys = keys;
        Object[] oldVals = vals;
        int cap = oldKeys.length << 1;
        byte[][] newKeys = new byte[cap][];
        Object[] newVals = new Object[cap];
        int mask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            byte[] k = oldKeys[i];
            if (k == null) {
                continue;
            }
            int j = hash(k, 0, k.length) & mask;
            while (newKeys[j] != null) {
                j = (j + 1) & mask;
            }
            newKeys[j] = k;
            newVals[j] = oldVals[i];
        }
        keys = newKeys;
        vals = newVals;
    }

    private static int hash(byte[] b, int off, int len) {
        int h = 0x811c9dc5;
        for (int i = off; i < off + len; i++) {
            h ^= b[i];
            h *= 0x01000193;
        }
        return h ^ (h >>> 16);
    }
}
