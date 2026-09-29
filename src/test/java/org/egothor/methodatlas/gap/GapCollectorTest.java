// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GapCollector}.
 *
 * @since 3.0.0
 */
@Tag("unit")
@Tag("gap")
class GapCollectorTest {

    private static final String TOOL_VER = "test-3.0.0";

    // -------------------------------------------------------------------------
    // Empty collector — all domains are gaps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty collector reports all 9 taxonomy domains as gaps and 0% coverage")
    @Tag("positive")
    void empty_allDomainsAreGaps() {
        GapCollector collector = new GapCollector(0.0);
        GapReport report = collector.buildReport(TOOL_VER);

        assertEquals(9, report.domains().size());
        assertEquals(9, report.gaps().size());
        assertEquals(9, report.summary().totalDomains());
        assertEquals(0, report.summary().coveredDomains());
        assertEquals(9, report.summary().uncoveredDomains());
        assertEquals(0.0, report.summary().coveragePercent(), 1e-9);
    }

    @Test
    @DisplayName("empty collector report contains schema version and tool version")
    @Tag("positive")
    void empty_reportContainsVersionFields() {
        GapCollector collector = new GapCollector(0.0);
        GapReport report = collector.buildReport(TOOL_VER);

        assertNotNull(report.schemaVersion());
        assertFalse(report.schemaVersion().isBlank());
        assertEquals(TOOL_VER, report.methodAtlasVersion());
        assertNotNull(report.generatedUtc());
    }

    // -------------------------------------------------------------------------
    // Null suggestion — silently skipped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null suggestion is silently skipped")
    @Tag("negative")
    void nullSuggestion_isSkipped() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.Foo", "test_foo", 1, 5, "h", List.of(), null, null);

        GapReport report = collector.buildReport(TOOL_VER);
        assertEquals(9, report.gaps().size());
    }

    // -------------------------------------------------------------------------
    // Non-security-relevant suggestion — silently skipped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("non-security-relevant suggestion is silently skipped")
    @Tag("negative")
    void nonSecurityRelevant_isSkipped() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.Foo", "test_foo", 1, 5, "h", List.of(), null,
                suggestion(false, List.of("auth"), 1.0));

        GapReport report = collector.buildReport(TOOL_VER);
        assertEquals(9, report.gaps().size());
    }

    // -------------------------------------------------------------------------
    // Confidence threshold filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("suggestion below minConfidence threshold is excluded")
    @Tag("negative")
    void belowMinConfidence_excluded() {
        GapCollector collector = new GapCollector(0.8);
        collector.record("com.Foo", "test_foo", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("auth"), 0.7));

        GapReport report = collector.buildReport(TOOL_VER);
        assertEquals(9, report.gaps().size());
    }

    @Test
    @DisplayName("suggestion at exactly minConfidence boundary is included")
    @Tag("positive")
    void atMinConfidenceBoundary_included() {
        GapCollector collector = new GapCollector(0.8);
        collector.record("com.Foo", "test_foo", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("auth"), 0.8));

        GapReport report = collector.buildReport(TOOL_VER);
        assertFalse(report.gaps().contains("auth"));
    }

    // -------------------------------------------------------------------------
    // Single domain covered
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("one method covering auth marks auth domain as covered and 8 remaining as gaps")
    @Tag("positive")
    void singleDomain_authCovered() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.AuthTest", "test_login", 1, 10, "h", List.of(), null,
                suggestion(true, List.of("auth"), 1.0));

        GapReport report = collector.buildReport(TOOL_VER);

        assertFalse(report.gaps().contains("auth"), "auth should be covered");
        assertEquals(8, report.gaps().size());
        assertEquals(1, report.summary().coveredDomains());
        assertEquals(8, report.summary().uncoveredDomains());
        assertEquals(1, report.domains().get("auth").count());
    }

    // -------------------------------------------------------------------------
    // Unrecognised tag — not counted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tag not in the built-in taxonomy is silently ignored")
    @Tag("negative")
    void unrecognisedTag_ignored() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.Foo", "test_foo", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("custom-tag"), 1.0));

        GapReport report = collector.buildReport(TOOL_VER);
        assertEquals(9, report.gaps().size());
    }

    // -------------------------------------------------------------------------
    // Multiple methods and domains
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("two methods covering different domains leave 7 gaps")
    @Tag("positive")
    void twoDomainsCovered_sevenGaps() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.AuthTest", "test_login", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("auth"), 1.0));
        collector.record("com.CryptoTest", "test_enc", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("crypto"), 0.9));

        GapReport report = collector.buildReport(TOOL_VER);

        assertEquals(7, report.gaps().size());
        assertEquals(2, report.summary().coveredDomains());
    }

    @Test
    @DisplayName("same method covering two taxonomy tags counts toward both domains")
    @Tag("positive")
    void methodWithTwoTags_countsBothDomains() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.Foo", "test_multi", 1, 5, "h", List.of(), null,
                suggestion(true, List.of("auth", "injection"), 1.0));

        GapReport report = collector.buildReport(TOOL_VER);

        assertFalse(report.gaps().contains("auth"));
        assertFalse(report.gaps().contains("injection"));
        assertEquals(7, report.gaps().size());
    }

    // -------------------------------------------------------------------------
    // All 9 domains covered → 100%
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all 9 taxonomy domains covered gives 100% coverage and no gaps")
    @Tag("positive")
    void allDomainsCovered_hundredPercent() {
        GapCollector collector = new GapCollector(0.0);
        List<String> allTags = List.of(
                "auth", "access-control", "crypto", "input-validation",
                "injection", "data-protection", "logging", "error-handling", "owasp");
        for (String tag : allTags) {
            collector.record("com.FullTest", "test_" + tag, 1, 5, "h", List.of(), null,
                    suggestion(true, List.of(tag), 1.0));
        }

        GapReport report = collector.buildReport(TOOL_VER);

        assertTrue(report.gaps().isEmpty());
        assertEquals(100.0, report.summary().coveragePercent(), 1e-9);
        assertEquals(9, report.summary().coveredDomains());
        assertEquals(0, report.summary().uncoveredDomains());
    }

    // -------------------------------------------------------------------------
    // GapReportMethod content
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("recorded method appears in the domain's method list with correct fqcn and name")
    @Tag("positive")
    void methodEntry_hasCorrectFqcnAndName() {
        GapCollector collector = new GapCollector(0.0);
        collector.record("com.AuthTest", "test_login", 5, 10, "h", List.of(), "SECURITY: login", null);
        // Give it a security-relevant suggestion so it is recorded
        GapCollector collector2 = new GapCollector(0.0);
        collector2.record("com.AuthTest", "test_login", 5, 10, "h", List.of(), "SECURITY: login",
                suggestion(true, List.of("auth"), 1.0));

        GapReport report = collector2.buildReport(TOOL_VER);

        GapReportDomain authDomain = report.domains().get("auth");
        assertNotNull(authDomain);
        assertEquals(1, authDomain.count());
        GapReportMethod entry = authDomain.methods().get(0);
        assertEquals("com.AuthTest", entry.fqcn());
        assertEquals("test_login", entry.method());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static AiMethodSuggestion suggestion(boolean securityRelevant, List<String> tags,
            double confidence) {
        return new AiMethodSuggestion("method", securityRelevant, null, tags, null, confidence, 0.0);
    }
}
