package com.s_exp.oda;

import java.math.BigInteger;

/**
 * Decimal (significand x 10^power) to correctly-rounded double conversion
 * using the Eisel-Lemire algorithm in its "Fast Number Parsing Without
 * Fallback" variant (Noble Mushtak, Daniel Lemire, 2023,
 * https://arxiv.org/pdf/2212.06644.pdf).
 *
 * Ported from FastDoubleParser's FastDoubleMath (MIT License,
 * Copyright (c) Werner Randelshofer), with the power-of-ten mantissa table
 * generated at class initialization instead of stored as literals.
 *
 * Returns NaN when the fast algorithm cannot guarantee correct rounding;
 * callers must fall back to a slow path (e.g. Double.parseDouble). JSON
 * cannot express NaN, so the sentinel is unambiguous.
 */
final class EiselLemire {

    static final int MIN_POWER_OF_TEN = -325;
    static final int MAX_POWER_OF_TEN = 308;

    private static final int DOUBLE_EXPONENT_BIAS = 1023;
    private static final int DOUBLE_SIGNIFICAND_WIDTH = 53;
    private static final int DOUBLE_MAX_EXPONENT_POWER_OF_TWO = 1023;

    /** Exact double representations of 10^0 to 10^22 (Clinger fast path). */
    private static final double[] POWERS_OF_TEN = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    /**
     * The mantissas of powers of ten from 10^MIN_POWER_OF_TEN to
     * 10^MAX_POWER_OF_TEN, normalized to a leading 1 bit and truncated
     * (never rounded up) to 64 bits.
     */
    private static final long[] MANTISSA_64 = computeMantissaTable();

    private EiselLemire() {
    }

    private static long[] computeMantissaTable() {
        long[] t = new long[MAX_POWER_OF_TEN - MIN_POWER_OF_TEN + 1];
        for (int q = MIN_POWER_OF_TEN; q <= MAX_POWER_OF_TEN; q++) {
            BigInteger m;
            if (q >= 0) {
                BigInteger v = BigInteger.TEN.pow(q);
                int bl = v.bitLength();
                m = bl <= 64 ? v.shiftLeft(64 - bl) : v.shiftRight(bl - 64);
            } else {
                // 10^q = 1 / 10^-q: floor(2^(63+L) / 10^-q) lies in [2^63, 2^64)
                BigInteger d = BigInteger.TEN.pow(-q);
                m = BigInteger.ONE.shiftLeft(63 + d.bitLength()).divide(d);
            }
            t[q - MIN_POWER_OF_TEN] = m.longValue();
        }
        return t;
    }

    /**
     * Computes significand x 10^power as a correctly rounded double, negated
     * if isNegative. Assumes significand != 0 and power in
     * [MIN_POWER_OF_TEN, MAX_POWER_OF_TEN]. Returns NaN when correct rounding
     * cannot be guaranteed.
     */
    static double tryToDouble(boolean isNegative, long significand, int power) {
        // Clinger fast path: both operands exactly representable
        if (-22 <= power && power <= 22
                && Long.compareUnsigned(significand, (1L << DOUBLE_SIGNIFICAND_WIDTH) - 1) <= 0) {
            double d = (double) significand;
            if (power < 0) {
                d = d / POWERS_OF_TEN[-power];
            } else {
                d = d * POWERS_OF_TEN[power];
            }
            return isNegative ? -d : d;
        }

        long factorMantissa = MANTISSA_64[power - MIN_POWER_OF_TEN];
        // exponent of the result: 1023 bias + 64-bit word width +
        // floor(log2(5^power)) + power, the log2 via the 152170/2^16 trick
        long exponent = (((152170L + 65536L) * power) >> 16) + DOUBLE_EXPONENT_BIAS + 64;
        int lz = Long.numberOfLeadingZeros(significand);
        long shiftedSignificand = significand << lz;
        long upper = Math.unsignedMultiplyHigh(shiftedSignificand, factorMantissa);

        long upperbit = upper >>> 63;
        long mantissa = upper >>> (upperbit + 9);
        lz += (int) (1 ^ upperbit);

        // round-to-even guard: bail out when we might sit exactly between
        // two doubles (see Mushtak & Lemire 2023 for why this suffices)
        if (((upper & 0x1ff) == 0x1ff)
                || ((upper & 0x1ff) == 0) && (mantissa & 3) == 1) {
            return Double.NaN;
        }

        mantissa += 1;
        mantissa >>>= 1;

        if (mantissa >= (1L << DOUBLE_SIGNIFICAND_WIDTH)) {
            // e.g. 7.2057594037927933e+16 rounds up past 2^53
            mantissa = 1L << (DOUBLE_SIGNIFICAND_WIDTH - 1);
            lz--;
        }

        mantissa &= ~(1L << (DOUBLE_SIGNIFICAND_WIDTH - 1));

        long realExponent = exponent - lz;
        if (realExponent < 1 || realExponent > DOUBLE_MAX_EXPONENT_POWER_OF_TWO + DOUBLE_EXPONENT_BIAS) {
            return Double.NaN;
        }

        long bits = mantissa
                | realExponent << (DOUBLE_SIGNIFICAND_WIDTH - 1)
                | (isNegative ? 1L << 63 : 0L);
        return Double.longBitsToDouble(bits);
    }
}
