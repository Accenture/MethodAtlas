package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies that the taxonomy version is stamped into AI-classified records of
 * every emitter (CSV, plain, JSON, SARIF) and left blank or absent otherwise.
 *
 * @since 6.1.0
 */
@Tag("unit")
@Tag("taxonomy-version")
class TaxonomyVersionEmissionTest {

    private static final String VERSION = "built-in/default@1";
    private static final String COLUMN = "ai_taxonomy_version";

    private static final AiMethodSuggestion SUGGESTION = new AiMethodSuggestion(
            "m", true, "SECURITY: auth - login", List.of("security", "auth"), "why", 0.9, 0.1);

    private static String csv(boolean ai, boolean confidence, boolean drift, boolean cwe, String version,
            AiMethodSuggestion suggestion) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            OutputEmitter e = new OutputEmitter(pw, ai, confidence, true, drift, true, cwe, version);
            e.emitCsvHeader(OutputMode.CSV);
            e.emit(OutputMode.CSV, "com.acme.FooTest", "m", 5, "abc", List.of("security"), "", suggestion, "src/");
        }
        return norm(sw);
    }

    /** Normalises platform line separators so assertions split on {@code \n}. */
    private static String norm(StringWriter sw) {
        return sw.toString().replace("\r\n", "\n");
    }

    @Test
    @DisplayName("CSV header ends with ai_taxonomy_version, after every optional AI column")
    @Tag("positive")
    void csvHeader_taxonomyVersionIsLastColumn() {
        String header = csv(true, true, true, true, VERSION, SUGGESTION).split("\n")[0];
        assertTrue(header.endsWith(",ai_cwe," + COLUMN), header);
        assertEquals("fqcn,method,loc,tags,display_name,source_root,content_hash,"
                + "ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,"
                + "ai_confidence,tag_ai_drift,tags_added,tags_removed,ai_cwe," + COLUMN, header);
    }

    @Test
    @DisplayName("CSV header is unchanged for non-AI runs")
    @Tag("edge-case")
    void csvHeader_noAi_hasNoTaxonomyColumn() {
        String header = csv(false, false, false, false, null, null).split("\n")[0];
        assertFalse(header.contains(COLUMN), header);
    }

    @Test
    @DisplayName("CSV AI row carries the taxonomy version as its last field")
    @Tag("positive")
    void csvRow_aiClassified_carriesVersion() {
        String row = csv(true, false, false, false, VERSION, SUGGESTION).split("\n")[1];
        assertTrue(row.endsWith("," + VERSION), row);
    }

    @Test
    @DisplayName("CSV row without an AI suggestion leaves the taxonomy version blank")
    @Tag("edge-case")
    void csvRow_nonAiRow_blank() {
        String[] lines = csv(true, false, false, false, VERSION, null).split("\n");
        assertTrue(lines[0].endsWith("," + COLUMN));
        assertTrue(lines[1].endsWith(","), "trailing field must be empty: " + lines[1]);
        assertFalse(lines[1].contains(VERSION), lines[1]);
    }

    @Test
    @DisplayName("CSV without a configured version leaves the column blank even for AI rows")
    @Tag("edge-case")
    void csvRow_nullVersion_blank() {
        String row = csv(true, false, false, false, null, SUGGESTION).split("\n")[1];
        assertTrue(row.endsWith(","), row);
    }

    @Test
    @DisplayName("legacy 7-argument constructor still produces the column, blank")
    @Tag("edge-case")
    void legacyConstructor_blankColumn() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            OutputEmitter e = new OutputEmitter(pw, true, false, false, false, false, false);
            e.emitCsvHeader(OutputMode.CSV);
            e.emit(OutputMode.CSV, "c", "m", 1, null, List.of(), "", SUGGESTION, null);
        }
        String[] lines = norm(sw).split("\n");
        assertTrue(lines[0].endsWith("," + COLUMN));
        assertTrue(lines[1].endsWith(","));
    }

    @Test
    @DisplayName("plain output carries AI_TAXONOMY_VERSION for AI rows and '-' otherwise")
    @Tag("positive")
    void plain_carriesVersionOrDash() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            OutputEmitter e = new OutputEmitter(pw, true, false, false, false, false, false, VERSION);
            e.emit(OutputMode.PLAIN, "c", "m", 1, null, List.of(), "", SUGGESTION, null);
            e.emit(OutputMode.PLAIN, "c", "n", 1, null, List.of(), "", null, null);
        }
        String[] lines = norm(sw).split("\n");
        assertTrue(lines[0].endsWith(", AI_TAXONOMY_VERSION=" + VERSION), lines[0]);
        assertTrue(lines[1].endsWith(", AI_TAXONOMY_VERSION=-"), lines[1]);
    }

    private static JsonNode json(String version, boolean ai, AiMethodSuggestion... suggestions) throws Exception {
        JsonEmitter emitter = new JsonEmitter(ai, false, false, false, false, version);
        int i = 0;
        for (AiMethodSuggestion s : suggestions) {
            emitter.record("com.acme.Foo", "m" + i++, 1, 5, null, List.of(), "", s, null);
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw);
        }
        return new ObjectMapper().readTree(sw.toString());
    }

    @Test
    @DisplayName("JSON AI record carries ai_taxonomy_version; non-AI record omits it")
    @Tag("positive")
    void json_stampsOnlyAiRecords() throws Exception {
        JsonNode doc = json(VERSION, true, SUGGESTION, null);
        assertEquals(VERSION, doc.get(0).path(COLUMN).asString());
        assertTrue(doc.get(1).path(COLUMN).isMissingNode(), "row without suggestion must omit the field");
    }

    @Test
    @DisplayName("JSON omits ai_taxonomy_version when AI is disabled or no version is configured")
    @Tag("edge-case")
    void json_omittedWhenNotApplicable() throws Exception {
        assertTrue(json(VERSION, false, SUGGESTION).get(0).path(COLUMN).isMissingNode());
        assertTrue(json(null, true, SUGGESTION).get(0).path(COLUMN).isMissingNode());
        assertTrue(json("", true, SUGGESTION).get(0).path(COLUMN).isMissingNode());
    }

    private static JsonNode sarifResults(String version, boolean ai, AiMethodSuggestion s) throws Exception {
        SarifEmitter emitter = new SarifEmitter(ai, false, "", true, version);
        emitter.record("com.acme.FooTest", "m", 3, 5, null, List.of("security"), null, s);
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw);
        }
        return new ObjectMapper().readTree(sw.toString()).path("runs").get(0).path("results");
    }

    @Test
    @DisplayName("SARIF AI result carries the aiTaxonomyVersion property")
    @Tag("positive")
    void sarif_aiResult_carriesProperty() throws Exception {
        JsonNode results = sarifResults(VERSION, true, SUGGESTION);
        assertTrue(results.size() > 0);
        for (JsonNode r : results) {
            assertEquals(VERSION, r.path("properties").path("aiTaxonomyVersion").asString(), r.toString());
        }
    }

    @Test
    @DisplayName("SARIF result without an AI suggestion omits aiTaxonomyVersion")
    @Tag("edge-case")
    void sarif_nonAiResult_omitsProperty() throws Exception {
        JsonNode results = sarifResults(VERSION, true, null);
        for (JsonNode r : results) {
            assertTrue(r.path("properties").path("aiTaxonomyVersion").isMissingNode(), r.toString());
        }
        JsonNode aiOff = sarifResults(VERSION, false, SUGGESTION);
        for (JsonNode r : aiOff) {
            assertTrue(r.path("properties").path("aiTaxonomyVersion").isMissingNode(), r.toString());
        }
    }
}
