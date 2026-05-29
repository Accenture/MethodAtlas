// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

class ControlCoverageCollectorTest {

    private static final String FQCN = "com.acme.AccessControlTest";
    private static final String METHOD = "denyAnonymous";

    @Test
    void sourceTag_yieldsSourceProvenanceAndFullConfidence() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of("tag-a"), null, null);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        CoverageControlEntry control = report.coverage().get("ASVS-1.1.1");
        assertNotNull(control, "control must be present");
        CoverageTestEntry entry = control.tests().get(0);
        assertEquals("source", entry.tagSource());
        assertEquals(1.0, entry.confidence());
    }

    @Test
    void aiOnly_aboveThreshold_yieldsAiProvenanceAndAiConfidence() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.8);
        AiMethodSuggestion suggestion = new AiMethodSuggestion(METHOD, true, "x",
                List.of("tag-a"), null, 0.9, 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of(), null, suggestion);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        CoverageTestEntry entry = report.coverage().get("ASVS-1.1.1").tests().get(0);
        assertEquals("ai", entry.tagSource());
        assertEquals(0.9, entry.confidence());
    }

    @Test
    void aiOnly_belowThreshold_isExcluded() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.8);
        AiMethodSuggestion lowConfidence = new AiMethodSuggestion(METHOD, true, "x",
                List.of("tag-a"), null, 0.5, 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of(), null, lowConfidence);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        assertTrue(report.coverage().isEmpty(),
                "Sub-threshold AI evidence must not contribute coverage");
    }

    @Test
    void sourceAndAi_yieldsBothProvenance() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        AiMethodSuggestion suggestion = new AiMethodSuggestion(METHOD, true, "x",
                List.of("tag-a"), null, 0.6, 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of("tag-a"), null, suggestion);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        CoverageTestEntry entry = report.coverage().get("ASVS-1.1.1").tests().get(0);
        assertEquals("both", entry.tagSource());
        assertEquals(1.0, entry.confidence(),
                "Human annotation pins confidence to 1.0 even when AI agrees");
    }

    @Test
    void umbrellaTag_notInMapping_isIgnored() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of("security"), null, null);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        assertTrue(report.coverage().isEmpty(),
                "Tags absent from the mapping must contribute no coverage");
    }

    @Test
    void onlyUnmappedTags_areIgnoredEntirely() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of("not-in-mapping"), null, null);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        assertTrue(report.coverage().isEmpty());
        assertFalse(report.gaps().isEmpty(),
                "Mapped controls without a covering test must still appear as gaps");
    }

    @Test
    void gaps_containsEveryUncoveredControl_sortedLexicographically() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        // Cover only the first control.
        collector.record(FQCN, METHOD, 1, 1, null, List.of("tag-a"), null, null);

        ControlCoverageReport report = collector.buildReport("1.0.0");
        List<String> gaps = report.gaps();
        // tag-b → 2.2.2 (uncovered)
        assertEquals(List.of("ASVS-2.2.2"), gaps);
    }

    @Test
    void statistics_coveragePercent_isZeroOnEmptyRun() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        ControlCoverageReport report = collector.buildReport("1.0.0");
        assertEquals(0.0, report.statistics().coveragePercent());
        assertEquals(2, report.statistics().totalMappedControls());
    }

    @Test
    void statistics_coveragePercent_isOneHundredWhenAllCovered() {
        ControlCoverageCollector collector = new ControlCoverageCollector(twoTagMapping(), 0.0);
        collector.record(FQCN, METHOD, 1, 1, null, List.of("tag-a", "tag-b"), null, null);
        ControlCoverageReport report = collector.buildReport("1.0.0");
        assertEquals(100.0, report.statistics().coveragePercent());
    }

    private static ControlMapping twoTagMapping() {
        // Two tags → two distinct controls; 2.2.2 has no chapter to verify
        // nullable propagation.
        List<ControlEntry> aEntries = Collections.singletonList(
                new ControlEntry("1.1.1", "V1", "Architecture"));
        List<ControlEntry> bEntries = Collections.singletonList(
                new ControlEntry("2.2.2", null, null));
        Map<String, List<ControlEntry>> map = new LinkedHashMap<>();
        map.put("tag-a", Collections.unmodifiableList(new ArrayList<>(aEntries)));
        map.put("tag-b", Collections.unmodifiableList(new ArrayList<>(bEntries)));
        return new ControlMapping("ASVS", "4.0", "/tmp/test-mapping.json",
                Collections.unmodifiableMap(map));
    }
}
