package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptTemplateValidatorTest {

    @Test
    void builtInTemplatesAreValidForTheirKind() {
        PromptTemplateSet defaults = PromptTemplateSet.defaults();
        for (PromptTemplateKind kind : PromptTemplateKind.values()) {
            assertTrue(PromptTemplateValidator.validate(kind, defaults.get(kind)).isEmpty(),
                    "built-in " + kind + " template must validate clean");
        }
    }

    @Test
    void tokensInFindsPlaceholdersAndIgnoresJsonBraces() {
        Set<String> tokens = PromptTemplateValidator.tokensIn("a {fqcn} b { \"x\": 1 } {classSource}");
        assertEquals(Set.of("fqcn", "classSource"), tokens);
    }

    @Test
    void unknownPlaceholderIsReported() {
        List<String> problems = PromptTemplateValidator.validate(PromptTemplateKind.DEDICATED_TRIAGE,
                "{candidates} {classSource} {bogus} \"secrets\"");
        assertTrue(problems.stream().anyMatch(p -> p.contains("{bogus}")),
                "an unknown placeholder must be reported: " + problems);
    }

    @Test
    void missingRequiredPlaceholderIsReported() {
        // DEDICATED_TRIAGE requires {candidates} and {classSource}; omit classSource.
        List<String> problems = PromptTemplateValidator.validate(PromptTemplateKind.DEDICATED_TRIAGE,
                "{candidates} only \"secrets\"");
        assertTrue(problems.stream().anyMatch(p -> p.contains("{classSource}")),
                "a missing required placeholder must be reported: " + problems);
    }

    @Test
    void missingStructuralAnchorIsReported() {
        List<String> problems = PromptTemplateValidator.validate(PromptTemplateKind.DEDICATED_TRIAGE,
                "{candidates} {classSource} but no anchor");
        assertTrue(problems.stream().anyMatch(p -> p.contains("structural anchor")),
                "a missing JSON anchor must be reported: " + problems);
    }

    @Test
    void emptyTemplateIsReported() {
        assertEquals(List.of("template is empty"),
                PromptTemplateValidator.validate(PromptTemplateKind.CLASSIFICATION, "   "));
    }

    @Test
    void validateOrThrowThrowsWithKindAndSourceLabel() {
        PromptTemplateException ex = assertThrows(PromptTemplateException.class,
                () -> PromptTemplateValidator.validateOrThrow(
                        PromptTemplateKind.TRIAGE_APPENDIX, "no tokens, no anchor", "my-file.txt"));
        assertTrue(ex.getMessage().contains("TRIAGE_APPENDIX"), "message names the kind");
        assertTrue(ex.getMessage().contains("my-file.txt"), "message names the source");
    }

    @Test
    void validateOrThrowAcceptsValidTemplate() {
        PromptTemplateValidator.validateOrThrow(PromptTemplateKind.TRIAGE_APPENDIX,
                PromptTemplateSet.defaults().triageAppendix(), "built-in");
    }

    @Test
    void classificationAllowsOptionalConfidenceTokens() {
        // {confidenceRules}/{confidenceField} are allowed but not required.
        List<String> problems = PromptTemplateValidator.validate(PromptTemplateKind.CLASSIFICATION,
                "{taxonomy} {methods} [{expectedMethodNames}] {classSource} {confidenceRules}{confidenceField} \"methods\"");
        assertTrue(problems.isEmpty(), "optional confidence tokens must be accepted: " + problems);
    }
}
