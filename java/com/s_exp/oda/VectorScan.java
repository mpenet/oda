package com.s_exp.oda;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD string scanning via the (incubating) Vector API. This is the only
 * class referencing jdk.incubator.vector: it must only be loaded after
 * JsonParser.VECTOR confirmed the module is present.
 */
final class VectorScan {

    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    static final int LANES = SPECIES.length();

    private VectorScan() {
    }

    /**
     * Advances past clean string content, stopping at the first '"', '\\'
     * or control byte. Returns {@code (index << 1) | highSeen} where index
     * is the position of the first structural byte, or the first unscanned
     * position once fewer than LANES bytes remain; highSeen reports bytes
     * >= 0x80 strictly before index.
     */
    static long scanString(byte[] b, int p, int end) {
        boolean high = false;
        while (end - p >= LANES) {
            ByteVector v = ByteVector.fromArray(SPECIES, b, p);
            VectorMask<Byte> struct = v.eq((byte) '"')
                    .or(v.eq((byte) '\\'))
                    .or(v.compare(VectorOperators.ULT, (byte) 0x20));
            if (struct.anyTrue()) {
                int i = struct.firstTrue();
                high |= (v.lt((byte) 0).toLong() & ((1L << i) - 1)) != 0;
                return ((long) (p + i) << 1) | (high ? 1L : 0L);
            }
            high |= v.lt((byte) 0).anyTrue();
            p += LANES;
        }
        return ((long) p << 1) | (high ? 1L : 0L);
    }

    /**
     * Like scanString but also stops at bytes >= 0x80 (escape-decoding path
     * needs to handle UTF-8 sequences itself). Returns the index of the
     * first special byte, or the first unscanned position.
     */
    static int scanSpecial(byte[] b, int p, int end) {
        while (end - p >= LANES) {
            ByteVector v = ByteVector.fromArray(SPECIES, b, p);
            VectorMask<Byte> m = v.eq((byte) '"')
                    .or(v.eq((byte) '\\'))
                    .or(v.compare(VectorOperators.ULT, (byte) 0x20))
                    .or(v.lt((byte) 0));
            if (m.anyTrue()) {
                return p + m.firstTrue();
            }
            p += LANES;
        }
        return p;
    }

    // fixed 128-bit shorts -> 64-bit bytes keeps the S2B conversion a
    // simple narrowing on every platform supporting the Vector API
    private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_128;
    private static final VectorSpecies<Byte> NARROW_SPECIES = ByteVector.SPECIES_64;
    private static final int SHORT_LANES = SHORT_SPECIES.length();

    /**
     * Encodes chars that need no escaping and no multi-byte UTF-8
     * (0x20..0x7f minus '"' and '\\') from cs[i..limit) into out[p...],
     * 1 byte per char. Returns the number of chars encoded; stops at the
     * first char needing scalar treatment or when fewer than a vector's
     * worth remain.
     */
    static int encodeAscii(char[] cs, int i, int limit, byte[] out, int p) {
        int n = 0;
        while (limit - (i + n) >= SHORT_LANES) {
            ShortVector v = ShortVector.fromCharArray(SHORT_SPECIES, cs, i + n);
            VectorMask<Short> dirty = v.compare(VectorOperators.ULT, (short) 0x20)
                    .or(v.compare(VectorOperators.UGE, (short) 0x80))
                    .or(v.eq((short) '"'))
                    .or(v.eq((short) '\\'));
            // store unconditionally: the caller reserves 6 bytes per char,
            // so lanes past a dirty char write scratch that the caller
            // overwrites; advancing by firstTrue keeps correctness
            ((ByteVector) v.convertShape(VectorOperators.S2B, NARROW_SPECIES, 0))
                    .intoArray(out, p + n);
            if (dirty.anyTrue()) {
                return n + dirty.firstTrue();
            }
            n += SHORT_LANES;
        }
        return n;
    }
}
