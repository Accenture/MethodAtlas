package org.egothor.methodatlas.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;

/**
 * Carries the per-class credential candidates into the scan loop and collects the
 * triage verdicts the AI returns, so classification and credential triage share a
 * single per-class provider call — the class source is transmitted only once.
 *
 * <p>
 * Populated up-front by the credential-detection pass (candidates keyed by fully
 * qualified class name, in finding order) and mutated during the single-threaded
 * discovery loop as each class is classified. Not thread-safe; the scan loop is
 * single-threaded.
 * </p>
 *
 * @since 4.1.0
 */
final class CredentialTriageContext {

    private final Map<String, List<PromptBuilder.CredentialCandidateRef>> candidatesByFqcn;
    private final Map<String, List<CredentialTriageVerdict>> verdictsByFqcn = new HashMap<>();

    /**
     * Creates a context for the supplied candidates.
     *
     * @param candidatesByFqcn candidate spans per class, in finding order; never {@code null}
     */
    /* default */ CredentialTriageContext(Map<String, List<PromptBuilder.CredentialCandidateRef>> candidatesByFqcn) {
        this.candidatesByFqcn = Map.copyOf(candidatesByFqcn);
    }

    /**
     * Returns the candidates for a class, or an empty list when none were detected.
     *
     * @param fqcn fully qualified class name
     * @return candidate spans for the class; never {@code null}
     */
    /* default */ List<PromptBuilder.CredentialCandidateRef> candidatesFor(String fqcn) {
        return candidatesByFqcn.getOrDefault(fqcn, List.of());
    }

    /**
     * Records the triage verdicts returned for a class.
     *
     * @param fqcn     fully qualified class name
     * @param verdicts verdicts from the folded AI call; ignored when {@code null}/empty
     */
    /* default */ void recordVerdicts(String fqcn, List<CredentialTriageVerdict> verdicts) {
        if (verdicts != null && !verdicts.isEmpty()) {
            verdictsByFqcn.put(fqcn, verdicts);
        }
    }

    /**
     * Returns the collected verdicts keyed by class.
     *
     * @return verdicts per class; never {@code null}
     */
    /* default */ Map<String, List<CredentialTriageVerdict>> verdictsByFqcn() {
        return verdictsByFqcn;
    }
}
