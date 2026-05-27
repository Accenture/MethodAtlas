// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.egothor.methodatlas.TestMethodSink;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure helpers on {@link ScanOrchestrator}.
 *
 * <p>
 * The {@code scan}, {@code runDiscovery}, {@code collectMethodsByFile}, and
 * {@code gatherAiSuggestionsForFile} methods are covered end-to-end by the
 * existing scan-mode integration tests against the real {@code ServiceLoader}
 * classpath; duplicating those at the unit level would either need elaborate
 * mocking or duplicate the integration coverage. This test class focuses on
 * the two non-trivial pure helpers that benefit most from focused unit
 * tests: {@link ScanOrchestrator#toTargetMethod} and
 * {@link ScanOrchestrator#filterSink}.
 * </p>
 *
 * @since 1.0.0
 */
class ScanOrchestratorTest {

    // ── toTargetMethod ──────────────────────────────────────────────────────

    @Test
    void toTargetMethod_validBeginAndEndLines_preservesBoth() {
        DiscoveredMethod m = discovered("testLogin", 10, 25);

        PromptBuilder.TargetMethod target = ScanOrchestrator.toTargetMethod(m);

        assertEquals("testLogin", target.methodName());
        assertEquals(Integer.valueOf(10), target.beginLine());
        assertEquals(Integer.valueOf(25), target.endLine());
    }

    @Test
    void toTargetMethod_nonPositiveLineNumbers_collapseToNull() {
        // beginLine == 0 means "no line info available"; the conversion must
        // not propagate the sentinel 0 into the prompt-target descriptor.
        DiscoveredMethod m = discovered("noLineInfo", 0, 0);

        PromptBuilder.TargetMethod target = ScanOrchestrator.toTargetMethod(m);

        assertEquals("noLineInfo", target.methodName());
        assertEquals(null, target.beginLine());
        assertEquals(null, target.endLine());
    }

    // ── filterSink ──────────────────────────────────────────────────────────

    @Test
    void filterSink_neitherFilterActive_returnsDelegateUnchanged() {
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());
        TestMethodSink delegate = (a, b, c, d, e, f, g, h) -> { };

        TestMethodSink result = orchestrator.filterSink(delegate, false, 0.0, false);

        assertSame(delegate, result,
                "When both filters are off, the original sink is returned "
                        + "unchanged to avoid runtime wrapper overhead");
    }

    @Test
    void filterSink_securityOnly_dropsNonSecurityRecords() {
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());
        List<String> received = new ArrayList<>();
        TestMethodSink capturing = (fqcn, m, b, l, h, t, dn, s) -> received.add(m);

        TestMethodSink filtered = orchestrator.filterSink(capturing, true, 0.0, false);

        filtered.record("c", "secMethod", 1, 1, null, List.of(), null,
                suggestion(true, 0.0));
        filtered.record("c", "regularMethod", 2, 1, null, List.of(), null,
                suggestion(false, 0.0));
        filtered.record("c", "noSuggestion", 3, 1, null, List.of(), null, null);

        assertEquals(List.of("secMethod"), received,
                "Security-only filter must keep records with "
                        + "securityRelevant=true and drop everything else");
    }

    @Test
    void filterSink_confidenceFilter_dropsLowConfidenceRecords() {
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());
        List<String> received = new ArrayList<>();
        TestMethodSink capturing = (fqcn, m, b, l, h, t, dn, s) -> received.add(m);

        TestMethodSink filtered = orchestrator.filterSink(capturing, false, 0.7, true);

        filtered.record("c", "highConfidence", 1, 1, null, List.of(), null,
                suggestion(false, 0.9));
        filtered.record("c", "lowConfidence", 2, 1, null, List.of(), null,
                suggestion(false, 0.5));
        filtered.record("c", "noSuggestion", 3, 1, null, List.of(), null, null);

        assertEquals(List.of("highConfidence"), received,
                "Confidence filter must keep records with "
                        + "confidence >= threshold and drop the rest");
    }

    @Test
    void filterSink_bothFilters_appliedTogether() {
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());
        List<String> received = new ArrayList<>();
        TestMethodSink capturing = (fqcn, m, b, l, h, t, dn, s) -> received.add(m);

        TestMethodSink filtered = orchestrator.filterSink(capturing, true, 0.7, true);

        filtered.record("c", "secHighConfidence", 1, 1, null, List.of(), null,
                suggestion(true, 0.9));
        filtered.record("c", "secLowConfidence", 2, 1, null, List.of(), null,
                suggestion(true, 0.4));
        filtered.record("c", "nonSecHighConfidence", 3, 1, null, List.of(), null,
                suggestion(false, 0.9));

        assertTrue(received.contains("secHighConfidence"));
        assertEquals(1, received.size(),
                "Only records passing both security AND confidence filters survive");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static DiscoveredMethod discovered(String name, int beginLine, int endLine) {
        SourceContent emptySource = Optional::empty;
        return new DiscoveredMethod(
                "com.example.Test", name,
                beginLine, endLine, /* loc */ 0,
                /* tags */ List.of(),
                /* displayName */ null,
                /* filePath */ null,
                /* fileStem */ null,
                /* sourceContent */ emptySource);
    }

    private static AiMethodSuggestion suggestion(boolean securityRelevant, double confidence) {
        return new AiMethodSuggestion(
                /* methodName */ "m",
                securityRelevant,
                /* displayName */ null,
                /* tags */ List.of(),
                /* reason */ null,
                confidence,
                /* interactionScore */ 0.0);
    }
}
