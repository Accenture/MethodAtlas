package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptBuilder} and its nested {@link PromptBuilder.TargetMethod} record.
 *
 * <p>
 * This class verifies that prompts are built with the correct FQCN, class source,
 * taxonomy text, target method listing (including line range formatting), task
 * instructions, closed taxonomy rules, display name rules, JSON shape contract,
 * and confidence handling. Null-check enforcement on both {@code TargetMethod}
 * and {@code build} parameters is also covered.
 * </p>
 */
@Tag("unit")
@Tag("prompt-builder")
class PromptBuilderTest {

    @Test
    @DisplayName("build contains FQCN, class source, taxonomy text, and target method line references")
    @Tag("positive")
    void build_containsFqcnSourceAndTaxonomy() {
        String fqcn = "com.acme.security.AccessControlServiceTest";
        String classSource = """
                package com.acme.security;

                import org.junit.jupiter.api.Test;

                class AccessControlServiceTest {

                    @Test
                    void shouldRejectUnauthenticatedRequest() {}

                    @Test
                    void shouldAllowOwnerToReadOwnStatement() {}
                }
                """;
        String taxonomyText = """
                SECURITY TAXONOMY
                - security
                - auth
                - access-control
                - input-validation
                - logging
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", 8, 8),
                new PromptBuilder.TargetMethod("shouldAllowOwnerToReadOwnStatement", 11, 11));

        String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods, false);

        assertTrue(prompt.contains("FQCN: " + fqcn));
        assertTrue(prompt.contains(classSource));
        assertTrue(prompt.contains(taxonomyText));
        assertTrue(prompt.contains("- shouldRejectUnauthenticatedRequest [lines 8-8]"));
        assertTrue(prompt.contains("- shouldAllowOwnerToReadOwnStatement [lines 11-11]"));
    }

    @Test
    @DisplayName("build contains the expected task instructions section")
    @Tag("positive")
    void build_containsExpectedTaskInstructions() {
        String prompt = PromptBuilder.build("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}",
                "security, logging",
                List.of(new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null)), false);

        assertTrue(prompt.contains("You are analyzing a single JUnit 5 test class and suggesting security tags."));
        assertTrue(prompt.contains("- Analyze the WHOLE class for context."));
        assertTrue(prompt.contains("- Classify ONLY the methods explicitly listed in TARGET TEST METHODS."));
        assertTrue(prompt.contains("- Do not invent methods that do not exist."));
        assertTrue(prompt.contains(
                "- Do not classify helper methods, lifecycle methods, nested classes, or any method not listed."));
        assertTrue(prompt.contains("- Be conservative."));
        assertTrue(prompt.contains("- If uncertain, classify the method as securityRelevant=false."));
    }

    @Test
    @DisplayName("build contains closed taxonomy rules including the fixed tag set")
    @Tag("positive")
    void build_containsClosedTaxonomyRules() {
        String prompt = PromptBuilder.build("com.acme.storage.PathTraversalValidationTest",
                "class PathTraversalValidationTest {}", "security, input-validation, injection",
                List.of(new PromptBuilder.TargetMethod("shouldRejectRelativePathTraversalSequence", null, null)),
                false);

        assertTrue(prompt.contains("Tags must come only from this closed set:"));
        assertTrue(prompt.contains(
                "security, auth, access-control, crypto, input-validation, injection, data-protection, logging, error-handling, owasp"));
        assertTrue(prompt.contains("If securityRelevant=true, tags MUST include \"security\"."));
        assertTrue(prompt.contains("Add 1-3 tags total per method."));
    }

    @Test
    @DisplayName("build contains display name formatting rules")
    @Tag("positive")
    void build_containsDisplayNameRules() {
        String prompt = PromptBuilder.build("com.acme.security.AccessControlServiceTest",
                "class AccessControlServiceTest {}", "security, auth, access-control",
                List.of(new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null)), false);

        assertTrue(prompt.contains("If securityRelevant=false, displayName must be null."));
        assertTrue(prompt.contains("If securityRelevant=true, displayName must match:"));
        assertTrue(prompt.contains("SECURITY: <control/property> - <scenario>"));
    }

    @Test
    @DisplayName("build contains JSON shape contract with all required fields")
    @Tag("positive")
    void build_containsJsonShapeContract() {
        String prompt = PromptBuilder.build("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}",
                "security, logging",
                List.of(new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null)), false);

        assertTrue(prompt.contains("JSON SHAPE"));
        assertTrue(prompt.contains("\"className\": \"string\""));
        assertTrue(prompt.contains("\"classSecurityRelevant\": true"));
        assertTrue(prompt.contains("\"classTags\": [\"security\", \"crypto\"]"));
        assertTrue(prompt.contains("\"classReason\": \"string\""));
        assertTrue(prompt.contains("\"methods\": ["));
        assertTrue(prompt.contains("\"methodName\": \"string\""));
        assertTrue(prompt.contains("\"securityRelevant\": true"));
        assertTrue(prompt.contains("\"displayName\": \"SECURITY: ...\""));
        assertTrue(prompt.contains("\"tags\": [\"security\", \"crypto\"]"));
        assertTrue(prompt.contains("\"reason\": \"string\""));
    }

    @Test
    @DisplayName("build includes the complete class source verbatim in the prompt")
    @Tag("positive")
    void build_includesCompleteClassSourceVerbatim() {
        String classSource = """
                class PathTraversalValidationTest {

                    void shouldRejectRelativePathTraversalSequence() {
                        String userInput = "../etc/passwd";
                    }
                }
                """;

        String prompt = PromptBuilder.build("com.acme.storage.PathTraversalValidationTest", classSource,
                "security, input-validation, injection",
                List.of(new PromptBuilder.TargetMethod("shouldRejectRelativePathTraversalSequence", 3, 5)), false);

        assertTrue(prompt.contains("String userInput = \"../etc/passwd\";"));
        assertTrue(prompt.contains("void shouldRejectRelativePathTraversalSequence()"));
        assertTrue(prompt.contains("- shouldRejectRelativePathTraversalSequence [lines 3-5]"));
    }

    @Test
    @DisplayName("build includes expected method names constraint with all listed method names")
    @Tag("positive")
    void build_includesExpectedMethodNamesConstraint() {
        String prompt = PromptBuilder.build("com.acme.tests.SampleOneTest", "class SampleOneTest {}",
                "security, crypto", List.of(new PromptBuilder.TargetMethod("alpha", 1, 1),
                        new PromptBuilder.TargetMethod("beta", 2, 2), new PromptBuilder.TargetMethod("gamma", 3, 3)),
                false);

        assertTrue(prompt.contains("- methodName values in the output must exactly match one of:"));
        assertTrue(prompt.contains("[\"alpha\", \"beta\", \"gamma\"]"));
        assertTrue(prompt.contains("- Do not omit any listed method."));
        assertTrue(prompt.contains("- Do not include any additional methods."));
    }

    @Test
    @DisplayName("build is deterministic for the same input")
    @Tag("positive")
    void build_isDeterministicForSameInput() {
        String fqcn = "com.example.X";
        String source = "class X {}";
        String taxonomy = "security, logging";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(new PromptBuilder.TargetMethod("alpha", null, null));

        String prompt1 = PromptBuilder.build(fqcn, source, taxonomy, targetMethods, false);
        String prompt2 = PromptBuilder.build(fqcn, source, taxonomy, targetMethods, false);

        assertEquals(prompt1, prompt2);
    }

    @Test
    @DisplayName("build with confidence=true includes confidence rules and JSON confidence field")
    @Tag("positive")
    void build_withConfidence_includesConfidenceRulesAndJsonField() {
        String prompt = PromptBuilder.build("com.acme.security.AccessControlServiceTest",
                "class AccessControlServiceTest {}", "security, auth",
                List.of(new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null)), true);

        assertTrue(prompt.contains("confidence must be a decimal between 0.0 and 1.0"));
        assertTrue(prompt.contains("1.0"));
        assertTrue(prompt.contains("0.7"));
        assertTrue(prompt.contains("0.5"));
        assertTrue(prompt.contains("\"confidence\": 0.9"));
    }

    @Test
    @DisplayName("build with confidence=false excludes confidence rules and JSON confidence field")
    @Tag("positive")
    void build_withoutConfidence_excludesConfidenceRulesAndJsonField() {
        String prompt = PromptBuilder.build("com.acme.security.AccessControlServiceTest",
                "class AccessControlServiceTest {}", "security, auth",
                List.of(new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null)), false);

        assertFalse(prompt.contains("confidence must be a decimal"));
        assertFalse(prompt.contains("\"confidence\": 0.9"));
    }

    @Test
    @DisplayName("build throws IllegalArgumentException for empty targetMethods list")
    @Tag("negative")
    void build_rejectsEmptyTargetMethods() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PromptBuilder.build("com.example.X", "class X {}", "security", List.of(), false));

        assertEquals("targetMethods must not be empty", ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // New tests: TargetMethod null/blank validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TargetMethod throws NullPointerException for null methodName")
    @Tag("negative")
    void targetMethod_nullMethodName_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new PromptBuilder.TargetMethod(null, 1, 2));
    }

    @Test
    @DisplayName("TargetMethod throws IllegalArgumentException for blank methodName")
    @Tag("negative")
    void targetMethod_blankMethodName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptBuilder.TargetMethod("   ", 1, 2));
    }

    @Test
    @DisplayName("TargetMethod throws IllegalArgumentException for empty methodName")
    @Tag("negative")
    void targetMethod_emptyMethodName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptBuilder.TargetMethod("", 1, 2));
    }

    // -------------------------------------------------------------------------
    // New tests: build null-check behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("build throws NullPointerException for null fqcn")
    @Tag("negative")
    void build_nullFqcn_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> PromptBuilder.build(null, "class X {}", "security",
                        List.of(new PromptBuilder.TargetMethod("m", 1, 1)), false));
    }

    @Test
    @DisplayName("build throws NullPointerException for null classSource")
    @Tag("negative")
    void build_nullClassSource_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> PromptBuilder.build("com.example.X", null, "security",
                        List.of(new PromptBuilder.TargetMethod("m", 1, 1)), false));
    }

    @Test
    @DisplayName("build throws NullPointerException for null targetMethods")
    @Tag("negative")
    void build_nullTargetMethods_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> PromptBuilder.build("com.example.X", "class X {}", "security", null, false));
    }

    // -------------------------------------------------------------------------
    // New tests: TargetMethod line range formatting
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("build with TargetMethod(method, null, null) produces '- method' without '[lines' in output")
    @Tag("edge-case")
    void build_targetMethodWithNullLines_omitsLineRange() {
        String prompt = PromptBuilder.build("com.example.X", "class X {}",
                "security",
                List.of(new PromptBuilder.TargetMethod("method", null, null)), false);

        assertTrue(prompt.contains("- method"), prompt);
        assertFalse(prompt.contains("[lines"), prompt);
    }

    @Test
    @DisplayName("build with TargetMethod(method, 5, null) produces '- method [lines 5-?]' in output")
    @Tag("edge-case")
    void build_targetMethodWithOnlyBeginLine_showsQuestionMarkForEnd() {
        String prompt = PromptBuilder.build("com.example.X", "class X {}",
                "security",
                List.of(new PromptBuilder.TargetMethod("method", 5, null)), false);

        assertTrue(prompt.contains("- method [lines 5-?]"), prompt);
    }
}
