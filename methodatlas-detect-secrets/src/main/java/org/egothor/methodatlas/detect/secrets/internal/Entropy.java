package org.egothor.methodatlas.detect.secrets.internal;

import java.util.Arrays;

/**
 * Shannon-entropy helper for the unanchored high-entropy detection pass.
 *
 * @since 4.1.0
 */
public final class Entropy {

    /** Natural log of 2, used to convert {@link Math#log(double)} to base-2. */
    private static final double LOG2 = Math.log(2);

    private Entropy() {
        // utility class
    }

    /**
     * Computes the Shannon entropy of {@code s} in bits per character.
     *
     * <p>
     * Character frequencies are computed by sorting a {@code char[]} copy and
     * counting runs of equal characters, which is allocation-light (one small
     * array, no autoboxing) and correct for the full char range.
     * </p>
     *
     * @param s input string; never {@code null}
     * @return entropy in bits per character; {@code 0.0} for an empty string
     */
    public static double shannonBitsPerChar(String s) {
        if (s.isEmpty()) {
            return 0.0;
        }
        char[] chars = s.toCharArray();
        Arrays.sort(chars);
        double entropy = 0.0;
        double len = chars.length;
        int runStart = 0;
        for (int i = 1; i <= chars.length; i++) {
            if (i == chars.length || chars[i] != chars[runStart]) {
                double p = (i - runStart) / len;
                entropy -= p * (Math.log(p) / LOG2);
                runStart = i;
            }
        }
        return entropy;
    }
}
