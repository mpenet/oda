package com.s_exp.oda;

import java.util.Arrays;

/**
 * Fixed-size, lossy canonicalization caches from raw ASCII key byte spans to
 * key objects. One-way associative: a colliding insert overwrites the slot.
 *
 * Entries are immutable, so racy unsynchronized reads are safe (final-field
 * publication guarantees no torn entry); a racy write at worst drops a cached
 * entry. No locks, no growth, hard global memory bound — safe with thread
 * pools and virtual threads, unlike thread-local caches.
 */
final class KeyCache {

    static final class Entry {
        final byte[] bytes;
        final Object key;

        Entry(byte[] bytes, Object key) {
            this.bytes = bytes;
            this.key = key;
        }
    }

    private static final int GLOBAL_SIZE = 8192;

    /** Shared tables for the two canonical key modes. */
    static final Entry[] KEYWORDS = new Entry[GLOBAL_SIZE];
    static final Entry[] STRINGS = new Entry[GLOBAL_SIZE];

    /** Size of the per-parse table used with a custom key-fn. */
    static final int CUSTOM_SIZE = 512;

    private KeyCache() {
    }

    static Object lookup(Entry[] table, byte[] buf, int off, int len, int hash) {
        Entry e = table[hash & (table.length - 1)];
        if (e != null && e.bytes.length == len
                && Arrays.equals(e.bytes, 0, len, buf, off, off + len)) {
            return e.key;
        }
        return null;
    }

    static void store(Entry[] table, byte[] buf, int off, int len, int hash, Object key) {
        table[hash & (table.length - 1)] = new Entry(Arrays.copyOfRange(buf, off, off + len), key);
    }

    static int hash(byte[] b, int off, int len) {
        int h = 0x811c9dc5;
        for (int i = off; i < off + len; i++) {
            h ^= b[i];
            h *= 0x01000193;
        }
        return h ^ (h >>> 16);
    }
}
