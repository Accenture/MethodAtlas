package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the credential-triage value types and their interaction with
 * {@link AiClassSuggestion} and {@link AiProviderClient#normalize(AiClassSuggestion)}.
 */
class CredentialTriageVerdictTest {

    @Test
    void verdictCarriesFields() {
        CredentialTriageVerdict v = new CredentialTriageVerdict(2, 0.91, "s3.amazonaws.com", "looks live");
        assertEquals(2, v.candidateIndex());
        assertEquals(0.91, v.credibilityScore(), 1e-9);
        assertEquals("s3.amazonaws.com", v.endpoint());
        assertEquals("looks live", v.rationale());
    }

    @Test
    void classSuggestionConvenienceConstructorDefaultsSecretsToNull() {
        AiClassSuggestion s = new AiClassSuggestion("Foo", null, List.of(), null, List.of());
        assertNull(s.secrets());
    }

    @Test
    void classSuggestionCarriesSecrets() {
        AiClassSuggestion s = new AiClassSuggestion("Foo", null, List.of(), null, List.of(),
                List.of(new CredentialTriageVerdict(0, 0.5, "db", "maybe")));
        assertEquals(1, s.secrets().size());
    }

    @Test
    void normalizePreservesSecrets() {
        AiClassSuggestion in = new AiClassSuggestion("Foo", null, List.of(), null, List.of(),
                List.of(new CredentialTriageVerdict(0, 0.8, "db", "x")));
        AiClassSuggestion out = AiProviderClient.normalize(in);
        assertNotNull(out.secrets());
        assertEquals(1, out.secrets().size());
        assertEquals(0.8, out.secrets().get(0).credibilityScore(), 1e-9);
    }

    @Test
    void normalizeToleratesNullSecrets() {
        AiClassSuggestion in = new AiClassSuggestion("Foo", null, List.of(), null, List.of());
        assertNull(AiProviderClient.normalize(in).secrets());
    }

    @Test
    void suggestForClassFiveArgDefaultDelegatesToClassification() throws AiSuggestionException {
        AiSuggestionEngine engine = (fileStem, fqcn, source, methods) ->
                new AiClassSuggestion(fqcn, Boolean.TRUE, List.of("security"), null, List.of());
        AiClassSuggestion result = engine.suggestForClass("stem", "Foo", "class Foo {}", List.of(),
                List.of(new PromptBuilder.CredentialCandidateRef(0, 1, "secret")));
        assertEquals(Boolean.TRUE, result.classSecurityRelevant());
    }

    @Test
    void triageSecretsDefaultReturnsEmpty() throws AiSuggestionException {
        AiSuggestionEngine engine = (fileStem, fqcn, source, methods) ->
                new AiClassSuggestion(fqcn, null, List.of(), null, List.of());
        List<CredentialTriageVerdict> verdicts = engine.triageSecrets("Foo", "class Foo {}",
                List.of(new PromptBuilder.CredentialCandidateRef(0, 1, "secret")));
        assertTrue(verdicts.isEmpty());
    }
}
