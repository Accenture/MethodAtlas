package org.egothor.methodatlas.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders the prompts supplied to AI providers for security classification of JUnit
 * test classes and for credential triage.
 *
 * <p>
 * A prompt is produced by substituting {@code {token}} placeholders in a
 * {@link PromptTemplateSet} with deterministically-derived data: the controlled
 * taxonomy, the exact list of target test methods, the class source, and any detected
 * credential candidates. The same template set is hashed for the reproducibility
 * receipt, so the prompt that is sent and the checksum that is recorded are guaranteed
 * to describe the same text.
 * </p>
 *
 * <p>
 * Each public {@code build}/{@code buildCredentialTriage} method has two forms: one
 * that uses the built-in templates ({@link PromptTemplateSet#defaults()}) and one that
 * accepts a caller-supplied {@link PromptTemplateSet} (used when the operator has
 * overridden a template). Substitution is single-pass, so a value that happens to
 * contain a {@code {token}}-shaped substring is never re-interpreted as a placeholder.
 * </p>
 *
 * <p>
 * This class is a stateless, thread-safe, non-instantiable utility holder.
 * </p>
 *
 * @see AiSuggestionEngine
 * @see AiProviderClient
 * @see PromptTemplateSet
 * @see PromptTemplateValidator
 * @see DefaultSecurityTaxonomy
 * @see OptimizedSecurityTaxonomy
 */
public final class PromptBuilder {

    /**
     * Matches a placeholder of the form <code>{identifier}</code>. JSON braces such as
     * <code>{ "x": 1 }</code> do not match because a letter must immediately follow the
     * opening brace.
     */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    /** Per-method confidence rubric appended when confidence scoring is requested. */
    private static final String CONFIDENCE_RULES =
            "\n- confidence must be a decimal between 0.0 and 1.0 (one decimal place is sufficient).\n"
                    + "  Use these anchor points when setting it:\n"
                    + "    1.0 — the method name and body explicitly and unambiguously test a named\n"
                    + "          security property (authentication, authorisation, encryption,\n"
                    + "          injection prevention, access control, etc.)\n"
                    + "    0.7 — the method clearly tests a security-adjacent concern but the mapping\n"
                    + "          requires inference from context, class name, or surrounding code\n"
                    + "    0.5 — the classification is plausible; the method name or body is equally\n"
                    + "          consistent with a non-security interpretation\n"
                    + "  Prefer securityRelevant=false rather than returning a confidence value below\n"
                    + "  0.5. When securityRelevant=false, set confidence to 0.0.";

    /** {@code confidence} JSON field inserted into the method object when requested. */
    private static final String CONFIDENCE_FIELD = "\n              \"confidence\": 0.9,";

    /**
     * Deterministically extracted test method descriptor supplied to the prompt.
     *
     * @param methodName name of the JUnit test method
     * @param beginLine  first source line of the method, or {@code null} if unknown
     * @param endLine    last source line of the method, or {@code null} if unknown
     */
    public record TargetMethod(String methodName, Integer beginLine, Integer endLine) {

        /**
         * Validates the method name is present and non-blank.
         *
         * @throws NullPointerException     if {@code methodName} is {@code null}
         * @throws IllegalArgumentException if {@code methodName} is blank
         */
        public TargetMethod {
            Objects.requireNonNull(methodName, "methodName");
            if (methodName.isBlank()) {
                throw new IllegalArgumentException("methodName must not be blank");
            }
        }
    }

    /**
     * A deterministically-detected credential candidate presented to the LLM for triage.
     *
     * @param index   zero-based stable index used to correlate the verdict back to this
     *                candidate
     * @param line    one-based source line of the candidate
     * @param snippet the matched text (the raw value is included so the model can judge
     *                genuine-versus-placeholder); never {@code null}
     * @since 4.1.0
     */
    public record CredentialCandidateRef(int index, int line, String snippet) {

        /**
         * Validates the snippet is present.
         *
         * @throws NullPointerException if {@code snippet} is {@code null}
         */
        public CredentialCandidateRef {
            Objects.requireNonNull(snippet, "snippet");
        }
    }

    private PromptBuilder() {
        // utility class
    }

    /**
     * Builds the classification prompt using the built-in templates.
     *
     * @param fqcn          fully qualified class name of the test class being analyzed
     * @param classSource   complete source code of the test class
     * @param taxonomyText  taxonomy definition guiding classification
     * @param targetMethods exact list of deterministically discovered JUnit test methods
     *                      to classify
     * @param confidence    when {@code true}, request a per-method confidence score
     * @return the rendered prompt; never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code targetMethods} is empty
     * @see AiSuggestionEngine#suggestForClass(String, String, String, List)
     */
    public static String build(String fqcn, String classSource, String taxonomyText,
            List<TargetMethod> targetMethods, boolean confidence) {
        return build(PromptTemplateSet.defaults(), fqcn, classSource, taxonomyText, targetMethods, confidence);
    }

    /**
     * Builds the classification prompt using a caller-supplied template set.
     *
     * @param templates     the prompt templates to render; must not be {@code null}
     * @param fqcn          fully qualified class name of the test class being analyzed
     * @param classSource   complete source code of the test class
     * @param taxonomyText  taxonomy definition guiding classification
     * @param targetMethods exact list of deterministically discovered JUnit test methods
     *                      to classify
     * @param confidence    when {@code true}, request a per-method confidence score
     * @return the rendered prompt; never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code targetMethods} is empty
     * @since 4.1.0
     */
    public static String build(PromptTemplateSet templates, String fqcn, String classSource,
            String taxonomyText, List<TargetMethod> targetMethods, boolean confidence) {
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(fqcn, "fqcn");
        Objects.requireNonNull(classSource, "classSource");
        Objects.requireNonNull(taxonomyText, "taxonomyText");
        Objects.requireNonNull(targetMethods, "targetMethods");
        if (targetMethods.isEmpty()) {
            throw new IllegalArgumentException("targetMethods must not be empty");
        }

        String methodBlock = targetMethods.stream().map(PromptBuilder::formatTargetMethod)
                .collect(Collectors.joining("\n"));
        String expectedMethodNames = targetMethods.stream().map(TargetMethod::methodName)
                .map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));

        Map<String, String> values = new HashMap<>();
        values.put("taxonomy", taxonomyText);
        values.put("methods", methodBlock);
        values.put("expectedMethodNames", expectedMethodNames);
        values.put("confidenceRules", confidence ? CONFIDENCE_RULES : "");
        values.put("confidenceField", confidence ? CONFIDENCE_FIELD : "");
        values.put("fqcn", fqcn);
        values.put("classSource", classSource);
        return render(templates.get(PromptTemplateKind.CLASSIFICATION), values);
    }

    /**
     * Builds a classification prompt that also requests credential triage, using the
     * built-in templates.
     *
     * @param fqcn             fully qualified class name
     * @param classSource      complete source of the class
     * @param taxonomyText     taxonomy definition guiding classification
     * @param targetMethods    deterministically discovered test methods to classify
     * @param confidence       whether to request a per-method confidence score
     * @param secretCandidates credential candidates to triage in the same call;
     *                         {@code null} or empty disables the appendix
     * @return the combined prompt; never {@code null}
     * @since 4.1.0
     */
    public static String build(String fqcn, String classSource, String taxonomyText,
            List<TargetMethod> targetMethods, boolean confidence,
            List<CredentialCandidateRef> secretCandidates) {
        return build(PromptTemplateSet.defaults(), fqcn, classSource, taxonomyText, targetMethods,
                confidence, secretCandidates);
    }

    /**
     * Builds a classification prompt that also requests credential triage, using a
     * caller-supplied template set. When {@code secretCandidates} is {@code null} or
     * empty the result is identical to the classification-only prompt.
     *
     * @param templates        the prompt templates to render; must not be {@code null}
     * @param fqcn             fully qualified class name
     * @param classSource      complete source of the class
     * @param taxonomyText     taxonomy definition guiding classification
     * @param targetMethods    deterministically discovered test methods to classify
     * @param confidence       whether to request a per-method confidence score
     * @param secretCandidates credential candidates to triage in the same call;
     *                         {@code null} or empty disables the appendix
     * @return the combined prompt; never {@code null}
     * @throws NullPointerException     if {@code templates} or a required text argument is {@code null}
     * @throws IllegalArgumentException if {@code targetMethods} is empty
     * @since 4.1.0
     */
    public static String build(PromptTemplateSet templates, String fqcn, String classSource,
            String taxonomyText, List<TargetMethod> targetMethods, boolean confidence,
            List<CredentialCandidateRef> secretCandidates) {
        String base = build(templates, fqcn, classSource, taxonomyText, targetMethods, confidence);
        if (secretCandidates == null || secretCandidates.isEmpty()) {
            return base;
        }
        return base + render(templates.get(PromptTemplateKind.TRIAGE_APPENDIX),
                Map.of("candidates", formatCandidates(secretCandidates)));
    }

    /**
     * Builds a standalone credential-triage prompt using the built-in templates.
     *
     * @param fqcn        fully qualified class name (or a file identifier) for context
     * @param classSource complete source of the class being triaged
     * @param candidates  detected candidates to score; must not be empty
     * @return the triage prompt; never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code candidates} is empty
     * @since 4.1.0
     */
    public static String buildCredentialTriage(String fqcn, String classSource,
            List<CredentialCandidateRef> candidates) {
        return buildCredentialTriage(PromptTemplateSet.defaults(), fqcn, classSource, candidates);
    }

    /**
     * Builds a standalone credential-triage prompt using a caller-supplied template set.
     *
     * @param templates   the prompt templates to render; must not be {@code null}
     * @param fqcn        fully qualified class name (or a file identifier) for context
     * @param classSource complete source of the class being triaged
     * @param candidates  detected candidates to score; must not be empty
     * @return the triage prompt; never {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code candidates} is empty
     * @since 4.1.0
     */
    public static String buildCredentialTriage(PromptTemplateSet templates, String fqcn,
            String classSource, List<CredentialCandidateRef> candidates) {
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(fqcn, "fqcn");
        Objects.requireNonNull(classSource, "classSource");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        Map<String, String> values = new HashMap<>();
        values.put("candidates", formatCandidates(candidates));
        values.put("fqcn", fqcn);
        values.put("classSource", classSource);
        return render(templates.get(PromptTemplateKind.DEDICATED_TRIAGE), values);
    }

    /**
     * Substitutes {@code {token}} placeholders in {@code template} with the supplied
     * values in a single pass.
     *
     * <p>
     * A token absent from {@code values} is left verbatim. Because substitution is
     * single-pass, a replacement value containing a {@code {token}}-shaped substring is
     * never re-interpreted, so injected class source cannot perturb the prompt.
     * </p>
     *
     * @param template the template body; must not be {@code null}
     * @param values   token name (without braces) to replacement value; must not be {@code null}
     * @return the rendered text; never {@code null}
     */
    /* default */ static String render(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String formatCandidates(List<CredentialCandidateRef> candidates) {
        return candidates.stream()
                .map(c -> "- candidateIndex " + c.index() + " (line " + c.line() + "): " + c.snippet())
                .collect(Collectors.joining("\n"));
    }

    private static String formatTargetMethod(TargetMethod targetMethod) {
        StringBuilder builder = new StringBuilder(64).append("- ").append(targetMethod.methodName());
        if (targetMethod.beginLine() != null || targetMethod.endLine() != null) {
            builder.append(" [lines ").append(targetMethod.beginLine() == null ? "?" : targetMethod.beginLine())
                    .append('-').append(targetMethod.endLine() == null ? "?" : targetMethod.endLine()).append(']');
        }
        return builder.toString();
    }
}
