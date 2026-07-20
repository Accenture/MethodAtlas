package org.egothor.methodatlas.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TagStaging}, the Swing-free tag-staging logic extracted
 * from the tag editor panel.
 */
class TagStagingTest {

    private static MethodEntry entry(List<String> sourceTags, String sourceDisplayName,
            AiMethodSuggestion suggestion) {
        DiscoveredMethod dm = new DiscoveredMethod(
                "com.acme.FooTest", "test_one", 1, 5, 5,
                sourceTags, sourceDisplayName, null, "com.acme.FooTest",
                () -> Optional.empty());
        return new MethodEntry(dm, suggestion);
    }

    private static AiMethodSuggestion suggestion(List<String> tags, String displayName) {
        return new AiMethodSuggestion("test_one", true, displayName, tags,
                "reason", 0.9, 0.0);
    }

    // ── merge ───────────────────────────────────────────────────────────────

    @Test
    void merge_preservesOrderAndDeduplicates() {
        assertEquals(List.of("a", "b", "c"),
                TagStaging.merge(List.of("a", "b"), List.of("b", "c")));
    }

    @Test
    void merge_emptyExtras_returnsBase() {
        assertEquals(List.of("a", "b"), TagStaging.merge(List.of("a", "b"), List.of()));
    }

    // ── parseOverride ─────────────────────────────────────────────────────────

    @Test
    void parseOverride_trimsAndDropsEmptyTokens() {
        assertEquals(List.of("a", "b", "c"), TagStaging.parseOverride(" a , b ,, c "));
    }

    @Test
    void parseOverride_nullOrBlank_returnsEmpty() {
        assertTrue(TagStaging.parseOverride(null).isEmpty());
        assertTrue(TagStaging.parseOverride("   ").isEmpty());
    }

    // ── acceptAllAiTags ───────────────────────────────────────────────────────

    @Test
    void acceptAllAiTags_unionsSourceAndAiTags() {
        MethodEntry e = entry(List.of("existing"), null,
                suggestion(List.of("security", "existing"), null));
        assertEquals(List.of("existing", "security"), TagStaging.acceptAllAiTags(e));
    }

    @Test
    void acceptAllAiTags_nullSuggestionTags_returnsSourceTags() {
        MethodEntry e = entry(List.of("existing"), null, suggestion(null, null));
        assertEquals(List.of("existing"), TagStaging.acceptAllAiTags(e));
    }

    // ── selectedTags ──────────────────────────────────────────────────────────

    @Test
    void selectedTags_mergesSourceSelectedAndOverride_inOrder() {
        MethodEntry e = entry(List.of("src"), null, suggestion(List.of("ai"), null));
        List<String> result = TagStaging.selectedTags(e, List.of("ai"), "custom, extra");
        assertEquals(List.of("src", "ai", "custom", "extra"), result);
    }

    @Test
    void selectedTags_blankOverride_ignored() {
        MethodEntry e = entry(List.of("src"), null, suggestion(List.of("ai"), null));
        assertEquals(List.of("src", "ai"), TagStaging.selectedTags(e, List.of("ai"), "  "));
    }

    // ── resolveDisplayName ────────────────────────────────────────────────────

    @Test
    void resolveDisplayName_returnsSuggestionWhenSourceHasNone() {
        MethodEntry e = entry(List.of(), null, suggestion(List.of("t"), "SECURITY: auth"));
        assertEquals("SECURITY: auth", TagStaging.resolveDisplayName(e));
    }

    @Test
    void resolveDisplayName_nullWhenSourceAlreadyHasDisplayName() {
        MethodEntry e = entry(List.of(), "existing name", suggestion(List.of("t"), "SECURITY: auth"));
        assertNull(TagStaging.resolveDisplayName(e));
    }

    @Test
    void resolveDisplayName_nullWhenSuggestionHasNoDisplayName() {
        MethodEntry e = entry(List.of(), null, suggestion(List.of("t"), null));
        assertNull(TagStaging.resolveDisplayName(e));
    }

    @Test
    void resolveDisplayName_nullWhenNoSuggestion() {
        MethodEntry e = entry(List.of(), null, null);
        assertNull(TagStaging.resolveDisplayName(e));
    }
}
