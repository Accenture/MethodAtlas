package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptBuilder#buildCredentialTriage(String, String, List)}.
 */
class PromptBuilderCredentialTriageTest {

    @Test
    void buildsTriagePromptWithCandidatesAndSource() {
        String prompt = PromptBuilder.buildCredentialTriage("com.acme.Foo", "class Foo {}",
                List.of(new PromptBuilder.CredentialCandidateRef(0, 12, "password=\"hunter2longvalue\"")));
        assertTrue(prompt.contains("candidateIndex 0"), "candidate index listed");
        assertTrue(prompt.contains("(line 12)"), "candidate line listed");
        assertTrue(prompt.contains("credibilityScore"), "requests the score field");
        assertTrue(prompt.contains("\"secrets\""), "requests the secrets array");
        assertTrue(prompt.contains("com.acme.Foo"), "includes the FQCN");
        assertTrue(prompt.contains("class Foo {}"), "includes the class source");
    }

    @Test
    void emptyCandidateListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptBuilder.buildCredentialTriage("Foo", "src", List.of()));
    }

    @Test
    void candidateRefRejectsNullSnippet() {
        assertThrows(NullPointerException.class,
                () -> new PromptBuilder.CredentialCandidateRef(0, 1, null));
    }

    @Test
    void buildFoldsSecretsAppendixIntoClassificationPrompt() {
        String prompt = PromptBuilder.build("com.acme.Foo", "class Foo {}", "TAXONOMY",
                List.of(new PromptBuilder.TargetMethod("t", 1, 2)), false,
                List.of(new PromptBuilder.CredentialCandidateRef(0, 5, "AKIAIOSFODNN7EXAMPLE")));
        assertTrue(prompt.contains("TARGET TEST METHODS"), "base classification prompt present");
        assertTrue(prompt.contains("ADDITIONAL TASK"), "secrets appendix present");
        assertTrue(prompt.contains("candidateIndex 0"), "candidate listed");
        assertTrue(prompt.contains("credibilityScore"), "secrets JSON requested");
    }

    @Test
    void buildWithEmptyCandidatesEqualsBaseBuild() {
        List<PromptBuilder.TargetMethod> targets = List.of(new PromptBuilder.TargetMethod("t", 1, 2));
        String base = PromptBuilder.build("Foo", "src", "TAX", targets, false);
        String withEmpty = PromptBuilder.build("Foo", "src", "TAX", targets, false, List.of());
        assertEquals(base, withEmpty);
    }
}
