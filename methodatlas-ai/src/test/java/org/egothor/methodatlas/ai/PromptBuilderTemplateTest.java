package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PromptBuilder} rendering against a {@link PromptTemplateSet},
 * including operator overrides and single-pass substitution safety.
 */
class PromptBuilderTemplateTest {

    private static final List<PromptBuilder.TargetMethod> METHODS =
            List.of(new PromptBuilder.TargetMethod("shouldLogin", 10, 16));

    @Test
    void defaultClassificationRendersAllData() {
        String prompt = PromptBuilder.build("com.acme.LoginTest", "class LoginTest {}",
                "TAXONOMY-TEXT", METHODS, false);
        assertTrue(prompt.contains("TAXONOMY-TEXT"), "taxonomy is rendered");
        assertTrue(prompt.contains("com.acme.LoginTest"), "fqcn is rendered");
        assertTrue(prompt.contains("shouldLogin"), "target method is rendered");
        assertFalse(prompt.contains("{classSource}"), "no placeholder must survive");
        assertFalse(prompt.contains("{taxonomy}"), "no placeholder must survive");
    }

    @Test
    void customClassificationTemplateIsUsed() {
        PromptTemplateSet custom = PromptTemplateSet.defaults().with(PromptTemplateKind.CLASSIFICATION,
                "CUSTOM {taxonomy} :: [{expectedMethodNames}] :: {methods} :: {classSource} \"methods\"");
        String prompt = PromptBuilder.build(custom, "com.acme.LoginTest", "SRC", "TAX", METHODS, false);
        assertTrue(prompt.startsWith("CUSTOM TAX :: [\"shouldLogin\"]"), "custom wording is used: " + prompt);
        assertTrue(prompt.contains("SRC"), "class source is rendered into the custom template");
    }

    @Test
    void substitutionIsSinglePassSoInjectedTokensSurvive() {
        // A class source that itself contains a placeholder-shaped substring must not
        // be re-interpreted: single-pass substitution leaves it verbatim.
        String source = "String s = \"{taxonomy}\";";
        String prompt = PromptBuilder.build("com.acme.T", source, "REAL-TAXONOMY", METHODS, false);
        assertTrue(prompt.contains("\"{taxonomy}\""),
                "an injected {taxonomy} in the source must survive verbatim");
        assertTrue(prompt.contains("REAL-TAXONOMY"), "the real taxonomy is still rendered once");
    }

    @Test
    void dedicatedTriageRendersCandidates() {
        String prompt = PromptBuilder.buildCredentialTriage("com.acme.T", "class T {}",
                List.of(new PromptBuilder.CredentialCandidateRef(0, 12, "AKIA...")));
        assertTrue(prompt.contains("candidateIndex 0"), "candidate is rendered");
        assertTrue(prompt.contains("AKIA..."), "snippet is rendered");
        assertFalse(prompt.contains("{candidates}"), "no placeholder must survive");
    }

    @Test
    void foldedTriageAppendsCandidatesToClassification() {
        String prompt = PromptBuilder.build("com.acme.T", "class T {}", "TAX", METHODS, false,
                List.of(new PromptBuilder.CredentialCandidateRef(0, 12, "secret-value")));
        assertTrue(prompt.contains("ADDITIONAL TASK"), "the triage appendix is present");
        assertTrue(prompt.contains("secret-value"), "the candidate snippet is present");
    }
}
