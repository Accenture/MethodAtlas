package org.egothor.methodatlas.emit;

import java.io.PrintWriter;
import java.util.List;

import org.egothor.methodatlas.TagAiDrift;
import org.egothor.methodatlas.api.TestMethodSink;
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
 * @see TestMethodSink
 */
public final class GitHubAnnotationsEmitter implements TestMethodSink {

    /** Interaction score at or above which a security test is flagged as a potential placebo. */
    public static final double PLACEBO_THRESHOLD = 0.8;

    private final PrintWriter out;
    private final String filePrefix;

    /**
     * @param out        writer that receives the annotation lines
     * @param filePrefix prefix prepended to the FQCN-derived file path,
     *                   including a trailing slash (e.g. {@code "src/test/java/"});
     *                   empty string when the scan root is already the repo root
     */
    public GitHubAnnotationsEmitter(PrintWriter out, String filePrefix) {
        this.out = out;
        this.filePrefix = filePrefix;
    }

    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        String filePath = filePrefix + fqcn.replace('.', '/') + ".java";

        if (displayName != null && displayName.isEmpty()) {
            out.println(formatCommand("notice", filePath, beginLine,
                    "@DisplayName(\"\") on " + fqcn + "#" + method,
                    "@DisplayName(\"\") declares an empty display name — "
                            + "the test will appear unnamed in reports, obscuring the audit trail. "
                            + "Replace with a meaningful description, "
                            + "e.g. @DisplayName(\"Verifies that ...\")."));
        }

        if (suggestion == null || !suggestion.securityRelevant()) {
            return;
        }

        boolean isPlacebo = suggestion.interactionScore() >= PLACEBO_THRESHOLD;
        String level = isPlacebo ? "warning" : "notice";

        String title = suggestion.displayName() != null && !suggestion.displayName().isBlank()
                ? suggestion.displayName()
                : fqcn + "#" + method;

        TagAiDrift drift = TagAiDrift.compute(tags, suggestion);
        String message = buildMessage(suggestion, isPlacebo, drift);

        out.println(formatCommand(level, filePath, beginLine, title, message));
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private static String buildMessage(AiMethodSuggestion suggestion, boolean isPlacebo, TagAiDrift drift) {
        StringBuilder sb = new StringBuilder(512);

        if (suggestion.displayName() != null && !suggestion.displayName().isBlank()) {
            sb.append("Suggested @DisplayName: \"").append(suggestion.displayName()).append('"');
        }
        if (!suggestion.tags().isEmpty()) {
            appendSep(sb);
            sb.append("Suggested @Tag: ").append(String.join(", ", suggestion.tags()));
        }
        if (suggestion.reason() != null && !suggestion.reason().isBlank()) {
            appendSep(sb);
            String reason = suggestion.reason().strip();
            sb.append("Reason: ").append(reason);
            if (!reason.endsWith(".")) {
                sb.append('.');
            }
        }
        if (isPlacebo) {
            appendSep(sb);
            sb.append("Interaction score ")
              .append(String.format("%.1f", suggestion.interactionScore()))
              .append(": assertions only verify method calls, not output values or state");
        }
        if (drift == TagAiDrift.TAG_ONLY) {
            appendSep(sb);
            sb.append("Drift: @Tag(\"security\") present but AI disagrees — annotation may be stale");
        } else if (drift == TagAiDrift.AI_ONLY) {
            appendSep(sb);
            sb.append("Drift: AI classifies as security-relevant but no @Tag(\"security\") in source");
        }
        if (sb.length() == 0) {
            sb.append("Security test");
        }
        return sb.toString();
    }

    private static void appendSep(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
    }

    /**
     * Formats a GitHub Actions workflow command line.
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public static String formatCommand(String level, String filePath, int beginLine,
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
     */
    public static String escapeParam(String value) {
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
    public static String escapeMessage(String value) {
        return value
                .replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }
}
