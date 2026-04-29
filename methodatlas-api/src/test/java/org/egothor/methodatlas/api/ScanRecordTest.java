package org.egothor.methodatlas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ScanRecord} record.
 *
 * <p>
 * Verifies field accessors, the three-way nullable contract for optional
 * columns ({@code null} = column absent, {@code ""} or empty list = column
 * present but empty), and record equality.
 * </p>
 */
@Tag("unit")
@Tag("api")
class ScanRecordTest {

    // -------------------------------------------------------------------------
    // Full construction — all fields present
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all fields are returned verbatim by their accessors")
    @Tag("positive")
    void allFields_returnedVerbatim() {
        List<String> tags = List.of("security");
        List<String> aiTags = List.of("crypto", "auth");
        ScanRecord r = new ScanRecord(
                "com.acme.CryptoTest",
                "testEncrypt",
                12,
                tags,
                "SECURITY: verifies AES-GCM",
                "abc123",
                true,
                "AI: verifies AES-GCM encryption",
                aiTags,
                "covers AES-GCM",
                0.95,
                0.8,
                "none");

        assertEquals("com.acme.CryptoTest", r.fqcn());
        assertEquals("testEncrypt", r.method());
        assertEquals(12, r.loc());
        assertEquals(tags, r.tags());
        assertEquals("SECURITY: verifies AES-GCM", r.displayName());
        assertEquals("abc123", r.contentHash());
        assertEquals(Boolean.TRUE, r.aiSecurityRelevant());
        assertEquals("AI: verifies AES-GCM encryption", r.aiDisplayName());
        assertEquals(aiTags, r.aiTags());
        assertEquals("covers AES-GCM", r.aiReason());
        assertEquals(0.95, r.aiConfidence());
        assertEquals(0.8, r.aiInteractionScore());
        assertEquals("none", r.tagAiDrift());
    }

    // -------------------------------------------------------------------------
    // Mandatory fields are always present
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fqcn and method are mandatory and always non-null")
    @Tag("positive")
    void mandatoryFields_fqcnAndMethod_neverNull() {
        ScanRecord r = minimal("com.acme.FooTest", "testFoo");

        assertNotNull(r.fqcn());
        assertNotNull(r.method());
    }

    @Test
    @DisplayName("loc is always a non-negative integer")
    @Tag("positive")
    void loc_nonNegative() {
        ScanRecord zero = minimal_withLoc(0);
        ScanRecord positive = minimal_withLoc(5);

        assertEquals(0, zero.loc());
        assertEquals(5, positive.loc());
    }

    @Test
    @DisplayName("tags list is never null (empty list represents no tags)")
    @Tag("positive")
    void tags_neverNull_emptyListAllowed() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);

        assertNotNull(r.tags());
        assertTrue(r.tags().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Optional column: displayName (null = column absent)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("displayName null means column was absent from the source CSV")
    @Tag("positive")
    void displayName_null_columnAbsent() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);

        assertNull(r.displayName(), "null displayName means column was absent from CSV");
    }

    @Test
    @DisplayName("displayName empty string means column was present but no annotation")
    @Tag("positive")
    void displayName_empty_columnPresentNoAnnotation() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), "", null, null, null, null, null, null, null, null);

        assertNotNull(r.displayName());
        assertTrue(r.displayName().isEmpty());
    }

    @Test
    @DisplayName("displayName non-empty means the annotation text")
    @Tag("positive")
    void displayName_nonEmpty_annotationText() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), "Auth test", null, null, null, null, null, null, null, null);

        assertEquals("Auth test", r.displayName());
    }

    // -------------------------------------------------------------------------
    // Optional column: contentHash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("contentHash null means column was absent (scan run without -content-hash)")
    @Tag("positive")
    void contentHash_null_columnAbsent() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);

        assertNull(r.contentHash());
    }

    @Test
    @DisplayName("contentHash non-null is returned as-is")
    @Tag("positive")
    void contentHash_present_returnedAsIs() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, "deadbeef", null, null, null, null, null, null, null);

        assertEquals("deadbeef", r.contentHash());
    }

    // -------------------------------------------------------------------------
    // Optional columns: AI fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all AI fields null when scan was run without -ai")
    @Tag("positive")
    void aiFields_allNull_whenNoAi() {
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);

        assertNull(r.aiSecurityRelevant());
        assertNull(r.aiDisplayName());
        assertNull(r.aiTags());
        assertNull(r.aiReason());
        assertNull(r.aiConfidence());
        assertNull(r.aiInteractionScore());
        assertNull(r.tagAiDrift());
    }

    @Test
    @DisplayName("aiSecurityRelevant false is distinct from null")
    @Tag("positive")
    void aiSecurityRelevant_false_distinctFromNull() {
        ScanRecord nullValue  = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);
        ScanRecord falseValue = new ScanRecord("FooTest", "m", 1, List.of(), null, null, false, null, null, null, null, null, null);

        assertNull(nullValue.aiSecurityRelevant());
        assertNotNull(falseValue.aiSecurityRelevant());
        assertFalse(falseValue.aiSecurityRelevant());
    }

    @Test
    @DisplayName("aiTags empty list is distinct from null")
    @Tag("positive")
    void aiTags_emptyList_distinctFromNull() {
        ScanRecord withNull  = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, null, null, null);
        ScanRecord withEmpty = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, List.of(), null, null, null, null);

        assertNull(withNull.aiTags());
        assertNotNull(withEmpty.aiTags());
        assertTrue(withEmpty.aiTags().isEmpty());
    }

    @Test
    @DisplayName("aiTags list with values is returned verbatim")
    @Tag("positive")
    void aiTags_withValues_returnedVerbatim() {
        List<String> aiTags = List.of("crypto", "auth", "injection");
        ScanRecord r = new ScanRecord("FooTest", "m", 1, List.of(), null, null, true, null, aiTags, null, null, null, null);

        assertEquals(aiTags, r.aiTags());
    }

    @Test
    @DisplayName("aiConfidence boundary values 0.0 and 1.0 are accepted")
    @Tag("positive")
    void aiConfidence_boundaryValues_accepted() {
        ScanRecord zero = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, 0.0, null, null);
        ScanRecord one  = new ScanRecord("FooTest", "m", 1, List.of(), null, null, null, null, null, null, 1.0, null, null);

        assertEquals(0.0, zero.aiConfidence());
        assertEquals(1.0, one.aiConfidence());
    }

    // -------------------------------------------------------------------------
    // tagAiDrift values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tagAiDrift accepts none, tag-only and ai-only string values")
    @Tag("positive")
    void tagAiDrift_validValues_stored() {
        ScanRecord none    = new ScanRecord("T", "m", 1, List.of(), null, null, null, null, null, null, null, null, "none");
        ScanRecord tagOnly = new ScanRecord("T", "m", 1, List.of(), null, null, null, null, null, null, null, null, "tag-only");
        ScanRecord aiOnly  = new ScanRecord("T", "m", 1, List.of(), null, null, null, null, null, null, null, null, "ai-only");

        assertEquals("none", none.tagAiDrift());
        assertEquals("tag-only", tagOnly.tagAiDrift());
        assertEquals("ai-only", aiOnly.tagAiDrift());
    }

    // -------------------------------------------------------------------------
    // Record equality and hashCode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("two records with identical fields are equal and share hashCode")
    @Tag("positive")
    void equality_identicalFields_equal() {
        ScanRecord a = new ScanRecord("FooTest", "m", 5, List.of("security"), "dn",
                "hash", true, "ai-dn", List.of("tag"), "reason", 0.9, 0.5, "none");
        ScanRecord b = new ScanRecord("FooTest", "m", 5, List.of("security"), "dn",
                "hash", true, "ai-dn", List.of("tag"), "reason", 0.9, 0.5, "none");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("records differing in fqcn are not equal")
    @Tag("positive")
    void equality_differentFqcn_notEqual() {
        ScanRecord a = minimal("com.acme.FooTest", "m");
        ScanRecord b = minimal("com.acme.BarTest", "m");

        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("records differing in aiSecurityRelevant are not equal")
    @Tag("positive")
    void equality_differentAiSecurityRelevant_notEqual() {
        ScanRecord a = new ScanRecord("T", "m", 1, List.of(), null, null, true, null, null, null, null, null, null);
        ScanRecord b = new ScanRecord("T", "m", 1, List.of(), null, null, false, null, null, null, null, null, null);

        assertFalse(a.equals(b));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScanRecord minimal(String fqcn, String method) {
        return new ScanRecord(fqcn, method, 1, List.of(), null, null, null, null, null, null, null, null, null);
    }

    private static ScanRecord minimal_withLoc(int loc) {
        return new ScanRecord("FooTest", "m", loc, List.of(), null, null, null, null, null, null, null, null, null);
    }
}
