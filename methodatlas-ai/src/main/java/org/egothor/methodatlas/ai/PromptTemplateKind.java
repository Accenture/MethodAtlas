package org.egothor.methodatlas.ai;

import java.util.Set;

/**
 * The distinct prompt templates MethodAtlas sends to an AI provider, each with
 * its own set of permitted and required placeholder tokens.
 *
 * <p>
 * A template is plain text containing {@code {token}} placeholders that
 * {@link PromptBuilder} substitutes with deterministically-derived data before
 * the prompt is sent. Each kind declares:
 * </p>
 * <ul>
 *   <li>its <em>allowed</em> tokens — any other {@code {token}} in a user-supplied
 *       template is rejected as a typo;</li>
 *   <li>its <em>required</em> tokens — omitting one would drop data the model needs
 *       (for example the class source), so it is rejected;</li>
 *   <li>a <em>structural anchor</em> — a literal substring the rendered prompt must
 *       still contain so the provider response parser keeps working (for example the
 *       {@code "methods"} or {@code "secrets"} JSON key).</li>
 * </ul>
 *
 * <p>
 * This type is immutable and thread-safe.
 * </p>
 *
 * @since 4.1.0
 */
// Set.of(...) returns deeply-immutable sets; the Set field type is what Error Prone's
// ImmutableEnumChecker cannot prove, so the finding is a false positive here.
@SuppressWarnings("ImmutableEnumChecker")
public enum PromptTemplateKind {

    /**
     * The method-classification prompt. Renders the taxonomy, the deterministically
     * extracted target methods, and the class source, and requests a {@code methods}
     * JSON array.
     */
    CLASSIFICATION(
            Set.of("taxonomy", "methods", "expectedMethodNames", "classSource"),
            Set.of("taxonomy", "methods", "expectedMethodNames", "classSource", "fqcn",
                    "confidenceRules", "confidenceField"),
            "\"methods\""),

    /**
     * The credential-triage appendix appended to a classification prompt so a single
     * provider call returns both classifications and credential verdicts. Renders only
     * the candidate list and requests a {@code secrets} JSON array.
     */
    TRIAGE_APPENDIX(
            Set.of("candidates"),
            Set.of("candidates"),
            "secrets"),

    /**
     * The standalone credential-triage prompt used when triage is not folded into a
     * classification call. Renders the candidate list and class source and requests a
     * {@code secrets} JSON array.
     */
    DEDICATED_TRIAGE(
            Set.of("candidates", "classSource"),
            Set.of("candidates", "classSource", "fqcn"),
            "\"secrets\"");

    private final Set<String> requiredTokens;
    private final Set<String> allowedTokens;
    private final String structuralAnchor;

    PromptTemplateKind(Set<String> requiredTokens, Set<String> allowedTokens, String structuralAnchor) {
        this.requiredTokens = requiredTokens;
        this.allowedTokens = allowedTokens;
        this.structuralAnchor = structuralAnchor;
    }

    /**
     * Returns the placeholder tokens that a template of this kind must contain.
     *
     * @return an unmodifiable set of token names (without the surrounding braces);
     *         never {@code null}
     */
    public Set<String> requiredTokens() {
        return requiredTokens;
    }

    /**
     * Returns the placeholder tokens that a template of this kind may contain.
     *
     * @return an unmodifiable set of token names (without the surrounding braces);
     *         a superset of {@link #requiredTokens()}; never {@code null}
     */
    public Set<String> allowedTokens() {
        return allowedTokens;
    }

    /**
     * Returns a literal substring that a rendered prompt of this kind must still
     * contain for the provider response parser to function.
     *
     * @return the structural anchor substring; never {@code null}, never empty
     */
    public String structuralAnchor() {
        return structuralAnchor;
    }
}
