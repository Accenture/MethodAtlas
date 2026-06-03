// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvidenceFrameworkTest {

    @Test
    void canonicalTokensAreStable() {
        assertEquals("ASVS", EvidenceFramework.ASVS.canonicalToken());
        assertEquals("PCI-6.4.1", EvidenceFramework.PCI_6_4_1.canonicalToken());
        assertEquals("NIST-SSDF-PW.8", EvidenceFramework.NIST_SSDF_PW8.canonicalToken());
        assertEquals("ISO-27001-8.29", EvidenceFramework.ISO_27001_8_29.canonicalToken());
    }

    @Test
    void parseMatchesEveryCanonicalToken() {
        assertSame(EvidenceFramework.ASVS, EvidenceFramework.parse("ASVS"));
        assertSame(EvidenceFramework.PCI_6_4_1, EvidenceFramework.parse("PCI-6.4.1"));
        assertSame(EvidenceFramework.NIST_SSDF_PW8, EvidenceFramework.parse("NIST-SSDF-PW.8"));
        assertSame(EvidenceFramework.ISO_27001_8_29, EvidenceFramework.parse("ISO-27001-8.29"));
    }

    @Test
    void parseIsCaseInsensitive() {
        assertSame(EvidenceFramework.ASVS, EvidenceFramework.parse("asvs"));
        assertSame(EvidenceFramework.PCI_6_4_1, EvidenceFramework.parse("pci-6.4.1"));
        assertSame(EvidenceFramework.NIST_SSDF_PW8, EvidenceFramework.parse("nist-ssdf-pw.8"));
        assertSame(EvidenceFramework.ISO_27001_8_29, EvidenceFramework.parse("iso-27001-8.29"));
    }

    @Test
    void parseRejectsUnknownTokenAndListsValidValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EvidenceFramework.parse("HIPAA"));
        String msg = e.getMessage();
        assertTrue(msg.contains("HIPAA"), () -> "Message should echo the bad token: " + msg);
        assertTrue(msg.contains("ASVS"), () -> "Message should list ASVS: " + msg);
        assertTrue(msg.contains("PCI-6.4.1"), () -> "Message should list PCI-6.4.1: " + msg);
        assertTrue(msg.contains("NIST-SSDF-PW.8"), () -> "Message should list NIST-SSDF-PW.8: " + msg);
        assertTrue(msg.contains("ISO-27001-8.29"), () -> "Message should list ISO-27001-8.29: " + msg);
    }
}
