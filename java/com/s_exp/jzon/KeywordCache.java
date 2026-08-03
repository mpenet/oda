package com.s_exp.jzon;

import clojure.lang.Keyword;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Open-addressing cache from raw ASCII byte spans to interned Keywords.
 * Avoids materializing a String per key on the hot path. Bounded to
 * MAX_ENTRIES to prevent adversarial blowup; past that, misses fall
 * through to plain interning without caching.
 */
final class KeywordCache {
    private static final int MAX_ENTRIES = 4096;

    private byte[][] keys = new byte[1024][];
    private Keyword[] vals = new Keyword[1024];
    private int size;

    Keyword intern(byte[] buf, int off, int len) {
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
        Keyword kw = Keyword.intern(new String(buf, off, len, StandardCharsets.ISO_8859_1));
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
            vals[i] = kw;
            size++;
        }
        return kw;
    }

    private void rehash() {
        byte[][] oldKeys = keys;
        Keyword[] oldVals = vals;
        int cap = oldKeys.length << 1;
        byte[][] newKeys = new byte[cap][];
        Keyword[] newVals = new Keyword[cap];
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
