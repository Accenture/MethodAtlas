package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TagAiDrift}.
 */
@Tag("unit")
@Tag("drift")
class TagAiDriftTest {

    // -------------------------------------------------------------------------
    // compute — null suggestion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compute returns null when suggestion is null")
    @Tag("edge-case")
    void compute_nullSuggestion_returnsNull() {
        assertNull(TagAiDrift.compute(List.of("security"), null));
    }

    // -------------------------------------------------------------------------
    // compute — NONE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compute returns NONE when both tag and AI agree: security-relevant")
    @Tag("positive")
    void compute_bothSecurityRelevant_returnsNone() {
        AiMethodSuggestion suggestion = securitySuggestion(true);
        assertEquals(TagAiDrift.NONE, TagAiDrift.compute(List.of("security"), suggestion));
    }

    @Test
    @DisplayName("compute returns NONE when both tag and AI agree: not security-relevant")
    @Tag("positive")
    void compute_neitherSecurityRelevant_returnsNone() {
        AiMethodSuggestion suggestion = securitySuggestion(false);
        assertEquals(TagAiDrift.NONE, TagAiDrift.compute(List.of("smoke"), suggestion));
    }

    @Test
    @DisplayName("compute returns NONE when tag list is empty and AI says not security-relevant")
    @Tag("edge-case")
    void compute_noTagAndAiNonSecurity_returnsNone() {
        AiMethodSuggestion suggestion = securitySuggestion(false);
        assertEquals(TagAiDrift.NONE, TagAiDrift.compute(List.of(), suggestion));
    }

    // -------------------------------------------------------------------------
    // compute — TAG_ONLY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compute returns TAG_ONLY when @Tag(security) present but AI disagrees")
    @Tag("positive")
    void compute_tagPresentAiDisagrees_returnsTagOnly() {
        AiMethodSuggestion suggestion = securitySuggestion(false);
        assertEquals(TagAiDrift.TAG_ONLY, TagAiDrift.compute(List.of("security"), suggestion));
    }

    @Test
    @DisplayName("compute tag matching is case-insensitive")
    @Tag("edge-case")
    void compute_tagMatchingIsCaseInsensitive() {
        AiMethodSuggestion suggestion = securitySuggestion(false);
        assertEquals(TagAiDrift.TAG_ONLY, TagAiDrift.compute(List.of("SECURITY"), suggestion));
        assertEquals(TagAiDrift.TAG_ONLY, TagAiDrift.compute(List.of("Security"), suggestion));
    }

    @Test
    @DisplayName("compute returns TAG_ONLY when security tag is among multiple tags and AI disagrees")
    @Tag("positive")
    void compute_securityTagAmongMultiple_tagOnlyWhenAiDisagrees() {
        AiMethodSuggestion suggestion = securitySuggestion(false);
        assertEquals(TagAiDrift.TAG_ONLY,
                TagAiDrift.compute(List.of("smoke", "security", "slow"), suggestion));
    }

    // -------------------------------------------------------------------------
    // compute — AI_ONLY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compute returns AI_ONLY when AI says security-relevant but no @Tag(security)")
    @Tag("positive")
    void compute_aiSecurityNoTag_returnsAiOnly() {
        AiMethodSuggestion suggestion = securitySuggestion(true);
        assertEquals(TagAiDrift.AI_ONLY, TagAiDrift.compute(List.of(), suggestion));
    }

    @Test
    @DisplayName("compute returns AI_ONLY when non-security tags are present but AI says security-relevant")
    @Tag("positive")
    void compute_nonSecurityTagAndAiSecure_returnsAiOnly() {
        AiMethodSuggestion suggestion = securitySuggestion(true);
        assertEquals(TagAiDrift.AI_ONLY, TagAiDrift.compute(List.of("smoke", "integration"), suggestion));
    }

    // -------------------------------------------------------------------------
    // toValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("toValue returns lowercase hyphenated representation")
    @Tag("positive")
    void toValue_returnsExpectedStrings() {
        assertEquals("none", TagAiDrift.NONE.toValue());
        assertEquals("tag-only", TagAiDrift.TAG_ONLY.toValue());
        assertEquals("ai-only", TagAiDrift.AI_ONLY.toValue());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static AiMethodSuggestion securitySuggestion(boolean securityRelevant) {
        return new AiMethodSuggestion("testMethod", securityRelevant, null, List.of(), null, 0.0, 0.0);
    }
}
