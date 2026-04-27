package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.api.ScanRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeltaEntry} factory methods, accessors, and the
 * {@link DeltaEntry#record()} contract.
 */
@Tag("unit")
@Tag("delta")
class DeltaEntryTest {

    private static ScanRecord record(String method) {
        return new ScanRecord("com.acme.FooTest", method, 5, List.of(), null,
                null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // added()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("added() produces ADDED entry with null before and non-null after")
    @Tag("positive")
    void added_changeType_isAdded() {
        ScanRecord after = record("newMethod");

        DeltaEntry entry = DeltaEntry.added(after);

        assertEquals(DeltaEntry.ChangeType.ADDED, entry.changeType());
        assertNull(entry.before(), "before should be null for ADDED entry");
        assertSame(after, entry.after());
    }

    @Test
    @DisplayName("added() produces entry with empty changedFields")
    @Tag("positive")
    void added_changedFields_isEmpty() {
        DeltaEntry entry = DeltaEntry.added(record("m"));

        assertTrue(entry.changedFields().isEmpty(), "ADDED entry should have no changedFields");
    }

    @Test
    @DisplayName("added() record() returns the after record")
    @Tag("positive")
    void added_record_returnsAfter() {
        ScanRecord after = record("newMethod");

        DeltaEntry entry = DeltaEntry.added(after);

        assertSame(after, entry.record(), "record() for ADDED should return after");
    }

    // -------------------------------------------------------------------------
    // removed()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("removed() produces REMOVED entry with non-null before and null after")
    @Tag("positive")
    void removed_changeType_isRemoved() {
        ScanRecord before = record("oldMethod");

        DeltaEntry entry = DeltaEntry.removed(before);

        assertEquals(DeltaEntry.ChangeType.REMOVED, entry.changeType());
        assertSame(before, entry.before());
        assertNull(entry.after(), "after should be null for REMOVED entry");
    }

    @Test
    @DisplayName("removed() produces entry with empty changedFields")
    @Tag("positive")
    void removed_changedFields_isEmpty() {
        DeltaEntry entry = DeltaEntry.removed(record("m"));

        assertTrue(entry.changedFields().isEmpty(), "REMOVED entry should have no changedFields");
    }

    @Test
    @DisplayName("removed() record() returns the before record")
    @Tag("positive")
    void removed_record_returnsBefore() {
        ScanRecord before = record("oldMethod");

        DeltaEntry entry = DeltaEntry.removed(before);

        assertSame(before, entry.record(), "record() for REMOVED should return before");
    }

    // -------------------------------------------------------------------------
    // modified()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("modified() produces MODIFIED entry with both before and after non-null")
    @Tag("positive")
    void modified_changeType_isModified() {
        ScanRecord before = record("m");
        ScanRecord after = record("m");

        DeltaEntry entry = DeltaEntry.modified(before, after, Set.of("loc"));

        assertEquals(DeltaEntry.ChangeType.MODIFIED, entry.changeType());
        assertSame(before, entry.before());
        assertSame(after, entry.after());
    }

    @Test
    @DisplayName("modified() preserves the supplied changedFields set")
    @Tag("positive")
    void modified_changedFields_preserved() {
        Set<String> fields = Set.of("loc", "tags", "source");
        DeltaEntry entry = DeltaEntry.modified(record("m"), record("m"), fields);

        assertEquals(fields, entry.changedFields());
    }

    @Test
    @DisplayName("modified() defends against mutation of the caller's set")
    @Tag("positive")
    void modified_changedFields_isCopy() {
        java.util.HashSet<String> fields = new java.util.HashSet<>(Set.of("loc"));
        DeltaEntry entry = DeltaEntry.modified(record("m"), record("m"), fields);

        fields.add("tags"); // mutate the original set

        assertFalse_setContains(entry.changedFields(), "tags",
                "changedFields should be an immutable copy, not the caller's set");
    }

    @Test
    @DisplayName("modified() record() returns the after record")
    @Tag("positive")
    void modified_record_returnsAfter() {
        ScanRecord before = record("m");
        ScanRecord after  = record("m");

        DeltaEntry entry = DeltaEntry.modified(before, after, Set.of("loc"));

        assertSame(after, entry.record(), "record() for MODIFIED should return after");
    }

    @Test
    @DisplayName("modified() with empty changedFields set is accepted")
    @Tag("positive")
    void modified_emptyChangedFields_accepted() {
        DeltaEntry entry = DeltaEntry.modified(record("m"), record("m"), Set.of());

        assertNotNull(entry);
        assertEquals(DeltaEntry.ChangeType.MODIFIED, entry.changeType());
        assertTrue(entry.changedFields().isEmpty());
    }

    // -------------------------------------------------------------------------
    // record() — general contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("record() never returns null for any change type")
    @Tag("positive")
    void record_neverNull_forAllChangeTypes() {
        ScanRecord r = record("m");

        assertNotNull(DeltaEntry.added(r).record());
        assertNotNull(DeltaEntry.removed(r).record());
        assertNotNull(DeltaEntry.modified(r, r, Set.of()).record());
    }

    // -------------------------------------------------------------------------
    // ChangeType enum
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ChangeType enum has exactly three values")
    @Tag("positive")
    void changeType_threeValues() {
        assertEquals(3, DeltaEntry.ChangeType.values().length);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void assertFalse_setContains(Set<String> set, String value, String message) {
        if (set.contains(value)) {
            throw new AssertionError(message + " — set unexpectedly contained '" + value + "'");
        }
    }
}
