package org.egothor.methodatlas.emit;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.egothor.methodatlas.TagAiDrift;
import org.egothor.methodatlas.TestMethodSink;
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
 * the orchestration layer in {@code MethodAtlasApp}.
 * </p>
 *
 * @see TestMethodSink
 */
public final class SarifEmitter implements TestMethodSink {

    private static final String SARIF_SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
    private static final String SARIF_VERSION = "2.1.0";
    private static final int SINGLE_CHAR_LENGTH = 1;
    private static final int RULE_ID_PART_COUNT = 2;
    private static final String RULE_TEST_METHOD = "test-method";
    private static final String RULE_SECURITY_TEST = "security-test";
    private static final String RULE_EMPTY_DISPLAY_NAME = "annotation/empty-display-name";
    private static final String RULE_SECURITY_PLACEBO = "security-test/placebo";
    private static final String LEVEL_NOTE = "note";
    private static final String LEVEL_NONE = "none";
    private static final String LEVEL_WARNING = "warning";

    /** Interaction score at or above which a security test is flagged as a potential placebo. */
    private static final double PLACEBO_THRESHOLD = 0.8;

    private static final String SEVERITY_CRITICAL = "9.0";
    private static final String SEVERITY_DESERIALIZATION = "8.5";
    private static final String SEVERITY_HIGH = "7.5";
    private static final String SEVERITY_MEDIUM_HIGH = "6.5";
    private static final String SEVERITY_MEDIUM = "5.5";
    private static final String SEVERITY_PLACEBO = "6.0";
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

    private static final Pattern RULE_NAME_SEPARATOR = Pattern.compile("[/-]");

    private final boolean aiEnabled;
    private final boolean confidenceEnabled;
    private final String filePrefix;
    private final String toolVersion;
    private final List<ResultRecord> records = new ArrayList<>();

    /**
     * Creates a new SARIF emitter.
     *
     * @param aiEnabled         whether AI enrichment columns should be included
     * @param confidenceEnabled whether the {@code aiConfidence} property should
     *                          be included; only meaningful when {@code aiEnabled}
     *                          is {@code true}
     * @param filePrefix        forward-slash path prefix prepended to every
     *                          artifact URI to produce a repo-relative path (e.g.
     *                          {@code "src/test/java/"}); use empty string when
     *                          the scan root is already the repository root
     */
    public SarifEmitter(boolean aiEnabled, boolean confidenceEnabled, String filePrefix) {
        this.aiEnabled = aiEnabled;
        this.confidenceEnabled = confidenceEnabled;
        this.filePrefix = filePrefix;
        String v = SarifEmitter.class.getPackage().getImplementationVersion();
        this.toolVersion = v != null ? v : "dev";
    }

    /**
     * Buffers a single test method record.
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        records.add(new ResultRecord(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion));
    }

    /**
     * Serializes all buffered records as a SARIF 2.1.0 JSON document and writes
     * it to the supplied writer.
     *
     * @param out destination writer
     * @throws IllegalStateException if JSON serialization fails
     */
    public void flush(PrintWriter out) {
        Map<String, SarifRule> rulesById = new LinkedHashMap<>();
        List<SarifResult> results = new ArrayList<>();

        for (ResultRecord rec : records) {
            String ruleId = resolveRuleId(rec.suggestion());
            rulesById.computeIfAbsent(ruleId, SarifEmitter::buildRule);
            results.add(buildResult(rec, ruleId));

            if (rec.displayName() != null && rec.displayName().isEmpty()) {
                rulesById.computeIfAbsent(RULE_EMPTY_DISPLAY_NAME, SarifEmitter::buildRule);
                results.add(buildEmptyDisplayNameResult(rec));
            }

            AiMethodSuggestion s = rec.suggestion();
            if (s != null && s.securityRelevant() && s.interactionScore() >= PLACEBO_THRESHOLD) {
                rulesById.computeIfAbsent(RULE_SECURITY_PLACEBO, SarifEmitter::buildRule);
                results.add(buildPlaceboResult(rec));
            }
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
        SarifHelp help = new SarifHelp(toRuleHelp(ruleId));
        return new SarifRule(ruleId, name, new SarifMessage(description), ruleProps, help);
    }

    private static List<String> toRuleTags(String ruleId) {
        if (RULE_TEST_METHOD.equals(ruleId)) {
            return List.of("test");
        }
        if (RULE_SECURITY_TEST.equals(ruleId)) {
            return List.of("security");
        }
        if (RULE_EMPTY_DISPLAY_NAME.equals(ruleId)) {
            return List.of("annotation", "quality");
        }
        if (RULE_SECURITY_PLACEBO.equals(ruleId)) {
            return List.of("security", "placebo", "test-quality");
        }
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
            case RULE_EMPTY_DISPLAY_NAME -> "@DisplayName annotation with empty string value";
            case RULE_SECURITY_PLACEBO ->
                "Security test with interaction-only assertions (placebo test)";
            default -> ruleId.startsWith("security/")
                    ? "Security test: " + ruleId.substring("security/".length())
                    : ruleId;
        };
    }

    private static String toRuleHelp(String ruleId) {
        return switch (ruleId) {
            case RULE_TEST_METHOD ->
                "MethodAtlas inventories all JUnit test methods found in the scanned source tree. "
                + "This result represents a test method that was not classified as security-relevant "
                + "by the AI, or that was scanned without AI enrichment enabled. No action is required.";
            case RULE_EMPTY_DISPLAY_NAME ->
                "A @DisplayName(\"\") annotation produces an unnamed test entry in JUnit reports, "
                + "CI dashboards, and audit evidence packages. Tests without names are difficult to "
                + "trace in security audit logs. Replace @DisplayName(\"\") with a meaningful "
                + "description of what the test verifies.";
            case RULE_SECURITY_PLACEBO ->
                "This security test has an interaction score >= 0.8, meaning its assertions "
                + "primarily verify that methods were called (e.g. Mockito verify(), spy call counts) "
                + "rather than asserting on return values, thrown exceptions, or observable state. "
                + "Such tests may give false confidence: the code under test could return wrong data "
                + "or corrupt state and the test would still pass. "
                + "Add assertions on security-critical outputs, e.g. "
                + "assertThat(response.getStatus()).isEqualTo(403), "
                + "assertThrows(SecurityException.class, ...), "
                + "or assertThat(audit.getEvents()).contains(expectedEvent).";
            default ->
                "MethodAtlas detected this test method as security-relevant via AI analysis. "
                + "Review the suggested @DisplayName and @Tag values in the result message. "
                + "If correct, apply them by running: ./methodatlas -ai -apply-tags SOURCE_ROOT. "
                + "An interaction score ≥ 0.8 in the result properties means the test verifies "
                + "only method calls, not actual outcomes — consider adding outcome assertions.";
        };
    }

    private SarifResult buildResult(ResultRecord rec, String ruleId) {
        String level = RULE_TEST_METHOD.equals(ruleId) ? LEVEL_NONE : LEVEL_NOTE;
        String messageText = resolveMessageText(rec);

        String artifactUri = filePrefix + rec.fqcn().replace('.', '/') + ".java";
        SarifArtifactLocation artifactLocation = new SarifArtifactLocation(artifactUri, null);

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
        if (s == null || !s.securityRelevant()) {
            return rec.fqcn() + "." + rec.method();
        }

        StringBuilder sb = new StringBuilder(256);

        if (s.displayName() != null && !s.displayName().isBlank()) {
            sb.append("AI suggests: @DisplayName(\"").append(s.displayName()).append("\")");
        } else {
            sb.append("AI classifies as security-relevant");
        }
        if (s.tags() != null && !s.tags().isEmpty()) {
            for (String tag : s.tags()) {
                sb.append(" @Tag(\"").append(tag).append("\")");
            }
        }
        sb.append('.');

        if (s.reason() != null && !s.reason().isBlank()) {
            String reason = s.reason().strip();
            sb.append(" Reason: ").append(reason);
            if (!reason.endsWith(".")) {
                sb.append('.');
            }
        }

        if (s.interactionScore() >= PLACEBO_THRESHOLD) {
            sb.append(String.format(Locale.ROOT,
                    " Interaction score %.1f: assertions verify only method calls, not actual outcomes.",
                    s.interactionScore()));
        }

        return sb.toString();
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

    private SarifResult buildEmptyDisplayNameResult(ResultRecord rec) {
        String artifactUri = filePrefix + rec.fqcn().replace('.', '/') + ".java";
        SarifArtifactLocation artifactLocation = new SarifArtifactLocation(artifactUri, null);
        SarifRegion region = rec.beginLine() > 0 ? new SarifRegion(rec.beginLine()) : null;
        SarifPhysicalLocation physicalLocation = new SarifPhysicalLocation(artifactLocation, region);
        SarifLogicalLocation logicalLocation = new SarifLogicalLocation(
                rec.fqcn() + "." + rec.method(), "member");
        SarifLocation location = new SarifLocation(physicalLocation, List.of(logicalLocation));
        String message = "@DisplayName(\"\") on " + rec.fqcn() + "." + rec.method()
                + " is explicitly empty — the test will appear unnamed in CI reports and audit "
                + "evidence packages. Replace with a meaningful description, e.g. "
                + "@DisplayName(\"Verifies that ...\").";
        String sourceTags = rec.tags().isEmpty() ? null : String.join(";", rec.tags());
        SarifProperties properties = new SarifProperties(rec.loc(), null, sourceTags,
                null, null, null, null, null, null, null, null);
        return new SarifResult(RULE_EMPTY_DISPLAY_NAME, LEVEL_NOTE,
                new SarifMessage(message), List.of(location), properties);
    }

    private SarifResult buildPlaceboResult(ResultRecord rec) {
        String artifactUri = filePrefix + rec.fqcn().replace('.', '/') + ".java";
        SarifArtifactLocation artifactLocation = new SarifArtifactLocation(artifactUri, null);
        SarifRegion region = rec.beginLine() > 0 ? new SarifRegion(rec.beginLine()) : null;
        SarifPhysicalLocation physicalLocation = new SarifPhysicalLocation(artifactLocation, region);
        SarifLogicalLocation logicalLocation = new SarifLogicalLocation(
                rec.fqcn() + "." + rec.method(), "member");
        SarifLocation location = new SarifLocation(physicalLocation, List.of(logicalLocation));

        AiMethodSuggestion s = rec.suggestion();
        String message = String.format(Locale.ROOT,
                "Interaction score %.1f: this security test only verifies that methods were called, "
                + "not what values they returned or what state they produced. "
                + "Tests that do not assert outcomes cannot catch regressions in security-critical logic. "
                + "Add assertions on return values, thrown exceptions, or observable state changes.",
                s.interactionScore());

        String sourceTags = rec.tags().isEmpty() ? null : String.join(";", rec.tags());
        SarifProperties properties = new SarifProperties(rec.loc(), null, sourceTags,
                null, null, null, null, s.interactionScore(), null, null, SEVERITY_PLACEBO);
        return new SarifResult(RULE_SECURITY_PLACEBO, LEVEL_WARNING,
                new SarifMessage(message), List.of(location), properties);
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
            String contentHash, List<String> tags, String displayName, AiMethodSuggestion suggestion) {
    }

    // -------------------------------------------------------------------------
    // SARIF 2.1.0 POJO records
    // -------------------------------------------------------------------------

    private record SarifDocument(
            @JsonProperty("$schema") String schema,
            String version,
            List<SarifRun> runs) {
    }

    private record SarifRun(SarifTool tool, List<SarifResult> results) {
    }

    private record SarifTool(SarifDriver driver) {
    }

    private record SarifDriver(String name, String version, List<SarifRule> rules) {
    }

    @JsonInclude(Include.NON_NULL)
    private record SarifRule(String id, String name, SarifMessage shortDescription,
            SarifRuleProperties properties, SarifHelp help) {
    }

    private record SarifHelp(String text) {
    }

    private record SarifRuleProperties(List<String> tags) {
    }

    private record SarifResult(
            String ruleId,
            String level,
            SarifMessage message,
            List<SarifLocation> locations,
            SarifProperties properties) {
    }

    private record SarifLocation(
            SarifPhysicalLocation physicalLocation,
            List<SarifLogicalLocation> logicalLocations) {
    }

    private record SarifPhysicalLocation(
            SarifArtifactLocation artifactLocation,
            @JsonInclude(Include.NON_NULL) SarifRegion region) {
    }

    @JsonInclude(Include.NON_NULL)
    private record SarifArtifactLocation(String uri, String uriBaseId) {
    }

    private record SarifRegion(int startLine) {
    }

    private record SarifLogicalLocation(String fullyQualifiedName, String kind) {
    }

    private record SarifMessage(String text) {
    }

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
