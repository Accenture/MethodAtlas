package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CredentialMaskerTest {

    @Test
    void masksMiddlePreservingLength() {
        String masked = CredentialMasker.mask("AKIAIOSFODNN7EXAMPLE");
        assertEquals("AKIAIOSFODNN7EXAMPLE".length(), masked.length());
        assertTrue(masked.startsWith("AKIA"));
        assertTrue(masked.endsWith("MPLE"));
        assertTrue(masked.contains("•"));
    }

    @Test
    void shortValueFullyMasked() {
        String masked = CredentialMasker.mask("abcd");
        assertEquals("••••", masked);
    }

    @Test
    void emptyValueMasksToEmpty() {
        assertEquals("", CredentialMasker.mask(""));
    }
}
