package org.egothor.methodatlas;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

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
    private static final int SINGLE_CHAR_LENGTH = 1;
    private static final int RULE_ID_PART_COUNT = 2;
    private static final String RULE_TEST_METHOD = "test-method";
    private static final String RULE_SECURITY_TEST = "security-test";
    private static final String LEVEL_NOTE = "note";
    private static final String LEVEL_NONE = "none";
    private static final String URI_BASE_ID = "%SRCROOT%";

    private static final String SEVERITY_CRITICAL = "9.0";
    private static final String SEVERITY_DESERIALIZATION = "8.5";
    private static final String SEVERITY_HIGH = "7.5";
    private static final String SEVERITY_MEDIUM_HIGH = "6.5";
    private static final String SEVERITY_MEDIUM = "5.5";
    private static final String SEVERITY_LOW = "4.0";
    private static final String SEVERITY_DEFAULT = "5.0";

    /**
     * Maps AI taxonomy tags to SARIF {@code security-severity} scores (0–10).
     * GitHub Code Scanning maps ≥9 → Critical, ≥7 → High, ≥4 → Medium, >0 → Low.
     */
    private static final Map<String, String> TAG_SEVERITY = Map.ofEntries(
            Map.entry("injection", SEVERITY_CRITICAL),
            Map.entry("sqli", SEVERITY_CRITICAL),
            Map.entry("rce", SEVERITY_CRITICAL),
            Map.entry("xxe", SEVERITY_CRITICAL),
            Map.entry("deserialization", SEVERITY_DESERIALIZATION),
            Map.entry("auth", SEVERITY_HIGH),
            Map.entry("authn", SEVERITY_HIGH),
            Map.entry("authz", SEVERITY_HIGH),
            Map.entry("access-control", SEVERITY_HIGH),
            Map.entry("privilege-escalation", SEVERITY_HIGH),
            Map.entry("idor", SEVERITY_HIGH),
            Map.entry("crypto", SEVERITY_MEDIUM_HIGH),
            Map.entry("session", SEVERITY_MEDIUM_HIGH),
            Map.entry("xss", SEVERITY_MEDIUM_HIGH),
            Map.entry("csrf", SEVERITY_MEDIUM_HIGH),
            Map.entry("path-traversal", SEVERITY_MEDIUM_HIGH),
            Map.entry("redirect", SEVERITY_MEDIUM),
            Map.entry("logging", SEVERITY_LOW),
            Map.entry("dos", SEVERITY_LOW));

    /**
     * Pre-compiled pattern for splitting rule IDs into camel-case name segments.
     * Using {@link Pattern#split(CharSequence, int)} with limit {@code -1} is
     * more predictable than {@link String#split(String)} which silently discards
     * trailing empty strings.
     */
    private static final Pattern RULE_NAME_SEPARATOR = Pattern.compile("[/-]");

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
    /* default */ SarifEmitter(boolean aiEnabled, boolean confidenceEnabled) {
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
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
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
    /* default */ void flush(PrintWriter out) {
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
        SarifDocument doc = new SarifDocument(SARIF_SCHEMA, SARIF_VERSION, List.of(run));

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
        List<String> tags = toRuleTags(ruleId);
        SarifRuleProperties ruleProps = tags.isEmpty() ? null : new SarifRuleProperties(tags);
        return new SarifRule(ruleId, name, new SarifMessage(description), ruleProps);
    }

    private static List<String> toRuleTags(String ruleId) {
        if (RULE_TEST_METHOD.equals(ruleId)) {
            return List.of("test");
        }
        if (RULE_SECURITY_TEST.equals(ruleId)) {
            return List.of("security");
        }
        // "security/auth" → ["security", "auth"]
        String[] parts = ruleId.split("/", RULE_ID_PART_COUNT);
        if (parts.length == RULE_ID_PART_COUNT) {
            return List.of(parts[0], parts[1]);
        }
        return List.of("security");
    }

    private static String toRuleName(String ruleId) {
        StringBuilder sb = new StringBuilder();
        for (String part : RULE_NAME_SEPARATOR.split(ruleId, -1)) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > SINGLE_CHAR_LENGTH) {
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

        SarifProperties properties = buildProperties(rec, ruleId);

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

    private SarifProperties buildProperties(ResultRecord rec, String ruleId) {
        AiMethodSuggestion s = rec.suggestion();
        String sourceTags = rec.tags().isEmpty() ? null : String.join(";", rec.tags());
        String securitySeverity = resolveSecuritySeverity(ruleId, s);

        if (!aiEnabled || s == null) {
            return new SarifProperties(rec.loc(), rec.contentHash(), sourceTags,
                    null, null, null, null, null, null, null, securitySeverity);
        }

        String aiTags = s.tags() == null || s.tags().isEmpty() ? null : String.join(";", s.tags());
        String aiDisplayName = s.displayName();
        String aiReason = s.reason() == null || s.reason().isBlank() ? null : s.reason();
        Double aiConfidence = confidenceEnabled ? s.confidence() : null;
        TagAiDrift drift = TagAiDrift.compute(rec.tags(), s);
        String tagAiDrift = drift != null ? drift.toValue() : null;
        return new SarifProperties(rec.loc(), rec.contentHash(), sourceTags,
                s.securityRelevant(), aiDisplayName, aiTags, aiReason, s.interactionScore(), aiConfidence,
                tagAiDrift, securitySeverity);
    }

    private static String resolveSecuritySeverity(String ruleId, AiMethodSuggestion suggestion) {
        if (RULE_TEST_METHOD.equals(ruleId)) {
            return null;
        }
        if (suggestion != null && suggestion.tags() != null) {
            for (String tag : suggestion.tags()) {
                String severity = TAG_SEVERITY.get(tag);
                if (severity != null) {
                    return severity;
                }
            }
        }
        return SEVERITY_DEFAULT;
    }

    // -------------------------------------------------------------------------
    // Internal buffer record
    // -------------------------------------------------------------------------

    private record ResultRecord(String fqcn, String method, int beginLine, int loc,
            String contentHash, List<String> tags, AiMethodSuggestion suggestion) {
    }

    // -------------------------------------------------------------------------
    // SARIF 2.1.0 POJO records (serialized by Jackson via accessor methods).
    //
    // Java records expose each component through a public accessor method whose
    // name matches the component name (no "get" prefix).  Jackson 2.12+
    // recognises records natively and serialises them via those accessors,
    // so no @JsonAutoDetect(fieldVisibility = ANY) is required.
    // -------------------------------------------------------------------------

    /** SARIF 2.1.0 top-level document containing a version, schema URL, and runs. */
    private record SarifDocument(
            @JsonProperty("$schema") String schema,
            String version,
            List<SarifRun> runs) {
    }

    /** SARIF run containing a tool description and the list of results. */
    private record SarifRun(SarifTool tool, List<SarifResult> results) {
    }

    /** SARIF tool wrapper holding the driver descriptor. */
    private record SarifTool(SarifDriver driver) {
    }

    /** SARIF driver descriptor containing the tool name, version, and rules. */
    private record SarifDriver(String name, String version, List<SarifRule> rules) {
    }

    /** SARIF rule definition with an id, camel-case name, short description, and optional properties. */
    @JsonInclude(Include.NON_NULL)
    private record SarifRule(String id, String name, SarifMessage shortDescription,
            SarifRuleProperties properties) {
    }

    /** Rule-level property bag carrying the tag list used by GitHub Code Scanning filters. */
    private record SarifRuleProperties(List<String> tags) {
    }

    /** SARIF result record representing one discovered test method. */
    private record SarifResult(
            String ruleId,
            String level,
            SarifMessage message,
            List<SarifLocation> locations,
            SarifProperties properties) {
    }

    /** SARIF location combining a physical and logical location for a result. */
    private record SarifLocation(
            SarifPhysicalLocation physicalLocation,
            List<SarifLogicalLocation> logicalLocations) {
    }

    /** SARIF physical location identifying a file and an optional region. */
    private record SarifPhysicalLocation(
            SarifArtifactLocation artifactLocation,
            @JsonInclude(Include.NON_NULL) SarifRegion region) {
    }

    /** SARIF artifact location holding a URI and an optional URI base-id token. */
    private record SarifArtifactLocation(String uri, String uriBaseId) {
    }

    /** SARIF region identifying the starting line of a result within a file. */
    private record SarifRegion(int startLine) {
    }

    /** SARIF logical location holding a fully-qualified member name and kind. */
    private record SarifLogicalLocation(String fullyQualifiedName, String kind) {
    }

    /** SARIF message wrapper containing a plain-text description string. */
    private record SarifMessage(String text) {
    }

    /** Custom SARIF properties bag carrying MethodAtlas-specific enrichment fields. */
    @JsonInclude(Include.NON_NULL)
    private record SarifProperties(
            int loc,
            String contentHash,
            String sourceTags,
            Boolean aiSecurityRelevant,
            String aiDisplayName,
            String aiTags,
            String aiReason,
            Double aiInteractionScore,
            Double aiConfidence,
            String tagAiDrift,
            @JsonProperty("security-severity") String securitySeverity) {
    }
}
