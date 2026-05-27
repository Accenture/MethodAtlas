package org.egothor.methodatlas.ai;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * This revision keeps the full class source as semantic context but removes
 * method discovery from the AI model. The caller supplies the exact list of
 * JUnit test methods that must be classified, optionally with source line
 * anchors.
 * </p>
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
     * Deterministically extracted test method descriptor supplied to the prompt.
     *
     * @param methodName name of the JUnit test method
     * @param beginLine  first source line of the method, or {@code null} if unknown
     * @param endLine    last source line of the method, or {@code null} if unknown
     */
    public record TargetMethod(String methodName, Integer beginLine, Integer endLine) {
        public TargetMethod {
            Objects.requireNonNull(methodName, "methodName");
            if (methodName.isBlank()) {
                throw new IllegalArgumentException("methodName must not be blank");
            }
        }
    }

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
     * <li>the exact list of target test methods to classify</li>
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
     * @param fqcn          fully qualified class name of the test class being
     *                      analyzed
     * @param classSource   complete source code of the test class
     * @param taxonomyText  taxonomy definition guiding classification
     * @param targetMethods exact list of deterministically discovered JUnit test
     *                      methods to classify
     * @param confidence    when {@code true}, the prompt instructs the AI to
     *                      include a {@code confidence} score for each method
     *                      classification
     * @return formatted prompt supplied to the AI provider
     *
     * @see AiSuggestionEngine#suggestForClass(String, String, String, List)
     */
    public static String build(String fqcn, String classSource, String taxonomyText, List<TargetMethod> targetMethods,
            boolean confidence) {
        Objects.requireNonNull(fqcn, "fqcn");
        Objects.requireNonNull(classSource, "classSource");
        Objects.requireNonNull(taxonomyText, "taxonomyText");
        Objects.requireNonNull(targetMethods, "targetMethods");

        if (targetMethods.isEmpty()) {
            throw new IllegalArgumentException("targetMethods must not be empty");
        }

        String targetMethodBlock = targetMethods.stream().map(PromptBuilder::formatTargetMethod)
                .collect(Collectors.joining("\n"));

        String expectedMethodNames = targetMethods.stream().map(TargetMethod::methodName)
                .map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));

        String confidenceOutputRules = confidence
                ? "\n- confidence must be a decimal between 0.0 and 1.0 (one decimal place is sufficient).\n"
                        + "  Use these anchor points when setting it:\n"
                        + "    1.0 \u2014 the method name and body explicitly and unambiguously test a named\n"
                        + "          security property (authentication, authorisation, encryption,\n"
                        + "          injection prevention, access control, etc.)\n"
                        + "    0.7 \u2014 the method clearly tests a security-adjacent concern but the mapping\n"
                        + "          requires inference from context, class name, or surrounding code\n"
                        + "    0.5 \u2014 the classification is plausible; the method name or body is equally\n"
                        + "          consistent with a non-security interpretation\n"
                        + "  Prefer securityRelevant=false rather than returning a confidence value below\n"
                        + "  0.5. When securityRelevant=false, set confidence to 0.0."
                : "";
        String confidenceJsonField = confidence ? "\n              \"confidence\": 0.9," : "";

        return """
                You are analyzing a single JUnit 5 test class and suggesting security tags.

                TASK
                - Analyze the WHOLE class for context.
                - Classify ONLY the methods explicitly listed in TARGET TEST METHODS.
                - Do not invent methods that do not exist.
                - Do not classify helper methods, lifecycle methods, nested classes, or any method not listed.
                - Be conservative.
                - If uncertain, classify the method as securityRelevant=false.
                - Ignore pure functional / performance / UX tests unless they explicitly validate a security property.

                CONTROLLED TAXONOMY
                %s

                TARGET TEST METHODS
                The following methods were extracted deterministically by the parser and are the ONLY methods
                you are allowed to classify. Use the full class source only as context for understanding them.

                %s

                OUTPUT RULES
                - Return JSON only.
                - No markdown.
                - No prose outside JSON.
                - Return exactly one result for each target method.
                - methodName values in the output must exactly match one of:
                  [%s]
                - Do not omit any listed method.
                - Do not include any additional methods.
                - Tags must come only from this closed set:
                  security, auth, access-control, crypto, input-validation, injection, data-protection, logging, error-handling, owasp
                - If securityRelevant=true, tags MUST include "security".
                - Add 1-3 tags total per method.
                - If securityRelevant=false, displayName must be null.
                - If securityRelevant=false, tags must be [].
                - If securityRelevant=true, displayName must match:
                  SECURITY: <control/property> - <scenario>
                - reason should be short and specific.
                - interactionScore must be a decimal between 0.0 and 1.0 (one decimal place is sufficient).
                  It measures what fraction of this test's assertions only verify *interactions* (that
                  methods were called, in what order, with what arguments) rather than *outcomes* (return
                  values, computed state, thrown exceptions, or observable side effects).
                  Use these anchor points:
                    1.0 — EVERY assertion is an interaction check (e.g. verify() only); NO assertion
                          verifies any return value, output field, database row, or observable outcome.
                    0.0 — ALL assertions verify actual outputs or state; no interaction-only checks.
                    0.5 — mixed: some real-output assertions alongside interaction checks.
                  Score 1.0 only when there is NO assertion on any return value, state change, or
                  observable outcome. A test that has even one meaningful output assertion scores ≤ 0.5.
                  This applies regardless of testing framework (Mockito, EasyMock, WireMock, etc.).%s

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
                      "tags": ["security", "crypto"],%s
                      "reason": "string",
                      "interactionScore": 0.0
                    }
                  ]
                }

                CLASS
                FQCN: %s

                SOURCE
                %s
                """
                .formatted(taxonomyText, targetMethodBlock, expectedMethodNames,
                        confidenceOutputRules, confidenceJsonField, fqcn, classSource);
    }

    private static String formatTargetMethod(TargetMethod targetMethod) {
        StringBuilder builder = new StringBuilder("- ").append(targetMethod.methodName());

        if (targetMethod.beginLine() != null || targetMethod.endLine() != null) {
            builder.append(" [lines ").append(targetMethod.beginLine() == null ? "?" : targetMethod.beginLine())
                    .append('-').append(targetMethod.endLine() == null ? "?" : targetMethod.endLine()).append(']');
        }

        return builder.toString();
    }
}