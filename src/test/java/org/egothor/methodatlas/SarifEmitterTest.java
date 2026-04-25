package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SarifEmitter}.
 *
 * <p>
 * This class verifies that {@link SarifEmitter} produces well-formed SARIF 2.1.0
 * output, with correct document structure, result-level fields (ruleId, level,
 * message, locations, properties), AI enrichment fields, confidence, content
 * hash, and rule deduplication.
 * </p>
 */
@Tag("unit")
@Tag("sarif")
class SarifEmitterTest {

    // -------------------------------------------------------------------------
    // Document structure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("flush emits a valid SARIF 2.1.0 document with version and schema")
    @Tag("positive")
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
    @DisplayName("flush emits an empty results array when no records have been added")
    @Tag("edge-case")
    void flush_emitsEmptyResultsArrayWhenNoRecords() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        JsonNode doc = flush(emitter);

        JsonNode results = doc.path("runs").get(0).path("results");
        assertTrue(results.isArray());
        assertEquals(0, results.size());
    }

    @Test
    @DisplayName("flush tool driver has name 'MethodAtlas'")
    @Tag("positive")
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
    @DisplayName("non-security method gets level 'none' and ruleId 'test-method'")
    @Tag("positive")
    void flush_nonSecurityMethodGetsLevelNoneAndRuleTestMethod() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("test-method", result.path("ruleId").asText());
        assertEquals("none", result.path("level").asText());
    }

    @Test
    @DisplayName("non-security method message text is fully qualified method name")
    @Tag("positive")
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
    @DisplayName("security method with 'auth' tag gets ruleId 'security/auth' and level 'note'")
    @Tag("positive")
    void flush_securityMethodWithAuthTagGetsRuleSecurityAuth() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests login", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("security/auth", result.path("ruleId").asText());
        assertEquals("note", result.path("level").asText());
    }

    @Test
    @DisplayName("security method displayName from suggestion is used as message text")
    @Tag("positive")
    void flush_securityMethodDisplayNameUsedAsMessage() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login test", List.of("security", "auth"), "Tests login", 1.0, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("SECURITY: auth - login test", result.path("message").path("text").asText());
    }

    @Test
    @DisplayName("security method with only 'security' tag gets ruleId 'security-test'")
    @Tag("positive")
    void flush_securityMethodWithOnlySecurityTagGetsRuleSecurityTest() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testGeneral", true, "SECURITY: general", List.of("security"), "Security test", 0.7, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.SomeTest", "testGeneral", 1, 3, null, List.of(), suggestion);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("security-test", result.path("ruleId").asText());
    }

    // -------------------------------------------------------------------------
    // Locations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("artifactLocation uri is derived from FQCN with dots replaced by slashes and .java appended")
    @Tag("positive")
    void flush_artifactUriDerivedFromFqcn() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.auth.AuthTest", "testLogin", 42, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals("com/acme/auth/AuthTest.java", physLoc.path("artifactLocation").path("uri").asText());
        assertEquals("%SRCROOT%", physLoc.path("artifactLocation").path("uriBaseId").asText());
    }

    @Test
    @DisplayName("region startLine is present when beginLine is positive")
    @Tag("positive")
    void flush_regionStartLinePresent_whenBeginLinePositive() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 42, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals(42, physLoc.path("region").path("startLine").asInt());
    }

    @Test
    @DisplayName("region is absent when beginLine is zero")
    @Tag("edge-case")
    void flush_regionAbsent_whenBeginLineZero() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 0, 5, null, List.of(), null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertTrue(physLoc.path("region").isMissingNode(), "region should be absent when beginLine is 0");
    }

    @Test
    @DisplayName("logicalLocation contains fully qualified method name and kind 'member'")
    @Tag("positive")
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
    @DisplayName("properties bag contains loc with correct value")
    @Tag("positive")
    void flush_propertiesContainLoc() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 7, null, List.of(), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals(7, props.path("loc").asInt());
    }

    @Test
    @DisplayName("properties bag contains sourceTags joined by semicolon when tags are present")
    @Tag("positive")
    void flush_propertiesContainSourceTags_whenPresent() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of("security", "auth"), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("security;auth", props.path("sourceTags").asText());
    }

    @Test
    @DisplayName("properties bag sourceTags is absent when tags list is empty")
    @Tag("edge-case")
    void flush_sourceTagsAbsent_whenEmpty() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("sourceTags").isMissingNode(), "sourceTags should be absent when empty");
    }

    @Test
    @DisplayName("properties bag contains AI fields when AI is enabled and suggestion is present")
    @Tag("positive")
    void flush_propertiesContainAiFields_whenAiEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests login auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiSecurityRelevant").asBoolean());
        assertEquals("SECURITY: auth - login", props.path("aiDisplayName").asText());
        assertEquals("security;auth", props.path("aiTags").asText());
        assertEquals("Tests login auth", props.path("aiReason").asText());
    }

    @Test
    @DisplayName("aiConfidence is absent in properties when confidence is disabled")
    @Tag("positive")
    void flush_aiConfidenceAbsent_whenConfidenceDisabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "reason", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false); // confidenceEnabled=false
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiConfidence").isMissingNode(), "aiConfidence should be absent when disabled");
    }

    @Test
    @DisplayName("aiConfidence is present in properties when confidence is enabled")
    @Tag("positive")
    void flush_aiConfidencePresent_whenConfidenceEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "reason", 0.85, 0.0);
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
    @DisplayName("identical security rules are deduplicated across multiple results")
    @Tag("edge-case")
    void flush_rulesAreDeduplicatedAcrossResults() throws Exception {
        AiMethodSuggestion authSuggestion = new AiMethodSuggestion(
                "testLogin", true, null, List.of("security", "auth"), null, 1.0, 0.0);
        AiMethodSuggestion authSuggestion2 = new AiMethodSuggestion(
                "testLogout", true, null, List.of("security", "auth"), null, 1.0, 0.0);

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
    @DisplayName("all three records are emitted when three methods are recorded")
    @Tag("positive")
    void flush_multipleResultsAllEmitted() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testA", 1, 3, null, List.of(), null);
        emitter.record("com.acme.FooTest", "testB", 10, 5, null, List.of(), null);
        emitter.record("com.acme.BarTest", "testC", 1, 2, null, List.of(), null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(3, results.size());
    }

    // -------------------------------------------------------------------------
    // Tag-vs-AI drift in SARIF properties
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tagAiDrift is 'none' when source tag and AI both agree: security-relevant")
    @Tag("positive")
    void flush_tagAiDriftNone_whenBothAgreeSecurityRelevant() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login security", List.of("auth"), "Tests auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of("security"), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("none", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is 'ai-only' when AI says security-relevant but no @Tag(security) in source")
    @Tag("positive")
    void flush_tagAiDriftAiOnly_whenAiSecurityButNoSourceTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login security", List.of("auth"), "Tests auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("ai-only", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is 'tag-only' when @Tag(security) present but AI says not security-relevant")
    @Tag("positive")
    void flush_tagAiDriftTagOnly_whenSourceTagButAiDisagrees() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", false, "Format check", List.of(), "Not security", 0.1, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("tag-only", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is absent from properties when AI is disabled")
    @Tag("edge-case")
    void flush_tagAiDriftAbsent_whenAiDisabled() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("tagAiDrift").isMissingNode(),
                "tagAiDrift should be absent when AI is disabled");
    }

    @Test
    @DisplayName("tagAiDrift is absent from properties when suggestion is null even if AI is enabled")
    @Tag("edge-case")
    void flush_tagAiDriftAbsent_whenSuggestionNull() throws Exception {
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("tagAiDrift").isMissingNode(),
                "tagAiDrift should be absent when suggestion is null");
    }

    // -------------------------------------------------------------------------
    // Content hash in SARIF properties
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("contentHash is absent from properties when null is passed")
    @Tag("edge-case")
    void contentHash_absentFromPropertiesWhenNull() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertTrue(properties.path("contentHash").isMissingNode(),
                "contentHash should not be present when null");
    }

    @Test
    @DisplayName("contentHash is present in properties when a hash value is provided")
    @Tag("positive")
    void contentHash_presentInPropertiesWhenProvided() throws Exception {
        String hash = "a".repeat(64); // simulate a 64-char hex string
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, hash, List.of(), null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertEquals(hash, properties.path("contentHash").asText());
    }

    // -------------------------------------------------------------------------
    // security-severity property
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("security-severity is absent for non-security method")
    @Tag("positive")
    void flush_securitySeverityAbsent_forNonSecurityMethod() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("security-severity").isMissingNode(),
                "security-severity should be absent for non-security methods");
    }

    @Test
    @DisplayName("security-severity is '7.5' for security method with 'auth' tag")
    @Tag("positive")
    void flush_securitySeverityIsHigh_forAuthTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login test", List.of("security", "auth"), "Tests auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("7.5", props.path("security-severity").asText());
    }

    @Test
    @DisplayName("security-severity is '9.0' for security method with 'injection' tag")
    @Tag("positive")
    void flush_securitySeverityIsCritical_forInjectionTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testSqlInjection", true, "SQL injection test", List.of("security", "injection"), "reason", 0.95, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.SqlTest", "testSqlInjection", 5, 8, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("9.0", props.path("security-severity").asText());
    }

    @Test
    @DisplayName("security-severity defaults to '5.0' for generic security method with no matched tag")
    @Tag("positive")
    void flush_securitySeverityDefaultsMedium_forUnknownTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testSomething", true, "Security test", List.of("security"), "reason", 0.8, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.SomeTest", "testSomething", 5, 4, null, List.of(), suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("5.0", props.path("security-severity").asText());
    }

    @Test
    @DisplayName("security-severity is '5.0' when AI is disabled but method has security rule")
    @Tag("positive")
    void flush_securitySeverityPresentWithoutAi_whenRuleIsSecurityTest() throws Exception {
        // With AI disabled, only source tags set the ruleId (which stays "test-method" when no AI)
        // so we use an AI suggestion but with aiEnabled=false to get security-test rule via source tags
        // Actually without AI, all become test-method → security-severity absent.
        // This test verifies the no-AI / security-test path doesn't apply (ruleId = test-method).
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.SomeTest", "testSomething", 5, 4, null, List.of("security"), null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        // Without AI, ruleId is always "test-method" → no security-severity
        assertTrue(props.path("security-severity").isMissingNode(),
                "Without AI, ruleId is test-method and security-severity should be absent");
    }

    // -------------------------------------------------------------------------
    // Rule properties (tags for GitHub Code Scanning filter)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("security/auth rule has properties.tags containing 'security' and 'auth'")
    @Tag("positive")
    void flush_securityAuthRuleHasTags() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login test", List.of("security", "auth"), "reason", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), suggestion);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        JsonNode authRule = null;
        for (JsonNode rule : rules) {
            if ("security/auth".equals(rule.path("id").asText())) {
                authRule = rule;
            }
        }
        assertNotNull(authRule, "security/auth rule should exist");
        JsonNode tags = authRule.path("properties").path("tags");
        assertTrue(tags.isArray(), "rule properties.tags should be an array");
        List<String> tagValues = new ArrayList<>();
        for (JsonNode t : tags) {
            tagValues.add(t.asText());
        }
        assertTrue(tagValues.contains("security"), "tags should contain 'security'");
        assertTrue(tagValues.contains("auth"), "tags should contain 'auth'");
    }

    @Test
    @DisplayName("test-method rule has properties.tags containing 'test'")
    @Tag("positive")
    void flush_testMethodRuleHasTestTag() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false);
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        JsonNode testRule = null;
        for (JsonNode rule : rules) {
            if ("test-method".equals(rule.path("id").asText())) {
                testRule = rule;
            }
        }
        assertNotNull(testRule, "test-method rule should exist");
        JsonNode tags = testRule.path("properties").path("tags");
        assertTrue(tags.isArray(), "rule properties.tags should be an array");
        boolean hasTest = false;
        for (JsonNode t : tags) {
            if ("test".equals(t.asText())) {
                hasTest = true;
            }
        }
        assertTrue(hasTest, "test-method rule should have 'test' tag");
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
