package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SuggestionLookup}.
 *
 * <p>
 * This class verifies that a {@link SuggestionLookup} is correctly built from
 * an {@link AiClassSuggestion}, handling null class suggestions, null method
 * lists, empty method lists, invalid method entries (null, blank names), and
 * duplicate method name deduplication. Look-up by method name and null-safety
 * of the {@code find} method are also covered.
 * </p>
 */
@Tag("unit")
@Tag("suggestion-lookup")
class SuggestionLookupTest {

    @Test
    @DisplayName("from(null) returns a non-null empty lookup that finds nothing")
    @Tag("edge-case")
    void from_nullSuggestion_returnsEmptyLookup() {
        SuggestionLookup lookup = SuggestionLookup.from(null);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAuthenticateUser").isPresent());
    }

    @Test
    @DisplayName("from suggestion with null methods list returns empty lookup")
    @Tag("edge-case")
    void from_nullMethods_returnsEmptyLookup() {
        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "access-control"), "Class contains access-control related tests.", null);

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAllowOwnerToReadOwnStatement").isPresent());
    }

    @Test
    @DisplayName("from suggestion with empty methods list returns empty lookup")
    @Tag("edge-case")
    void from_emptyMethods_returnsEmptyLookup() {
        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "access-control"), "Class contains access-control related tests.", List.of());

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAllowOwnerToReadOwnStatement").isPresent());
    }

    @Test
    @DisplayName("from filters out null entries, null method names, and blank method names from the methods list")
    @Tag("positive")
    void from_filtersNullBlankAndMissingMethodNames() {
        AiMethodSuggestion valid = new AiMethodSuggestion("shouldRejectUnauthenticatedRequest", true,
                "Reject unauthenticated access", List.of("security", "authentication", "access-control"),
                "The test verifies anonymous access is rejected.", 0.0, 0.0);

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "authentication", "access-control"), "Class tests protected-access scenarios.",
                Arrays.asList(null,
                        new AiMethodSuggestion(null, true, "Invalid", List.of("security"), "missing method name", 0.0, 0.0),
                        new AiMethodSuggestion("", true, "Invalid", List.of("security"), "blank method name", 0.0, 0.0),
                        new AiMethodSuggestion("   ", true, "Invalid", List.of("security"), "blank method name", 0.0, 0.0),
                        valid));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertFalse(lookup.find("missing").isPresent());

        Optional<AiMethodSuggestion> found = lookup.find("shouldRejectUnauthenticatedRequest");
        assertTrue(found.isPresent());
        assertSame(valid, found.get());
        assertTrue(found.get().tags().contains("security"));
        assertTrue(found.get().tags().contains("authentication"));
        assertTrue(found.get().tags().contains("access-control"));
    }

    @Test
    @DisplayName("from keeps first occurrence when duplicate method names are present")
    @Tag("edge-case")
    void from_duplicateMethodNames_keepsFirstOccurrence() {
        AiMethodSuggestion first = new AiMethodSuggestion("shouldAllowAdministratorToReadAnyStatement", true,
                "Allow administrative access", List.of("security", "access-control", "authorization"),
                "The test verifies that an administrator is allowed access.", 0.0, 0.0);

        AiMethodSuggestion duplicate = new AiMethodSuggestion("shouldAllowAdministratorToReadAnyStatement", true,
                "Ignore duplicate", List.of("security", "logging"), "A later duplicate entry that must be ignored.",
                0.0, 0.0);

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "access-control"), "Class covers authorization scenarios.",
                List.of(first, duplicate));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        Optional<AiMethodSuggestion> found = lookup.find("shouldAllowAdministratorToReadAnyStatement");

        assertTrue(found.isPresent());
        assertSame(first, found.get());
        assertTrue(found.get().tags().contains("authorization"));
        assertFalse(found.get().tags().contains("logging"));
    }

    @Test
    @DisplayName("find returns the matching AiMethodSuggestion for an existing method name")
    @Tag("positive")
    void find_existingMethod_returnsSuggestion() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldRejectRelativePathTraversalSequence", true,
                "Reject path traversal payload", List.of("security", "input-validation", "path-traversal"),
                "The test rejects a parent-directory traversal sequence.", 0.0, 0.0);

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.storage.PathTraversalValidationTest",
                Boolean.TRUE, List.of("security", "input-validation"), "Class validates filesystem input handling.",
                List.of(method));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        Optional<AiMethodSuggestion> found = lookup.find("shouldRejectRelativePathTraversalSequence");

        assertTrue(found.isPresent());
        assertSame(method, found.get());
        assertTrue(found.get().tags().contains("security"));
        assertTrue(found.get().tags().contains("input-validation"));
        assertTrue(found.get().tags().contains("path-traversal"));
    }

    @Test
    @DisplayName("find returns empty Optional for a method name that is not in the lookup")
    @Tag("negative")
    void find_missingMethod_returnsEmptyOptional() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldWriteAuditEventForPrivilegeChange", true,
                "Audit privilege changes", List.of("security", "audit", "logging"),
                "The test verifies audit logging for a security-sensitive action.", 0.0, 0.0);

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.audit.AuditLoggingTest", Boolean.TRUE,
                List.of("security", "audit", "logging"), "Class contains audit and secure logging tests.",
                List.of(method));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertFalse(lookup.find("shouldFormatHumanReadableSupportMessage").isPresent());
    }

    @Test
    @DisplayName("find throws NullPointerException for null method name argument")
    @Tag("negative")
    void find_nullMethodName_throwsNullPointerException() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldNotLogRawBearerToken", true,
                "Redact bearer token in logs", List.of("security", "logging", "secrets-handling"),
                "The test ensures sensitive credentials are not written to logs.", 0.0, 0.0);

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.audit.AuditLoggingTest", Boolean.TRUE,
                List.of("security", "logging"), "Class checks secure logging behavior.", List.of(method));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertThrows(NullPointerException.class, () -> lookup.find(null));
    }
}
