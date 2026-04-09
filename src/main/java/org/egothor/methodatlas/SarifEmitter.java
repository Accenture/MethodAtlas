package org.egothor.methodatlas;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Buffers test method records and serializes them as a single SARIF 2.1.0 JSON
 * document when {@link #flush(PrintWriter)} is called.
 *
 * <p>
 * SARIF (Static Analysis Results Interchange Format) is an OASIS standard for
 * representing the results of static analysis tools. MethodAtlas uses it to
 * emit a machine-readable inventory of discovered test methods, with
 * security-relevant methods distinguished from ordinary test methods via the
 * SARIF result level ({@code note} vs {@code none}).
 * </p>
 *
 * <p>
 * Each test method becomes one SARIF result. Security-relevant methods receive
 * level {@code note} and a rule derived from the first non-umbrella AI tag
 * (e.g. {@code security/auth}). All other methods receive level {@code none}
 * and rule {@code test-method}.
 * </p>
 *
 * <p>
 * AI enrichment fields (display name, tags, reason, confidence) are stored in
 * the SARIF result {@code properties} bag when an {@link AiMethodSuggestion}
 * is available.
 * </p>
 *
 * <p>
 * This class implements {@link TestMethodSink} so it can be passed directly to
 * the scan loop in {@link MethodAtlasApp}.
 * </p>
 *
 * @see OutputMode#SARIF
 * @see TestMethodSink
 */
final class SarifEmitter implements TestMethodSink {

    private static final String SARIF_SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
    private static final String SARIF_VERSION = "2.1.0";
    private static final String RULE_TEST_METHOD = "test-method";
    private static final String RULE_SECURITY_TEST = "security-test";
    private static final String LEVEL_NOTE = "note";
    private static final String LEVEL_NONE = "none";
    private static final String URI_BASE_ID = "%SRCROOT%";

    private final boolean aiEnabled;
    private final boolean confidenceEnabled;
    private final String toolVersion;
    private final List<ResultRecord> records = new ArrayList<>();

    /**
     * Creates a new SARIF emitter.
     *
     * @param aiEnabled         whether AI enrichment columns should be included
     * @param confidenceEnabled whether the {@code aiConfidence} property should
     *                          be included; only meaningful when {@code aiEnabled}
     *                          is {@code true}
     */
    SarifEmitter(boolean aiEnabled, boolean confidenceEnabled) {
        this.aiEnabled = aiEnabled;
        this.confidenceEnabled = confidenceEnabled;
        String v = SarifEmitter.class.getPackage().getImplementationVersion();
        this.toolVersion = v != null ? v : "dev";
    }

    /**
     * Buffers a single test method record.
     *
     * <p>
     * The record is not written to output until {@link #flush(PrintWriter)} is
     * called.
     * </p>
     */
    @Override
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, AiMethodSuggestion suggestion) {
        records.add(new ResultRecord(fqcn, method, beginLine, loc, contentHash, tags, suggestion));
    }

    /**
     * Serializes all buffered records as a SARIF 2.1.0 JSON document and writes
     * it to the supplied writer.
     *
     * <p>
     * The document contains a single run. Rules are collected from the unique
     * rule IDs referenced by results. The output is pretty-printed.
     * </p>
     *
     * @param out destination writer
     * @throws IllegalStateException if JSON serialization fails
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    void flush(PrintWriter out) {
        Map<String, SarifRule> rulesById = new LinkedHashMap<>();
        List<SarifResult> results = new ArrayList<>();

        for (ResultRecord rec : records) {
            String ruleId = resolveRuleId(rec.suggestion());
            rulesById.computeIfAbsent(ruleId, SarifEmitter::buildRule);
            results.add(buildResult(rec, ruleId));
        }

        SarifDriver driver = new SarifDriver("MethodAtlas", toolVersion,
                new ArrayList<>(rulesById.values()));
        SarifTool tool = new SarifTool(driver);
        SarifRun run = new SarifRun(tool, results);
        SarifDocument doc = new SarifDocument(SARIF_VERSION, List.of(run));

        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();

        try {
            out.print(mapper.writeValueAsString(doc));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize SARIF output", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String resolveRuleId(AiMethodSuggestion suggestion) {
        if (suggestion == null || !suggestion.securityRelevant()) {
            return RULE_TEST_METHOD;
        }
        List<String> tags = suggestion.tags();
        if (tags == null || tags.isEmpty()) {
            return RULE_SECURITY_TEST;
        }
        for (String tag : tags) {
            if (!"security".equals(tag)) {
                return "security/" + tag;
            }
        }
        return RULE_SECURITY_TEST;
    }

    private static SarifRule buildRule(String ruleId) {
        String name = toRuleName(ruleId);
        String description = toRuleDescription(ruleId);
        return new SarifRule(ruleId, name, new SarifMessage(description));
    }

    private static String toRuleName(String ruleId) {
        StringBuilder sb = new StringBuilder();
        for (String part : ruleId.split("[/-]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    private static String toRuleDescription(String ruleId) {
        return switch (ruleId) {
            case RULE_TEST_METHOD -> "JUnit test method";
            case RULE_SECURITY_TEST -> "Security-relevant test method";
            default -> ruleId.startsWith("security/")
                    ? "Security test: " + ruleId.substring("security/".length())
                    : ruleId;
        };
    }

    private SarifResult buildResult(ResultRecord rec, String ruleId) {
        String level = RULE_TEST_METHOD.equals(ruleId) ? LEVEL_NONE : LEVEL_NOTE;
        String messageText = resolveMessageText(rec);

        String artifactUri = rec.fqcn().replace('.', '/') + ".java";
        SarifArtifactLocation artifactLocation = new SarifArtifactLocation(artifactUri, URI_BASE_ID);

        SarifRegion region = rec.beginLine() > 0 ? new SarifRegion(rec.beginLine()) : null;
        SarifPhysicalLocation physicalLocation = new SarifPhysicalLocation(artifactLocation, region);

        String logicalFqmn = rec.fqcn() + "." + rec.method();
        SarifLogicalLocation logicalLocation = new SarifLogicalLocation(logicalFqmn, "member");

        SarifLocation location = new SarifLocation(physicalLocation, List.of(logicalLocation));

        SarifProperties properties = buildProperties(rec);

        return new SarifResult(ruleId, level, new SarifMessage(messageText),
                List.of(location), properties);
    }

    private String resolveMessageText(ResultRecord rec) {
        AiMethodSuggestion s = rec.suggestion();
        if (aiEnabled && s != null && s.displayName() != null && !s.displayName().isBlank()) {
            return s.displayName();
        }
        return rec.fqcn() + "." + rec.method();
    }

    private SarifProperties buildProperties(ResultRecord rec) {
        AiMethodSuggestion s = rec.suggestion();
        String sourceTags = rec.tags().isEmpty() ? null : String.join(";", rec.tags());

        if (!aiEnabled || s == null) {
            return new SarifProperties(rec.loc(), rec.contentHash(), sourceTags,
                    null, null, null, null, null);
        }

        String aiTags = s.tags() == null || s.tags().isEmpty() ? null : String.join(";", s.tags());
        String aiDisplayName = s.displayName();
        String aiReason = s.reason() == null || s.reason().isBlank() ? null : s.reason();
        Double aiConfidence = confidenceEnabled ? s.confidence() : null;
        return new SarifProperties(rec.loc(), rec.contentHash(), sourceTags,
                s.securityRelevant(), aiDisplayName, aiTags, aiReason, aiConfidence);
    }

    // -------------------------------------------------------------------------
    // Internal buffer record
    // -------------------------------------------------------------------------

    private record ResultRecord(String fqcn, String method, int beginLine, int loc,
            String contentHash, List<String> tags, AiMethodSuggestion suggestion) {
    }

    // -------------------------------------------------------------------------
    // SARIF 2.1.0 POJO classes (serialized by Jackson via direct field access)
    //
    // @JsonAutoDetect(fieldVisibility = ANY) instructs Jackson to read private
    // fields directly instead of going through public getters.  The fields are
    // only accessed by Jackson via reflection at runtime, so PMD would otherwise
    // flag them as UnusedPrivateField; the suppression below is intentional.
    // -------------------------------------------------------------------------

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifDocument {
        @JsonProperty("$schema")
        private final String schema = SARIF_SCHEMA;
        private final String version;
        private final List<SarifRun> runs;

        SarifDocument(String version, List<SarifRun> runs) {
            this.version = version;
            this.runs = runs;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifRun {
        private final SarifTool tool;
        private final List<SarifResult> results;

        SarifRun(SarifTool tool, List<SarifResult> results) {
            this.tool = tool;
            this.results = results;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifTool {
        private final SarifDriver driver;

        SarifTool(SarifDriver driver) { this.driver = driver; }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifDriver {
        private final String name;
        private final String version;
        private final List<SarifRule> rules;

        SarifDriver(String name, String version, List<SarifRule> rules) {
            this.name = name;
            this.version = version;
            this.rules = rules;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifRule {
        private final String id;
        private final String name;
        private final SarifMessage shortDescription;

        SarifRule(String id, String name, SarifMessage shortDescription) {
            this.id = id;
            this.name = name;
            this.shortDescription = shortDescription;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifResult {
        private final String ruleId;
        private final String level;
        private final SarifMessage message;
        private final List<SarifLocation> locations;
        private final SarifProperties properties;

        SarifResult(String ruleId, String level, SarifMessage message,
                List<SarifLocation> locations, SarifProperties properties) {
            this.ruleId = ruleId;
            this.level = level;
            this.message = message;
            this.locations = locations;
            this.properties = properties;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifLocation {
        private final SarifPhysicalLocation physicalLocation;
        private final List<SarifLogicalLocation> logicalLocations;

        SarifLocation(SarifPhysicalLocation physicalLocation,
                List<SarifLogicalLocation> logicalLocations) {
            this.physicalLocation = physicalLocation;
            this.logicalLocations = logicalLocations;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifPhysicalLocation {
        private final SarifArtifactLocation artifactLocation;

        @JsonInclude(Include.NON_NULL)
        private final SarifRegion region;

        SarifPhysicalLocation(SarifArtifactLocation artifactLocation, SarifRegion region) {
            this.artifactLocation = artifactLocation;
            this.region = region;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifArtifactLocation {
        private final String uri;
        private final String uriBaseId;

        SarifArtifactLocation(String uri, String uriBaseId) {
            this.uri = uri;
            this.uriBaseId = uriBaseId;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifRegion {
        private final int startLine;

        SarifRegion(int startLine) { this.startLine = startLine; }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifLogicalLocation {
        private final String fullyQualifiedName;
        private final String kind;

        SarifLogicalLocation(String fullyQualifiedName, String kind) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.kind = kind;
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifMessage {
        private final String text;

        SarifMessage(String text) { this.text = text; }
    }

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonInclude(Include.NON_NULL)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class SarifProperties {
        private final int loc;
        private final String contentHash;
        private final String sourceTags;
        private final Boolean aiSecurityRelevant;
        private final String aiDisplayName;
        private final String aiTags;
        private final String aiReason;
        private final Double aiConfidence;

        SarifProperties(int loc, String contentHash, String sourceTags,
                Boolean aiSecurityRelevant, String aiDisplayName, String aiTags,
                String aiReason, Double aiConfidence) {
            this.loc = loc;
            this.contentHash = contentHash;
            this.sourceTags = sourceTags;
            this.aiSecurityRelevant = aiSecurityRelevant;
            this.aiDisplayName = aiDisplayName;
            this.aiTags = aiTags;
            this.aiReason = aiReason;
            this.aiConfidence = aiConfidence;
        }
    }
}
