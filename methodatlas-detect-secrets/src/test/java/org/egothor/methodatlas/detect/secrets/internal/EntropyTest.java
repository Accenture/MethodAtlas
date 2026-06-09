package org.egothor.methodatlas.detect.secrets.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntropyTest {

    @Test
    void uniformStringHasHighEntropy() {
        assertEquals(4.0, Entropy.shannonBitsPerChar("0123456789abcdef"), 0.0001);
    }

    @Test
    void repeatedCharHasZeroEntropy() {
        assertEquals(0.0, Entropy.shannonBitsPerChar("aaaaaaaa"), 0.0001);
    }

    @Test
    void emptyStringIsZero() {
        assertEquals(0.0, Entropy.shannonBitsPerChar(""), 0.0001);
    }

    @Test
    void realisticSecretExceedsThreeAndAHalfBits() {
        assertTrue(Entropy.shannonBitsPerChar("S3cr3tK3y_9fA2bX7zQw") > 3.5);
    }
}
