package org.egothor.methodatlas.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that a prompt template is structurally sound before it is used.
 *
 * <p>
 * Validation is deliberately limited to what a tool can verify deterministically:
 * </p>
 * <ul>
 *   <li>the template is non-blank;</li>
 *   <li>every {@code {token}} it uses is permitted for its {@link PromptTemplateKind};</li>
 *   <li>every required token for that kind is present;</li>
 *   <li>the template still contains the JSON structural anchor the response parser
 *       relies on (for example {@code "methods"} or {@code "secrets"}).</li>
 * </ul>
 *
 * <p>
 * It does <strong>not</strong>, and cannot, verify that an LLM will understand or
 * follow the instructions — that is the template author's responsibility. The
 * {@code -check-prompts} CLI mode exposes this validator so authors get fast feedback.
 * </p>
 *
 * <p>
 * This class is a stateless, thread-safe utility holder.
 * </p>
 *
 * @since 4.1.0
 */
public final class PromptTemplateValidator {

    /**
     * Matches a placeholder of the form <code>{identifier}</code>. JSON braces such as
     * <code>{ "x": 1 }</code> or <code>{"x":1}</code> do not match because a letter must
     * immediately follow the opening brace.
     */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private PromptTemplateValidator() {
        // utility class
    }

    /**
     * Returns the placeholder token names used in a template body, in first-seen order.
     *
     * @param template the template body; must not be {@code null}
     * @return an ordered set of token names without braces; never {@code null}
     * @throws NullPointerException if {@code template} is {@code null}
     */
    public static Set<String> tokensIn(String template) {
        Objects.requireNonNull(template, "template");
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    /**
     * Validates a template and returns the list of problems found.
     *
     * @param kind     the template kind whose token rules apply; must not be {@code null}
     * @param template the template body to validate; must not be {@code null}
     * @return an immutable list of human-readable problems; empty when the template is
     *         valid; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static List<String> validate(PromptTemplateKind kind, String template) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(template, "template");

        List<String> problems = new ArrayList<>();
        if (template.isBlank()) {
            problems.add("template is empty");
            return List.copyOf(problems);
        }

        Set<String> used = tokensIn(template);
        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(kind.allowedTokens());
        for (String token : unknown) {
            problems.add("unknown placeholder {" + token + "}; allowed: "
                    + new TreeSet<>(kind.allowedTokens()));
        }

        Set<String> missing = new TreeSet<>(kind.requiredTokens());
        missing.removeAll(used);
        for (String token : missing) {
            problems.add("missing required placeholder {" + token + "}");
        }

        if (!template.contains(kind.structuralAnchor())) {
            problems.add("missing JSON structural anchor " + kind.structuralAnchor()
                    + "; the response parser depends on it");
        }
        return List.copyOf(problems);
    }

    /**
     * Validates a template and throws if it is invalid.
     *
     * @param kind     the template kind whose token rules apply; must not be {@code null}
     * @param template the template body to validate; must not be {@code null}
     * @param sourceLabel a short label identifying the template source (for example a
     *                    file name) used in the exception message; must not be {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws PromptTemplateException  if the template is invalid; the message lists
     *                                  every problem found
     */
    public static void validateOrThrow(PromptTemplateKind kind, String template, String sourceLabel) {
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        List<String> problems = validate(kind, template);
        if (!problems.isEmpty()) {
            throw new PromptTemplateException("Invalid " + kind + " prompt template (" + sourceLabel
                    + "): " + String.join("; ", problems));
        }
    }
}
