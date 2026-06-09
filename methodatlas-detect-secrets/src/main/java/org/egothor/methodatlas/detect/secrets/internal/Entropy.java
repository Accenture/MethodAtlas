package org.egothor.methodatlas.detect.secrets.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * Shannon-entropy helper for the unanchored high-entropy detection pass.
 *
 * @since 4.1.0
 */
public final class Entropy {

    private Entropy() {
        // utility class
    }

    /**
     * Computes the Shannon entropy of {@code s} in bits per character.
     *
     * @param s input string; never {@code null}
     * @return entropy in bits per character; {@code 0.0} for an empty string
     */
    public static double shannonBitsPerChar(String s) {
        if (s.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < s.length(); i++) {
            counts.merge(s.charAt(i), 1, Integer::sum);
        }
        double entropy = 0.0;
        double len = s.length();
        for (int count : counts.values()) {
            double p = count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }
}
