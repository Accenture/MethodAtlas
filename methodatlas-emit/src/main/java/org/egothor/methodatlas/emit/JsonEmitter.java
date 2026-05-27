package org.egothor.methodatlas.emit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.emit.TagAiDrift;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.egothor.methodatlas.ai.AiMethodSuggestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Buffers test method records and serializes them as a flat JSON array when
 * {@link #flush(PrintWriter)} is called.
 *
 * <p>
 * Each element of the array is a JSON object whose fields mirror the CSV
 * columns produced by {@link OutputEmitter}, with the following differences:
 * </p>
 * <ul>
 * <li>{@code tags} and {@code ai_tags} are JSON arrays rather than
 *     semicolon-separated strings</li>
 * <li>Numeric fields ({@code loc}, {@code ai_interaction_score},
 *     {@code ai_confidence}) are JSON numbers</li>
 * <li>{@code ai_security_relevant} is a JSON boolean</li>
 * <li>Optional columns ({@code source_root}, {@code content_hash},
 *     {@code ai_*}, {@code tag_ai_drift}) are omitted from each object
 *     when the corresponding flag is not enabled</li>
 * </ul>
 *
 * <p>
 * All records are accumulated in memory via {@link #record} and the complete
 * JSON array is serialized to the writer in a single {@link #flush} call.
 * </p>
 *
 * <p>
 * This class implements {@link TestMethodSink} so it can be passed directly to
 * the orchestration layer. The {@link TestMethodSink#record} implementation
 * calls {@link #record(String, String, int, int, String, List, String, AiMethodSuggestion, String)}
 * with a {@code null} source root; callers that know the scan root (such as
 * {@link org.egothor.methodatlas.command.JsonCommand}) should call the
 * extended method directly.
 * </p>
 *
 * @see OutputEmitter
 * @see TestMethodSink
 */
public final class JsonEmitter implements TestMethodSink {

    private final boolean aiEnabled;
    private final boolean confidenceEnabled;
    private final boolean contentHashEnabled;
    private final boolean driftDetect;
    private final boolean emitSourceRoot;
    private final List<MethodRecord> records = new ArrayList<>();

    /**
     * Creates a new JSON emitter.
     *
     * @param aiEnabled          whether AI enrichment columns should be included
     * @param confidenceEnabled  whether the {@code ai_confidence} field should be
     *                           included; only meaningful when {@code aiEnabled} is
     *                           {@code true}
     * @param contentHashEnabled whether the {@code content_hash} field should be
     *                           included
     * @param driftDetect        whether the {@code tag_ai_drift} field should be
     *                           included; only meaningful when {@code aiEnabled} is
     *                           {@code true}
     * @param emitSourceRoot     whether the {@code source_root} field should be
     *                           included
     */
    public JsonEmitter(boolean aiEnabled, boolean confidenceEnabled,
            boolean contentHashEnabled, boolean driftDetect, boolean emitSourceRoot) {
        this.aiEnabled = aiEnabled;
        this.confidenceEnabled = confidenceEnabled;
        this.contentHashEnabled = contentHashEnabled;
        this.driftDetect = driftDetect;
        this.emitSourceRoot = emitSourceRoot;
    }

    /**
     * Buffers a single test method record (without a source root).
     *
     * <p>
     * Delegates to
     * {@link #record(String, String, int, int, String, List, String, AiMethodSuggestion, String)}
     * with {@code sourceRoot=null}. Callers that know the scan root should use
     * the extended form directly.
     * </p>
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion, null);
    }

    /**
     * Buffers a single test method record including the scan root.
     *
     * @param fqcn        fully qualified class name
     * @param method      test method name
     * @param beginLine   first line of the method declaration (1-based)
     * @param loc         inclusive line count of the method declaration
     * @param contentHash SHA-256 fingerprint of the enclosing class source, or
     *                    {@code null} when {@code -content-hash} is not enabled
     * @param tags        source-level tags extracted from the method
     * @param displayName text from an existing display-name annotation, or an
     *                    empty string/null if absent
     * @param suggestion  AI suggestion for the method, or {@code null} if none
     * @param sourceRoot  CWD-relative path of the scan root that produced this
     *                    record, or {@code null} when {@code -emit-source-root} is
     *                    not enabled
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion, String sourceRoot) {

        String resolvedSourceRoot = emitSourceRoot ? sourceRoot : null;
        String resolvedContentHash = contentHashEnabled ? contentHash : null;

        Boolean aiSecurityRelevant = null;
        String aiDisplayName = null;
        List<String> aiTags = null;
        String aiReason = null;
        Double aiInteractionScore = null;
        Double aiConfidence = null;
        String tagAiDrift = null;

        if (aiEnabled) {
            if (suggestion != null) {
                aiSecurityRelevant = suggestion.securityRelevant();
                aiDisplayName = suggestion.displayName();
                aiTags = suggestion.tags() != null ? List.copyOf(suggestion.tags()) : List.of();
                aiReason = suggestion.reason();
                aiInteractionScore = suggestion.interactionScore();
                aiConfidence = confidenceEnabled ? suggestion.confidence() : null;
                tagAiDrift = driftDetect
                        ? TagAiDrift.compute(tags != null ? tags : List.of(), suggestion).toValue()
                        : null;
            } else {
                aiTags = List.of();
            }
        }

        records.add(new MethodRecord(
                fqcn,
                method,
                loc,
                tags == null ? List.of() : List.copyOf(tags),
                displayName != null ? displayName : "",
                resolvedSourceRoot,
                resolvedContentHash,
                aiSecurityRelevant,
                aiDisplayName,
                aiTags,
                aiReason,
                aiInteractionScore,
                aiConfidence,
                tagAiDrift));
    }

    /**
     * Serializes all buffered records as a JSON array and writes the result to
     * {@code out}.
     *
     * @param out writer that receives the JSON output
     */
    public void flush(PrintWriter out) {
        JsonMapper mapper = JsonMapper.builder()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .build();
        try {
            out.println(mapper.writeValueAsString(records));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON output", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal record POJO
    // -------------------------------------------------------------------------

    /**
     * Immutable value object representing one serialized test method.
     * Fields annotated with {@link JsonInclude}{@code (NON_NULL)} are omitted
     * from the JSON output when their value is {@code null}.
     */
    @JsonInclude(Include.NON_NULL)
    private record MethodRecord(
            @JsonProperty("fqcn") String fqcn,
            @JsonProperty("method") String method,
            @JsonProperty("loc") int loc,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("source_root") String sourceRoot,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("ai_security_relevant") Boolean aiSecurityRelevant,
            @JsonProperty("ai_display_name") String aiDisplayName,
            @JsonProperty("ai_tags") List<String> aiTags,
            @JsonProperty("ai_reason") String aiReason,
            @JsonProperty("ai_interaction_score") Double aiInteractionScore,
            @JsonProperty("ai_confidence") Double aiConfidence,
            @JsonProperty("tag_ai_drift") String tagAiDrift) {
    }
}
