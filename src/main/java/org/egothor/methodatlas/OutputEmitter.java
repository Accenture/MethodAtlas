package org.egothor.methodatlas;

import java.io.PrintWriter;
import java.util.List;

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
 * @see MethodAtlasApp
 */
final class OutputEmitter {

    private static final String PLAIN_ABSENT = "-";
    private static final String CSV_ABSENT = "";

    private final PrintWriter out;
    private final boolean aiEnabled;

    /**
     * Creates a new output emitter bound to the supplied writer.
     *
     * @param out       writer to which all records are emitted
     * @param aiEnabled whether AI enrichment columns should be included
     */
    /* default */ OutputEmitter(PrintWriter out, boolean aiEnabled) {
        this.out = out;
        this.aiEnabled = aiEnabled;
    }

    /**
     * Emits {@code # key: value} metadata comment lines before the CSV header.
     *
     * <p>
     * The lines are prefixed with {@code #} so standard CSV parsers treat them as
     * comments and skip them. The metadata describes the conditions under which the
     * scan was performed so that historical output files remain interpretable.
     * </p>
     *
     * <p>
     * Three lines are emitted: {@code tool_version}, {@code scan_timestamp}, and
     * {@code taxonomy}.
     * </p>
     *
     * @param version      tool version string, e.g. {@code 1.2.3} or {@code dev}
     * @param scanTimestamp ISO-8601 timestamp of the scan start
     * @param taxonomyInfo human-readable taxonomy descriptor
     */
    /* default */ void emitMetadata(String version, String scanTimestamp, String taxonomyInfo) {
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
    /* default */ void emitCsvHeader(OutputMode mode) {
        if (mode != OutputMode.CSV) {
            return;
        }
        if (aiEnabled) {
            out.println("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason");
        } else {
            out.println("fqcn,method,loc,tags");
        }
    }

    /**
     * Emits a single test method record in the configured output mode.
     *
     * @param mode       selected output mode
     * @param fqcn       fully qualified class name containing the method
     * @param method     test method name
     * @param loc        inclusive line count of the method declaration
     * @param tags       source-level JUnit tags extracted from the method
     * @param suggestion AI suggestion for the method, or {@code null} if none
     *                   is available
     */
    /* default */ void emit(OutputMode mode, String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        if (mode == OutputMode.PLAIN) {
            emitPlain(fqcn, method, loc, tags, suggestion);
        } else {
            emitCsv(fqcn, method, loc, tags, suggestion);
        }
    }

    /**
     * Emits a record in plain text format.
     *
     * @param fqcn       fully qualified class name
     * @param method     test method name
     * @param loc        inclusive line count
     * @param tags       source-level JUnit tags
     * @param suggestion AI suggestion, or {@code null}
     */
    private void emitPlain(String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        String existingTags = tags.isEmpty() ? PLAIN_ABSENT : String.join(";", tags);
        StringBuilder line = new StringBuilder(fqcn)
                .append(", ").append(method)
                .append(", LOC=").append(loc)
                .append(", TAGS=").append(existingTags);

        if (aiEnabled) {
            appendAiPlainFields(line, suggestion);
        }

        out.println(line.toString());
    }

    /**
     * Appends AI-related fields to a plain-text line builder.
     *
     * @param line       string builder receiving the AI field tokens
     * @param suggestion AI suggestion, or {@code null}
     */
    private static void appendAiPlainFields(StringBuilder line, AiMethodSuggestion suggestion) {
        String aiSecurity = suggestion == null ? PLAIN_ABSENT : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null
                ? PLAIN_ABSENT : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null || suggestion.tags().isEmpty()
                ? PLAIN_ABSENT : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null || suggestion.reason().isBlank()
                ? PLAIN_ABSENT : suggestion.reason();

        line.append(", AI_SECURITY=").append(aiSecurity)
                .append(", AI_DISPLAY=").append(aiDisplayName)
                .append(", AI_TAGS=").append(aiTags)
                .append(", AI_REASON=").append(aiReason);
    }

    /**
     * Emits a record in CSV format.
     *
     * @param fqcn       fully qualified class name
     * @param method     test method name
     * @param loc        inclusive line count
     * @param tags       source-level JUnit tags
     * @param suggestion AI suggestion, or {@code null}
     */
    private void emitCsv(String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        String existingTags = tags.isEmpty() ? CSV_ABSENT : String.join(";", tags);
        StringBuilder line = new StringBuilder(csvEscape(fqcn))
                .append(',').append(csvEscape(method))
                .append(',').append(loc)
                .append(',').append(csvEscape(existingTags));

        if (aiEnabled) {
            appendAiCsvFields(line, suggestion);
        }

        out.println(line.toString());
    }

    /**
     * Appends AI-related CSV fields to a line builder.
     *
     * @param line       string builder receiving the AI columns
     * @param suggestion AI suggestion, or {@code null}
     */
    private static void appendAiCsvFields(StringBuilder line, AiMethodSuggestion suggestion) {
        String aiSecurity = suggestion == null ? CSV_ABSENT : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null
                ? CSV_ABSENT : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null
                ? CSV_ABSENT : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null
                ? CSV_ABSENT : suggestion.reason();

        line.append(',').append(csvEscape(aiSecurity))
                .append(',').append(csvEscape(aiDisplayName))
                .append(',').append(csvEscape(aiTags))
                .append(',').append(csvEscape(aiReason));
    }

    /**
     * Escapes a value for inclusion in a CSV field.
     *
     * <p>
     * If the value contains a comma, double quote, carriage return, or line
     * feed, it is wrapped in double quotes and embedded quotes are doubled. A
     * {@code null} input is converted to an empty field.
     * </p>
     *
     * @param value value to escape; may be {@code null}
     * @return CSV-safe representation of {@code value}
     */
    /* default */ static String csvEscape(String value) {
        if (value == null) {
            return CSV_ABSENT;
        }

        boolean mustQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!mustQuote) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
