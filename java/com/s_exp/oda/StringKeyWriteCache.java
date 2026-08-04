package com.s_exp.oda;

/**
 * Open-addressing cache from String map keys to their pre-escaped JSON key
 * fragment ({@code "key":}). Unlike keywords, strings are not interned, so
 * probing costs hashCode (cached by String) + equals. Bounded to MAX_ENTRIES;
 * long keys are not cached to bound retained memory.
 */
final class StringKeyWriteCache {
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_KEY_LENGTH = 64;

    private String[] keys = new String[1024];
    private byte[][] vals = new byte[1024][];
    private int size;

    byte[] fragment(String s) {
        if (s.length() > MAX_KEY_LENGTH) {
            return null; // caller escapes inline
        }
        String[] ks = keys;
        int mask = ks.length - 1;
        int i = spread(s.hashCode()) & mask;
        while (true) {
            String c = ks[i];
            if (c == null) {
                break;
            }
            if (c.equals(s)) {
                return vals[i];
            }
            i = (i + 1) & mask;
        }
        byte[] frag = JsonWriter.escapedKeyFragment(s);
        if (size < MAX_ENTRIES) {
            if ((size + 1) * 2 > ks.length) {
                rehash();
                ks = keys;
                mask = ks.length - 1;
                i = spread(s.hashCode()) & mask;
                while (ks[i] != null) {
                    i = (i + 1) & mask;
                }
            }
            ks[i] = s;
            vals[i] = frag;
            size++;
        }
        return frag;
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private void rehash() {
        String[] oldKeys = keys;
        byte[][] oldVals = vals;
        int cap = oldKeys.length << 1;
        String[] newKeys = new String[cap];
        byte[][] newVals = new byte[cap][];
        int mask = cap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            String k = oldKeys[i];
            if (k == null) {
                continue;
            }
            int j = spread(k.hashCode()) & mask;
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
