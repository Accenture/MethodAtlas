package org.egothor.methodatlas.emit;

import java.io.PrintWriter;
import java.util.List;

import org.egothor.methodatlas.OutputMode;
import org.egothor.methodatlas.TagAiDrift;
import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Formats and emits test method records to a configured output writer.
 *
 * <p>
 * This class centralizes all output rendering logic for the MethodAtlas
 * application. It supports both CSV and plain-text output modes and handles
 * optional AI enrichment columns.
 * </p>
 *
 * <p>
 * The {@link PrintWriter} supplied at construction time is the sole output
 * destination, which allows the caller to redirect output for testing or
 * piping without manipulating {@code System.out}.
 * </p>
 *
 * <p>
 * Instances of this class are immutable after construction.
 * </p>
 *
 * @see OutputMode
 */
public final class OutputEmitter {

    private static final String PLAIN_ABSENT = "-";
    private static final String CSV_ABSENT = "";

    private final PrintWriter out;
    private final boolean aiEnabled;
    private final boolean confidenceEnabled;
    private final boolean contentHashEnabled;
    private final boolean driftDetect;
    private final boolean emitSourceRoot;

    /**
     * Creates a new output emitter bound to the supplied writer.
     *
     * @param out                writer to which all records are emitted
     * @param aiEnabled          whether AI enrichment columns should be included
     * @param confidenceEnabled  whether the {@code ai_confidence} column should be
     *                           included; only meaningful when {@code aiEnabled} is
     *                           {@code true}
     * @param contentHashEnabled whether the {@code content_hash} column should be
     *                           included
     * @param driftDetect        whether the {@code tag_ai_drift} column should be
     *                           included; only meaningful when {@code aiEnabled} is
     *                           {@code true}
     * @param emitSourceRoot     whether a {@code source_root} column (CSV) or
     *                           {@code SRCROOT=} token (plain) should be included;
     *                           enable with {@code -emit-source-root} when scanning
     *                           a multi-root project where the same FQCN can appear
     *                           under different source trees
     */
    public OutputEmitter(PrintWriter out, boolean aiEnabled, boolean confidenceEnabled,
            boolean contentHashEnabled, boolean driftDetect, boolean emitSourceRoot) {
        this.out = out;
        this.aiEnabled = aiEnabled;
        this.confidenceEnabled = confidenceEnabled;
        this.contentHashEnabled = contentHashEnabled;
        this.driftDetect = driftDetect;
        this.emitSourceRoot = emitSourceRoot;
    }

    /**
     * Emits {@code # key: value} metadata comment lines before the CSV header.
     *
     * @param version       tool version string, e.g. {@code 1.2.3} or {@code dev}
     * @param scanTimestamp ISO-8601 timestamp of the scan start
     * @param taxonomyInfo  human-readable taxonomy descriptor
     */
    public void emitMetadata(String version, String scanTimestamp, String taxonomyInfo) {
        out.println("# tool_version: " + version);
        out.println("# scan_timestamp: " + scanTimestamp);
        out.println("# taxonomy: " + taxonomyInfo);
    }

    /**
     * Emits the CSV header line when {@link OutputMode#CSV} is selected.
     *
     * <p>
     * Does nothing when plain-text mode is active.
     * </p>
     *
     * @param mode selected output mode
     */
    public void emitCsvHeader(OutputMode mode) {
        if (mode != OutputMode.CSV) {
            return;
        }
        StringBuilder header = new StringBuilder(256).append("fqcn,method,loc,tags,display_name");
        if (emitSourceRoot) {
            header.append(",source_root");
        }
        if (contentHashEnabled) {
            header.append(",content_hash");
        }
        if (aiEnabled) {
            header.append(",ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score");
            if (confidenceEnabled) {
                header.append(",ai_confidence");
            }
            if (driftDetect) {
                header.append(",tag_ai_drift");
            }
        }
        out.println(header.toString());
    }

    /**
     * Emits a single test method record in the configured output mode.
     *
     * @param mode        selected output mode
     * @param fqcn        fully qualified class name containing the method
     * @param method      test method name
     * @param loc         inclusive line count of the method declaration
     * @param contentHash SHA-256 fingerprint of the enclosing class source, or
     *                    {@code null} when {@code -content-hash} is not enabled
     * @param tags        source-level tags extracted from the method
     * @param displayName text from an existing display-name annotation on the
     *                    method, or an empty string if absent; {@code null}
     *                    is treated as absent
     * @param suggestion  AI suggestion for the method, or {@code null} if none
     * @param sourceRoot  CWD-relative path of the scan root, or {@code null}
     *                    when {@code -emit-source-root} is not enabled
     */
    public void emit(OutputMode mode, String fqcn, String method, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion, String sourceRoot) {
        if (mode == OutputMode.PLAIN) {
            emitPlain(fqcn, method, loc, contentHash, tags, displayName, suggestion, sourceRoot);
        } else {
            emitCsv(fqcn, method, loc, contentHash, tags, displayName, suggestion, sourceRoot);
        }
    }

    private void emitPlain(String fqcn, String method, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion, String sourceRoot) {
        String existingTags = tags.isEmpty() ? PLAIN_ABSENT : String.join(";", tags);
        StringBuilder line = new StringBuilder(fqcn)
                .append(", ").append(method)
                .append(", LOC=").append(loc)
                .append(", TAGS=").append(existingTags)
                .append(", DISPLAY=").append(displayName == null || displayName.isEmpty() ? PLAIN_ABSENT : displayName);

        if (emitSourceRoot) {
            line.append(", SRCROOT=").append(sourceRoot != null && !sourceRoot.isEmpty() ? sourceRoot : PLAIN_ABSENT);
        }

        if (contentHashEnabled) {
            line.append(", HASH=").append(contentHash != null ? contentHash : PLAIN_ABSENT);
        }

        if (aiEnabled) {
            TagAiDrift drift = driftDetect ? TagAiDrift.compute(tags, suggestion) : null;
            appendAiPlainFields(line, suggestion, drift);
        }

        out.println(line.toString());
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private void appendAiPlainFields(StringBuilder line, AiMethodSuggestion suggestion, TagAiDrift drift) {
        String aiSecurity = suggestion == null ? PLAIN_ABSENT : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null
                ? PLAIN_ABSENT : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null || suggestion.tags().isEmpty()
                ? PLAIN_ABSENT : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null || suggestion.reason().isBlank()
                ? PLAIN_ABSENT : suggestion.reason();

        String aiInteractionScore = suggestion == null ? PLAIN_ABSENT
                : String.format("%.1f", suggestion.interactionScore());

        line.append(", AI_SECURITY=").append(aiSecurity)
                .append(", AI_DISPLAY=").append(aiDisplayName)
                .append(", AI_TAGS=").append(aiTags)
                .append(", AI_REASON=").append(aiReason)
                .append(", AI_INTERACTION_SCORE=").append(aiInteractionScore);

        if (confidenceEnabled) {
            String aiConfidence = suggestion == null ? PLAIN_ABSENT
                    : String.format("%.1f", suggestion.confidence());
            line.append(", AI_CONFIDENCE=").append(aiConfidence);
        }
        if (driftDetect) {
            line.append(", TAG_AI_DRIFT=").append(drift != null ? drift.toValue() : PLAIN_ABSENT);
        }
    }

    private void emitCsv(String fqcn, String method, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion, String sourceRoot) {
        String existingTags = tags.isEmpty() ? CSV_ABSENT : String.join(";", tags);
        StringBuilder line = new StringBuilder(csvEscape(fqcn))
                .append(',').append(csvEscape(method))
                .append(',').append(loc)
                .append(',').append(csvEscape(existingTags))
                .append(',').append(csvEscape(displayName != null ? displayName : CSV_ABSENT));

        if (emitSourceRoot) {
            line.append(',').append(csvEscape(sourceRoot != null ? sourceRoot : CSV_ABSENT));
        }

        if (contentHashEnabled) {
            line.append(',').append(contentHash != null ? contentHash : CSV_ABSENT);
        }

        if (aiEnabled) {
            TagAiDrift drift = driftDetect ? TagAiDrift.compute(tags, suggestion) : null;
            appendAiCsvFields(line, suggestion, drift);
        }

        out.println(line.toString());
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private void appendAiCsvFields(StringBuilder line, AiMethodSuggestion suggestion, TagAiDrift drift) {
        String aiSecurity = suggestion == null ? CSV_ABSENT : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null
                ? CSV_ABSENT : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null
                ? CSV_ABSENT : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null
                ? CSV_ABSENT : suggestion.reason();

        String aiInteractionScore = suggestion == null ? CSV_ABSENT
                : String.format("%.1f", suggestion.interactionScore());

        line.append(',').append(csvEscape(aiSecurity))
                .append(',').append(csvEscape(aiDisplayName))
                .append(',').append(csvEscape(aiTags))
                .append(',').append(csvEscape(aiReason))
                .append(',').append(csvEscape(aiInteractionScore));

        if (confidenceEnabled) {
            String aiConfidence = suggestion == null ? CSV_ABSENT
                    : String.format("%.1f", suggestion.confidence());
            line.append(',').append(csvEscape(aiConfidence));
        }
        if (driftDetect) {
            line.append(',').append(drift != null ? drift.toValue() : CSV_ABSENT);
        }
    }

    /**
     * Escapes a value for inclusion in a CSV field.
     *
     * <p>
     * If the value contains a comma, double quote, carriage return, or line
     * feed, it is wrapped in double quotes and embedded quotes are doubled. Values
     * that start with {@code =}, {@code +}, {@code -}, or {@code @} are also
     * quoted to prevent spreadsheet formula injection. A {@code null} input is
     * converted to an empty field.
     * </p>
     *
     * @param value value to escape; may be {@code null}
     * @return CSV-safe representation of {@code value}
     */
    public static String csvEscape(String value) {
        if (value == null) {
            return CSV_ABSENT;
        }

        boolean formulaPrefix = !value.isEmpty()
                && "=+-@".indexOf(value.charAt(0)) >= 0;

        boolean mustQuote = formulaPrefix || value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;

        if (!mustQuote) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
