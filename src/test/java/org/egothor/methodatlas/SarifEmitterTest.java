package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SarifEmitterTest {

    // -------------------------------------------------------------------------
    // Document structure
    // -------------------------------------------------------------------------

    @Test
    void flush_emitsValidSarif210Document() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode doc = flush(emitter);

        assertEquals("2.1.0", doc.path("version").asText());
        assertNotNull(doc.path("$schema").asText());
        assertTrue(doc.path("$schema").asText().contains("sarif-schema-2.1.0"));
        assertTrue(doc.path("runs").isArray());
        assertEquals(1, doc.path("runs").size());
    }

    @Test
    void flush_emitsEmptyResultsArrayWhenNoRecords() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        JsonNode doc = flush(emitter);

        JsonNode results = doc.path("runs").get(0).path("results");
        assertTrue(results.isArray());
        assertEquals(0, results.size());
    }

    @Test
    void flush_toolDriverHasCorrectName() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        JsonNode doc = flush(emitter);

        JsonNode driver = doc.path("runs").get(0).path("tool").path("driver");
        assertEquals("MethodAtlas", driver.path("name").asText());
    }

    // -------------------------------------------------------------------------
    // Non-security test method
    // -------------------------------------------------------------------------

    @Test
    void flush_nonSecurityMethodGetsLevelNoneAndRuleTestMethod() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("test-method", result.path("ruleId").asText());
        assertEquals("none", result.path("level").asText());
    }

    @Test
    void flush_nonSecurityMethodMessageIsFullyQualifiedMethodName() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("com.acme.FooTest.testFoo", result.path("message").path("text").asText());
    }

    // -------------------------------------------------------------------------
    // Security test method (with AI suggestion)
    // -------------------------------------------------------------------------

    @Test
    void flush_securityMethodWithAuthTagGetsRuleSecurityAuth() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests login", 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("security/auth", result.path("ruleId").asText());
        assertEquals("note", result.path("level").asText());
    }

    @Test
    void flush_securityMethodDisplayNameUsedAsMessage() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login test", List.of("security", "auth"), "Tests login", 1.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("SECURITY: auth - login test", result.path("message").path("text").asText());
    }

    @Test
    void flush_securityMethodWithOnlySecurityTagGetsRuleSecurityTest() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testGeneral", true, "SECURITY: general", List.of("security"), "Security test", 0.7);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.SomeTest", "testGeneral", 1, 3, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("security-test", result.path("ruleId").asText());
    }

    // -------------------------------------------------------------------------
    // Locations
    // -------------------------------------------------------------------------

    @Test
    void flush_artifactUriDerivedFromFqcn() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.auth.AuthTest", "testLogin", 42, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals("com/acme/auth/AuthTest.java", physLoc.path("artifactLocation").path("uri").asText());
        assertEquals("%SRCROOT%", physLoc.path("artifactLocation").path("uriBaseId").asText());
    }

    @Test
    void flush_regionStartLinePresent_whenBeginLinePositive() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 42, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals(42, physLoc.path("region").path("startLine").asInt());
    }

    @Test
    void flush_regionAbsent_whenBeginLineZero() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 0, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertTrue(physLoc.path("region").isMissingNode(), "region should be absent when beginLine is 0");
    }

    @Test
    void flush_logicalLocationContainsFqmn() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode logLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("logicalLocations").get(0);
        assertEquals("com.acme.FooTest.testFoo", logLoc.path("fullyQualifiedName").asText());
        assertEquals("member", logLoc.path("kind").asText());
    }

    // -------------------------------------------------------------------------
    // Properties bag
    // -------------------------------------------------------------------------

    @Test
    void flush_propertiesContainLoc() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 7, null, List.of(), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals(7, props.path("loc").asInt());
    }

    @Test
    void flush_propertiesContainSourceTags_whenPresent() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of("security", "auth"), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("security;auth", props.path("sourceTags").asText());
    }

    @Test
    void flush_sourceTagsAbsent_whenEmpty() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("sourceTags").isMissingNode(), "sourceTags should be absent when empty");
    }

    @Test
    void flush_propertiesContainAiFields_whenAiEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests login auth", 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiSecurityRelevant").asBoolean());
        assertEquals("SECURITY: auth - login", props.path("aiDisplayName").asText());
        assertEquals("security;auth", props.path("aiTags").asText());
        assertEquals("Tests login auth", props.path("aiReason").asText());
    }

    @Test
    void flush_aiConfidenceAbsent_whenConfidenceDisabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "reason", 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false); // confidenceEnabled=false
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiConfidence").isMissingNode(), "aiConfidence should be absent when disabled");
    }

    @Test
    void flush_aiConfidencePresent_whenConfidenceEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "reason", 0.85);
        SarifEmitter emitter = new SarifEmitter(true, true); // confidenceEnabled=true
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertFalse(props.path("aiConfidence").isMissingNode(), "aiConfidence should be present when enabled");
        assertEquals(0.85, props.path("aiConfidence").asDouble(), 0.001);
    }

    // -------------------------------------------------------------------------
    // Rules deduplication
    // -------------------------------------------------------------------------

    @Test
    void flush_rulesAreDeduplicatedAcrossResults() throws Exception {
        AiMethodSuggestion authSuggestion = new AiMethodSuggestion(
                "testLogin", true, null, List.of("security", "auth"), null, 1.0);
        AiMethodSuggestion authSuggestion2 = new AiMethodSuggestion(
                "testLogout", true, null, List.of("security", "auth"), null, 1.0);

        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), authSuggestion);
        emitter.record("com.acme.AuthTest", "testLogout", 20, 6, null, List.of(), authSuggestion2);

        JsonNode driver = flush(emitter).path("runs").get(0).path("tool").path("driver");
        JsonNode rules = driver.path("rules");
        assertTrue(rules.isArray());
        // Both methods use "security/auth" → only one rule entry
        long authRuleCount = 0;
        for (JsonNode rule : rules) {
            if ("security/auth".equals(rule.path("id").asText())) {
                authRuleCount++;
            }
        }
        assertEquals(1, authRuleCount, "security/auth rule should appear exactly once");
    }

    @Test
    void flush_multipleResultsAllEmitted() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testA", 1, 3, null, List.of(), null);
        emitter.record("com.acme.FooTest", "testB", 10, 5, null, List.of(), null);
        emitter.record("com.acme.BarTest", "testC", 1, 2, null, List.of(), null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(3, results.size());
    }

    // -------------------------------------------------------------------------
    // Content hash in SARIF properties
    // -------------------------------------------------------------------------

    @Test
    void contentHash_absentFromPropertiesWhenNull() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertTrue(properties.path("contentHash").isMissingNode(),
                "contentHash should not be present when null");
    }

    @Test
    void contentHash_presentInPropertiesWhenProvided() throws Exception {
        String hash = "a".repeat(64); // simulate a 64-char hex string
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, hash, List.of(), null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertEquals(hash, properties.path("contentHash").asText());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JsonNode flush(SarifEmitter emitter) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), false)) {
            emitter.flush(pw);
        }
        return new ObjectMapper().readTree(baos.toString(StandardCharsets.UTF_8));
    }

    private static JsonNode getFirstResult(JsonNode doc) {
        return doc.path("runs").get(0).path("results").get(0);
    }
}
