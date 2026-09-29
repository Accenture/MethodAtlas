// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CweMapping}.
 *
 * @since 3.0.0
 */
@Tag("unit")
@Tag("cwe")
class CweMappingTest {

    // -------------------------------------------------------------------------
    // forTag — individual known tags
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("auth maps to CWE-287")
    @Tag("positive")
    void forTag_auth_returnsCwe287() {
        assertEquals(List.of("CWE-287"), CweMapping.forTag("auth"));
    }

    @Test
    @DisplayName("access-control maps to CWE-285")
    @Tag("positive")
    void forTag_accessControl_returnsCwe285() {
        assertEquals(List.of("CWE-285"), CweMapping.forTag("access-control"));
    }

    @Test
    @DisplayName("crypto maps to CWE-327")
    @Tag("positive")
    void forTag_crypto_returnsCwe327() {
        assertEquals(List.of("CWE-327"), CweMapping.forTag("crypto"));
    }

    @Test
    @DisplayName("input-validation maps to CWE-20")
    @Tag("positive")
    void forTag_inputValidation_returnsCwe20() {
        assertEquals(List.of("CWE-20"), CweMapping.forTag("input-validation"));
    }

    @Test
    @DisplayName("injection maps to CWE-74")
    @Tag("positive")
    void forTag_injection_returnsCwe74() {
        assertEquals(List.of("CWE-74"), CweMapping.forTag("injection"));
    }

    @Test
    @DisplayName("data-protection maps to CWE-311")
    @Tag("positive")
    void forTag_dataProtection_returnsCwe311() {
        assertEquals(List.of("CWE-311"), CweMapping.forTag("data-protection"));
    }

    @Test
    @DisplayName("logging maps to CWE-778")
    @Tag("positive")
    void forTag_logging_returnsCwe778() {
        assertEquals(List.of("CWE-778"), CweMapping.forTag("logging"));
    }

    @Test
    @DisplayName("error-handling maps to CWE-209")
    @Tag("positive")
    void forTag_errorHandling_returnsCwe209() {
        assertEquals(List.of("CWE-209"), CweMapping.forTag("error-handling"));
    }

    @Test
    @DisplayName("owasp has no CWE mapping and returns empty list")
    @Tag("positive")
    void forTag_owasp_returnsEmpty() {
        assertTrue(CweMapping.forTag("owasp").isEmpty());
    }

    @Test
    @DisplayName("unrecognised tag returns empty list")
    @Tag("negative")
    void forTag_unknown_returnsEmpty() {
        assertTrue(CweMapping.forTag("unknown-tag").isEmpty());
    }

    // -------------------------------------------------------------------------
    // forTags — null and empty guards
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("forTags with null returns empty list")
    @Tag("negative")
    void forTags_null_returnsEmpty() {
        assertTrue(CweMapping.forTags(null).isEmpty());
    }

    @Test
    @DisplayName("forTags with empty list returns empty list")
    @Tag("negative")
    void forTags_emptyList_returnsEmpty() {
        assertTrue(CweMapping.forTags(List.of()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // forTags — multi-tag union
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("forTags with multiple distinct tags returns union of CWE ids")
    @Tag("positive")
    void forTags_multipleDistinctTags_returnsUnion() {
        List<String> result = CweMapping.forTags(List.of("auth", "crypto"));
        assertEquals(List.of("CWE-287", "CWE-327"), result);
    }

    @Test
    @DisplayName("forTags with duplicate tags deduplicates CWE ids")
    @Tag("positive")
    void forTags_duplicateTags_deduplicates() {
        List<String> result = CweMapping.forTags(List.of("auth", "auth"));
        assertEquals(List.of("CWE-287"), result);
    }

    @Test
    @DisplayName("forTags with mix of known and unknown tags skips unknown")
    @Tag("positive")
    void forTags_mixedKnownUnknown_skipsUnknown() {
        List<String> result = CweMapping.forTags(List.of("injection", "owasp", "bogus"));
        assertEquals(List.of("CWE-74"), result);
    }

    @Test
    @DisplayName("forTags preserves encounter order for union result")
    @Tag("positive")
    void forTags_preservesEncounterOrder() {
        List<String> result = CweMapping.forTags(
                List.of("error-handling", "input-validation", "logging"));
        assertEquals(List.of("CWE-209", "CWE-20", "CWE-778"), result);
    }

    @Test
    @DisplayName("forTags result is unmodifiable")
    @Tag("positive")
    void forTags_resultIsUnmodifiable() {
        List<String> result = CweMapping.forTags(List.of("auth"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> result.add("extra"));
    }
}
