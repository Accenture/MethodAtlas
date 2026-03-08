package org.egothor.methodatlas.ai;

/**
 * Utility responsible for constructing the prompt supplied to AI providers for
 * security classification of JUnit test classes.
 *
 * <p>
 * The prompt produced by this class combines several components into a single
 * instruction payload:
 * </p>
 *
 * <ul>
 * <li>classification instructions for the AI model</li>
 * <li>a controlled security taxonomy definition</li>
 * <li>strict output formatting rules</li>
 * <li>the fully qualified class name</li>
 * <li>the complete source code of the analyzed test class</li>
 * </ul>
 *
 * <p>
 * The resulting prompt is passed to the configured AI provider and instructs
 * the model to produce a deterministic JSON classification result describing
 * security relevance and taxonomy tags for individual test methods.
 * </p>
 *
 * <p>
 * The prompt enforces a closed taxonomy and strict JSON output rules to ensure
 * that the returned content can be parsed reliably by the application.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see AiSuggestionEngine
 * @see AiProviderClient
 * @see DefaultSecurityTaxonomy
 * @see OptimizedSecurityTaxonomy
 */
public final class PromptBuilder {
    /**
     * Prevents instantiation of this utility class.
     */
    private PromptBuilder() {
    }

    /**
     * Builds the complete prompt supplied to an AI provider for security
     * classification of a JUnit test class.
     *
     * <p>
     * The generated prompt contains:
     * </p>
     *
     * <ul>
     * <li>task instructions describing the classification objective</li>
     * <li>the security taxonomy definition controlling allowed tags</li>
     * <li>strict output rules enforcing JSON-only responses</li>
     * <li>a formal JSON schema describing the expected result structure</li>
     * <li>the fully qualified class name of the analyzed test class</li>
     * <li>the complete class source used as analysis input</li>
     * </ul>
     *
     * <p>
     * The taxonomy text supplied to this method is typically obtained from either
     * {@link DefaultSecurityTaxonomy#text()} or
     * {@link OptimizedSecurityTaxonomy#text()}, depending on the selected
     * {@link AiOptions.TaxonomyMode}.
     * </p>
     *
     * <p>
     * The returned prompt is intended to be used as the content of a user message
     * in chat-based inference APIs.
     * </p>
     *
     * @param fqcn         fully qualified class name of the test class being
     *                     analyzed
     * @param classSource  complete source code of the test class
     * @param taxonomyText taxonomy definition guiding classification
     * @return formatted prompt supplied to the AI provider
     *
     * @see AiSuggestionEngine#suggestForClass(String, String)
     */
    public static String build(String fqcn, String classSource, String taxonomyText) {
        return """
                You are analyzing a single JUnit 5 test class and suggesting security tags.

                TASK
                - Analyze the WHOLE class for context.
                - Return per-method suggestions for JUnit test methods only.
                - Do not invent methods that do not exist.
                - Be conservative.
                - If uncertain, classify the method as securityRelevant=false.
                - Ignore pure functional / performance / UX tests unless they explicitly validate a security property.

                CONTROLLED TAXONOMY
                %s

                OUTPUT RULES
                - Return JSON only.
                - No markdown.
                - No prose outside JSON.
                - Tags must come only from this closed set:
                  security, auth, access-control, crypto, input-validation, injection, data-protection, logging, error-handling, owasp
                - If securityRelevant=true, tags MUST include "security".
                - Add 1-3 tags total per method.
                - displayName must be null when securityRelevant=false.
                - If securityRelevant=true, displayName must match:
                  SECURITY: <control/property> - <scenario>

                JSON SHAPE
                {
                  "className": "string",
                  "classSecurityRelevant": true,
                  "classTags": ["security", "crypto"],
                  "classReason": "string",
                  "methods": [
                    {
                      "methodName": "string",
                      "securityRelevant": true,
                      "displayName": "SECURITY: ...",
                      "tags": ["security", "crypto"],
                      "reason": "string"
                    }
                  ]
                }

                CLASS
                FQCN: %s

                SOURCE
                %s
                """
                .formatted(taxonomyText, fqcn, classSource);
    }
}