// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MethodEntry}.
 *
 * <p>
 * The class is a small mutable view-model for the GUI's results tree;
 * {@link MethodEntry#tagStatus()} has several branches that the tree
 * relies on for the colour-coded status indicators. Each branch is
 * exercised independently here.
 * </p>
 *
 * @since 1.0.0
 */
class MethodEntryTest {

    private static DiscoveredMethod method(String name, List<String> tags) {
        SourceContent emptySource = Optional::empty;
        return new DiscoveredMethod("com.example.Test", name,
                /* beginLine */ 1, /* endLine */ 5, /* loc */ 5,
                tags == null ? List.of() : tags,
                /* displayName */ null,
                /* filePath */ null,
                /* fileStem */ null,
                /* sourceContent */ emptySource);
    }

    private static AiMethodSuggestion suggestion(boolean securityRelevant, List<String> tags) {
        return new AiMethodSuggestion("m", securityRelevant,
                /* displayName */ null,
                tags == null ? List.of() : tags,
                /* reason */ null, /* confidence */ 0.0, /* interactionScore */ 0.0);
    }

    @Test
    void constructor_rejectsNullDiscovered() {
        assertThrows(NullPointerException.class,
                () -> new MethodEntry(null, null));
    }

    @Test
    void tagStatus_noSuggestion_returnsNoAi() {
        MethodEntry entry = new MethodEntry(method("m", List.of()), null);
        assertEquals(MethodEntry.TagStatus.NO_AI, entry.tagStatus());
    }

    @Test
    void tagStatus_suggestionNotSecurity_returnsNotSecurity() {
        MethodEntry entry = new MethodEntry(method("m", List.of()),
                suggestion(false, List.of()));
        assertEquals(MethodEntry.TagStatus.NOT_SECURITY, entry.tagStatus());
    }

    @Test
    void tagStatus_securityButEmptyAiTags_returnsOk() {
        MethodEntry entry = new MethodEntry(method("m", List.of()),
                suggestion(true, List.of()));
        assertEquals(MethodEntry.TagStatus.OK, entry.tagStatus());
    }

    @Test
    void tagStatus_aiTagsAllPresent_returnsOk() {
        MethodEntry entry = new MethodEntry(method("m", List.of("security", "auth")),
                suggestion(true, List.of("security", "auth")));
        assertEquals(MethodEntry.TagStatus.OK, entry.tagStatus());
    }

    @Test
    void tagStatus_aiTagMissing_returnsNeedsReview() {
        MethodEntry entry = new MethodEntry(method("m", List.of("security")),
                suggestion(true, List.of("security", "auth")));
        assertEquals(MethodEntry.TagStatus.NEEDS_REVIEW, entry.tagStatus());
    }

    @Test
    void tagStatus_stagedPatch_returnsPendingSaveRegardlessOfSuggestion() {
        MethodEntry entry = new MethodEntry(method("m", List.of()),
                suggestion(true, List.of("security")));
        entry.setStagedPatch(List.of("security", "auth"), "Display name");
        assertEquals(MethodEntry.TagStatus.PENDING_SAVE, entry.tagStatus());
    }

    @Test
    void setAppliedTags_replacesReferenceForStatusComparison() {
        MethodEntry entry = new MethodEntry(method("m", List.of()),
                suggestion(true, List.of("security")));
        // Without applied tags, the source tags (empty) miss the AI tag.
        assertEquals(MethodEntry.TagStatus.NEEDS_REVIEW, entry.tagStatus());

        entry.setAppliedTags(List.of("security"));
        assertEquals(MethodEntry.TagStatus.OK, entry.tagStatus());
    }

    @Test
    void appliedTags_nullArgumentResetsToNull() {
        MethodEntry entry = new MethodEntry(method("m", List.of()), null);
        entry.setAppliedTags(List.of("security"));
        assertNotNull(entry.appliedTags());
        entry.setAppliedTags(null);
        assertNull(entry.appliedTags());
    }

    @Test
    void stagedPatch_clearedAfterClear_hasPendingChangesFalse() {
        MethodEntry entry = new MethodEntry(method("m", List.of()), null);
        entry.setStagedPatch(List.of("security"), "name");
        assertTrue(entry.hasPendingChanges());

        entry.clearStagedPatch();
        assertFalse(entry.hasPendingChanges());
        assertNull(entry.getPendingTags());
        assertNull(entry.getPendingDisplayName());
    }

    @Test
    void suggestedDisplayName_nullSuggestion_returnsNull() {
        MethodEntry entry = new MethodEntry(method("m", List.of()), null);
        assertNull(entry.suggestedDisplayName());
    }

    @Test
    void toString_includesFqcnAndMethod() {
        MethodEntry entry = new MethodEntry(method("login", List.of()), null);
        assertTrue(entry.toString().contains("com.example.Test"));
        assertTrue(entry.toString().contains("login"));
    }
}
