package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    @Test
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

        String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText);

        assertTrue(prompt.contains("FQCN: " + fqcn));
        assertTrue(prompt.contains(classSource));
        assertTrue(prompt.contains(taxonomyText));
    }

    @Test
    void build_containsExpectedTaskInstructions() {
        String prompt = PromptBuilder.build("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}",
                "security, logging");

        assertTrue(prompt.contains("You are analyzing a single JUnit 5 test class and suggesting security tags."));
        assertTrue(prompt.contains("- Analyze the WHOLE class for context."));
        assertTrue(prompt.contains("- Return per-method suggestions for JUnit test methods only."));
        assertTrue(prompt.contains("- Do not invent methods that do not exist."));
        assertTrue(prompt.contains("- Be conservative."));
        assertTrue(prompt.contains("- If uncertain, classify the method as securityRelevant=false."));
    }

    @Test
    void build_containsClosedTaxonomyRules() {
        String prompt = PromptBuilder.build("com.acme.storage.PathTraversalValidationTest",
                "class PathTraversalValidationTest {}", "security, input-validation, injection");

        assertTrue(prompt.contains("Tags must come only from this closed set:"));
        assertTrue(prompt.contains(
                "security, auth, access-control, crypto, input-validation, injection, data-protection, logging, error-handling, owasp"));
        assertTrue(prompt.contains("If securityRelevant=true, tags MUST include \"security\"."));
        assertTrue(prompt.contains("Add 1-3 tags total per method."));
    }

    @Test
    void build_containsDisplayNameRules() {
        String prompt = PromptBuilder.build("com.acme.security.AccessControlServiceTest",
                "class AccessControlServiceTest {}", "security, auth, access-control");

        assertTrue(prompt.contains("displayName must be null when securityRelevant=false."));
        assertTrue(prompt.contains("If securityRelevant=true, displayName must match:"));
        assertTrue(prompt.contains("SECURITY: <control/property> - <scenario>"));
    }

    @Test
    void build_containsJsonShapeContract() {
        String prompt = PromptBuilder.build("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}",
                "security, logging");

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
    void build_includesCompleteClassSourceVerbatim() {
        String classSource = """
                class PathTraversalValidationTest {

                    void shouldRejectRelativePathTraversalSequence() {
                        String userInput = "../etc/passwd";
                    }
                }
                """;

        String prompt = PromptBuilder.build("com.acme.storage.PathTraversalValidationTest", classSource,
                "security, input-validation, injection");

        assertTrue(prompt.contains("String userInput = \"../etc/passwd\";"));
        assertTrue(prompt.contains("void shouldRejectRelativePathTraversalSequence()"));
    }

    @Test
    void build_isDeterministicForSameInput() {
        String fqcn = "com.example.X";
        String source = "class X {}";
        String taxonomy = "security, logging";

        String prompt1 = PromptBuilder.build(fqcn, source, taxonomy);
        String prompt2 = PromptBuilder.build(fqcn, source, taxonomy);

        assertEquals(prompt1, prompt2);
    }
}