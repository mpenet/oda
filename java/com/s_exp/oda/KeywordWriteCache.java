package com.s_exp.oda;

import clojure.lang.Keyword;

/**
 * Open-addressing identity cache from Keyword to its pre-escaped JSON object
 * key fragment: quotes, escaped ns/name, trailing colon (e.g. {@code "a/b":}).
 * Keywords are interned so identity comparison suffices. Bounded to
 * MAX_ENTRIES; past that, misses build the fragment without caching.
 */
final class KeywordWriteCache {
    private static final int MAX_ENTRIES = 4096;

    private Keyword[] keys = new Keyword[1024];
    private byte[][] vals = new byte[1024][];
    private int size;

    byte[] fragment(Keyword k) {
        Keyword[] ks = keys;
        int mask = ks.length - 1;
        int i = k.hashCode() & mask;
        while (true) {
            Keyword c = ks[i];
            if (c == null) {
                break;
            }
            if (c == k) {
                return vals[i];
            }
            i = (i + 1) & mask;
        }
        byte[] frag = JsonWriter.escapedKeyFragment(k.sym.toString());
        if (size < MAX_ENTRIES) {
            if ((size + 1) * 2 > ks.length) {
                rehash();
                ks = keys;
                mask = ks.length - 1;
                i = k.hashCode() & mask;
                while (ks[i] != null) {
                    i = (i + 1) & mask;
                }
            }
            ks[i] = k;
            vals[i] = frag;
            size++;
        }
        return frag;
    }

    private void rehash() {
        Keyword[] oldKeys = keys;
        byte[][] oldVals = vals;
        int cap = oldKeys.length << 1;
        Keyword[] newKeys = new Keyword[cap];
        byte[][] newVals = new byte[cap][];
        int mask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            Keyword k = oldKeys[i];
            if (k == null) {
                continue;
            }
            int j = k.hashCode() & mask;
            while (newKeys[j] != null) {
                j = (j + 1) & mask;
            }
            newKeys[j] = k;
            newVals[j] = oldVals[i];
        }
        keys = newKeys;
        vals = newVals;
    }
}
