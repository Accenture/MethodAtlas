package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SuggestionLookupTest {

    @Test
    void from_nullSuggestion_returnsEmptyLookup() {
        SuggestionLookup lookup = SuggestionLookup.from(null);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAuthenticateUser").isPresent());
    }

    @Test
    void from_nullMethods_returnsEmptyLookup() {
        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "access-control"), "Class contains access-control related tests.", null);

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAllowOwnerToReadOwnStatement").isPresent());
    }

    @Test
    void from_emptyMethods_returnsEmptyLookup() {
        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "access-control"), "Class contains access-control related tests.", List.of());

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertNotNull(lookup);
        assertFalse(lookup.find("shouldAllowOwnerToReadOwnStatement").isPresent());
    }

    @Test
    void from_filtersNullBlankAndMissingMethodNames() {
        AiMethodSuggestion valid = new AiMethodSuggestion("shouldRejectUnauthenticatedRequest", true,
                "Reject unauthenticated access", List.of("security", "authentication", "access-control"),
                "The test verifies anonymous access is rejected.");

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", Boolean.TRUE,
                List.of("security", "authentication", "access-control"), "Class tests protected-access scenarios.",
                Arrays.asList(null,
                        new AiMethodSuggestion(null, true, "Invalid", List.of("security"), "missing method name"),
                        new AiMethodSuggestion("", true, "Invalid", List.of("security"), "blank method name"),
                        new AiMethodSuggestion("   ", true, "Invalid", List.of("security"), "blank method name"),
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
    void from_duplicateMethodNames_keepsFirstOccurrence() {
        AiMethodSuggestion first = new AiMethodSuggestion("shouldAllowAdministratorToReadAnyStatement", true,
                "Allow administrative access", List.of("security", "access-control", "authorization"),
                "The test verifies that an administrator is allowed access.");

        AiMethodSuggestion duplicate = new AiMethodSuggestion("shouldAllowAdministratorToReadAnyStatement", true,
                "Ignore duplicate", List.of("security", "logging"), "A later duplicate entry that must be ignored.");

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
    void find_existingMethod_returnsSuggestion() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldRejectRelativePathTraversalSequence", true,
                "Reject path traversal payload", List.of("security", "input-validation", "path-traversal"),
                "The test rejects a parent-directory traversal sequence.");

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
    void find_missingMethod_returnsEmptyOptional() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldWriteAuditEventForPrivilegeChange", true,
                "Audit privilege changes", List.of("security", "audit", "logging"),
                "The test verifies audit logging for a security-sensitive action.");

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.audit.AuditLoggingTest", Boolean.TRUE,
                List.of("security", "audit", "logging"), "Class contains audit and secure logging tests.",
                List.of(method));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertFalse(lookup.find("shouldFormatHumanReadableSupportMessage").isPresent());
    }

    @Test
    void find_nullMethodName_throwsNullPointerException() {
        AiMethodSuggestion method = new AiMethodSuggestion("shouldNotLogRawBearerToken", true,
                "Redact bearer token in logs", List.of("security", "logging", "secrets-handling"),
                "The test ensures sensitive credentials are not written to logs.");

        AiClassSuggestion suggestion = new AiClassSuggestion("com.acme.audit.AuditLoggingTest", Boolean.TRUE,
                List.of("security", "logging"), "Class checks secure logging behavior.", List.of(method));

        SuggestionLookup lookup = SuggestionLookup.from(suggestion);

        assertThrows(NullPointerException.class, () -> lookup.find(null));
    }
}