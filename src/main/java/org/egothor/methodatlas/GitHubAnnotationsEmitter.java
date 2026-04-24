package org.egothor.methodatlas;

import java.io.PrintWriter;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Emits GitHub Actions workflow commands for inline PR annotations.
 *
 * <p>Only security-relevant methods produce output. Each method becomes one
 * {@code ::notice} or {@code ::warning} line that GitHub Actions intercepts
 * and renders as an inline annotation on the PR diff:</p>
 *
 * <ul>
 *   <li>{@code ::warning} — when {@code ai_interaction_score >= 0.8}: the test
 *       only verifies that methods were called, not what they returned.</li>
 *   <li>{@code ::notice} — otherwise: a well-formed security test worth
 *       reviewing.</li>
 * </ul>
 *
 * <p>The file path in each annotation is constructed as
 * {@code <filePrefix><fqcn-as-path>.java}, where {@code filePrefix} is
 * derived from the first configured scan root (e.g. {@code src/test/java/}).
 * This produces paths like {@code src/test/java/com/acme/AuthTest.java},
 * which GitHub resolves to the correct inline position in the PR diff for
 * standard Maven / Gradle source layouts.</p>
 *
 * <p>This mode does not require a GitHub Advanced Security licence, unlike
 * SARIF upload via the {@code upload-sarif} action.</p>
 *
 * @see OutputMode#GITHUB_ANNOTATIONS
 * @see TestMethodSink
 */
final class GitHubAnnotationsEmitter implements TestMethodSink {

    /** Interaction score at or above which a security test is flagged as a potential placebo. */
    /* default */ static final double PLACEBO_THRESHOLD = 0.8;

    private final PrintWriter out;
    private final String filePrefix;

    /**
     * @param out        writer that receives the annotation lines
     * @param filePrefix prefix prepended to the FQCN-derived file path,
     *                   including a trailing slash (e.g. {@code "src/test/java/"});
     *                   empty string when the scan root is already the repo root
     */
    /* default */ GitHubAnnotationsEmitter(PrintWriter out, String filePrefix) {
        this.out = out;
        this.filePrefix = filePrefix;
    }

    @Override
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, AiMethodSuggestion suggestion) {
        if (suggestion == null || !suggestion.securityRelevant()) {
            return;
        }

        String filePath = filePrefix + fqcn.replace('.', '/') + ".java";
        boolean isPlacebo = suggestion.interactionScore() >= PLACEBO_THRESHOLD;
        String level = isPlacebo ? "warning" : "notice";

        String title = suggestion.displayName() != null && !suggestion.displayName().isEmpty()
                ? suggestion.displayName()
                : fqcn + "#" + method;

        TagAiDrift drift = TagAiDrift.compute(tags, suggestion);
        String message = buildMessage(suggestion, isPlacebo, drift);

        out.println(formatCommand(level, filePath, beginLine, title, message));
    }

    private static String buildMessage(AiMethodSuggestion suggestion, boolean isPlacebo, TagAiDrift drift) {
        StringBuilder sb = new StringBuilder(256);
        if (!suggestion.tags().isEmpty()) {
            sb.append("Tags: ").append(String.join(";", suggestion.tags()));
        }
        if (isPlacebo) {
            if (sb.length() > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append("Interaction score ")
              .append(String.format("%.1f", suggestion.interactionScore()))
              .append(": assertions only verify method calls, not output values or state");
        }
        if (drift == TagAiDrift.TAG_ONLY) {
            if (sb.length() > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append("Drift: @Tag(\"security\") present but AI disagrees — annotation may be stale");
        } else if (drift == TagAiDrift.AI_ONLY) {
            if (sb.length() > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append("Drift: AI classifies as security-relevant but no @Tag(\"security\") in source");
        }
        if (sb.length() == 0) {
            sb.append("Security test");
        }
        return sb.toString();
    }

    /* default */ static String formatCommand(String level, String filePath, int beginLine,
            String title, String message) {
        StringBuilder cmd = new StringBuilder(128);
        cmd.append("::").append(level).append(" file=").append(escapeParam(filePath));
        if (beginLine > 0) {
            cmd.append(",line=").append(beginLine);
        }
        if (title != null && !title.isEmpty()) {
            cmd.append(",title=").append(escapeParam(title));
        }
        cmd.append("::").append(escapeMessage(message));
        return cmd.toString();
    }

    /**
     * Encodes characters that would break a GitHub workflow command parameter value.
     * GitHub command parameters are comma-delimited key=value pairs; colons and
     * commas inside values must be percent-encoded.
     */
    /* default */ static String escapeParam(String value) {
        return value
                .replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A")
                .replace(":", "%3A")
                .replace(",", "%2C");
    }

    /**
     * Encodes characters that would break a GitHub workflow command message.
     */
    /* default */ static String escapeMessage(String value) {
        return value
                .replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }
}
