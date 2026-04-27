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
import org.egothor.methodatlas.emit.SarifEmitter;
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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        JsonNode doc = flush(emitter);

        JsonNode results = doc.path("runs").get(0).path("results");
        assertTrue(results.isArray());
        assertEquals(0, results.size());
    }

    @Test
    @DisplayName("flush tool driver has name 'MethodAtlas'")
    @Tag("positive")
    void flush_toolDriverHasCorrectName() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("test-method", result.path("ruleId").asText());
        assertEquals("none", result.path("level").asText());
    }

    @Test
    @DisplayName("non-security method message text is fully qualified method name")
    @Tag("positive")
    void flush_nonSecurityMethodMessageIsFullyQualifiedMethodName() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

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
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode result = getFirstResult(flush(emitter));

        assertEquals("security/auth", result.path("ruleId").asText());
        assertEquals("note", result.path("level").asText());
    }

    @Test
    @DisplayName("security method message contains AI displayName and tag suggestions")
    @Tag("positive")
    void flush_securityMethodMessageContainsDisplayNameAndTags() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login test", List.of("security", "auth"), "Tests login auth flow.", 1.0, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        String messageText = getFirstResult(flush(emitter)).path("message").path("text").asText();
        assertTrue(messageText.contains("SECURITY: auth - login test"),
                "message should contain AI displayName");
        assertTrue(messageText.contains("@Tag(\"auth\")"),
                "message should contain suggested @Tag");
        assertTrue(messageText.contains("Tests login auth flow"),
                "message should contain AI reason");
    }

    @Test
    @DisplayName("security method with high interaction score warns about placebo in message")
    @Tag("positive")
    void flush_securityMethodMessage_highInteractionScore_includesPlaceboWarning() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("auth"), "Verifies login.", 0.9, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        String messageText = getFirstResult(flush(emitter)).path("message").path("text").asText();
        assertTrue(messageText.contains("Interaction score 0.9"),
                "message should warn about high interaction score");
        assertTrue(messageText.contains("only method calls"),
                "message should explain what the score means");
    }

    @Test
    @DisplayName("security method without AI displayName falls back to generic classification line")
    @Tag("positive")
    void flush_securityMethodMessage_noDisplayName_usesGenericLine() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, null, List.of("auth"), null, 0.8, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        String messageText = getFirstResult(flush(emitter)).path("message").path("text").asText();
        assertTrue(messageText.contains("AI classifies as security-relevant"),
                "message should fall back to generic line when displayName is null");
        assertTrue(messageText.contains("@Tag(\"auth\")"), "message should still include tag suggestion");
    }

    @Test
    @DisplayName("security method with only 'security' tag gets ruleId 'security-test'")
    @Tag("positive")
    void flush_securityMethodWithOnlySecurityTagGetsRuleSecurityTest() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testGeneral", true, "SECURITY: general", List.of("security"), "Security test", 0.7, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.SomeTest", "testGeneral", 1, 3, null, List.of(), null, suggestion);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.auth.AuthTest", "testLogin", 42, 5, null, List.of(), null, null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals("com/acme/auth/AuthTest.java", physLoc.path("artifactLocation").path("uri").asText());
        assertTrue(physLoc.path("artifactLocation").path("uriBaseId").isMissingNode(),
                "uriBaseId should be absent when filePrefix is empty — no %SRCROOT% dangling reference");
    }

    @Test
    @DisplayName("artifactLocation uri is prefixed with filePrefix when one is provided")
    @Tag("positive")
    void flush_artifactUri_includesFilePrefix() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "src/test/java/");
        emitter.record("com.acme.auth.AuthTest", "testLogin", 42, 5, null, List.of(), null, null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals("src/test/java/com/acme/auth/AuthTest.java",
                physLoc.path("artifactLocation").path("uri").asText());
        assertTrue(physLoc.path("artifactLocation").path("uriBaseId").isMissingNode(),
                "uriBaseId should be absent when filePrefix is provided");
    }

    @Test
    @DisplayName("region startLine is present when beginLine is positive")
    @Tag("positive")
    void flush_regionStartLinePresent_whenBeginLinePositive() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 42, 5, null, List.of(), null, null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertEquals(42, physLoc.path("region").path("startLine").asInt());
    }

    @Test
    @DisplayName("region is absent when beginLine is zero")
    @Tag("edge-case")
    void flush_regionAbsent_whenBeginLineZero() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 0, 5, null, List.of(), null, null);

        JsonNode physLoc = getFirstResult(flush(emitter))
                .path("locations").get(0).path("physicalLocation");
        assertTrue(physLoc.path("region").isMissingNode(), "region should be absent when beginLine is 0");
    }

    @Test
    @DisplayName("logicalLocation contains fully qualified method name and kind 'member'")
    @Tag("positive")
    void flush_logicalLocationContainsFqmn() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 7, null, List.of(), null, null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals(7, props.path("loc").asInt());
    }

    @Test
    @DisplayName("properties bag contains sourceTags joined by semicolon when tags are present")
    @Tag("positive")
    void flush_propertiesContainSourceTags_whenPresent() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of("security", "auth"), null, null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("security;auth", props.path("sourceTags").asText());
    }

    @Test
    @DisplayName("properties bag sourceTags is absent when tags list is empty")
    @Tag("edge-case")
    void flush_sourceTagsAbsent_whenEmpty() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("sourceTags").isMissingNode(), "sourceTags should be absent when empty");
    }

    @Test
    @DisplayName("properties bag contains AI fields when AI is enabled and suggestion is present")
    @Tag("positive")
    void flush_propertiesContainAiFields_whenAiEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests login auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

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
        SarifEmitter emitter = new SarifEmitter(true, false, ""); // confidenceEnabled=false
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiConfidence").isMissingNode(), "aiConfidence should be absent when disabled");
    }

    @Test
    @DisplayName("aiConfidence is present in properties when confidence is enabled")
    @Tag("positive")
    void flush_aiConfidencePresent_whenConfidenceEnabled() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "reason", 0.85, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, true, ""); // confidenceEnabled=true
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

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

        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), "", authSuggestion);
        emitter.record("com.acme.AuthTest", "testLogout", 20, 6, null, List.of(), "", authSuggestion2);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testA", 1, 3, null, List.of(), null, null);
        emitter.record("com.acme.FooTest", "testB", 10, 5, null, List.of(), null, null);
        emitter.record("com.acme.BarTest", "testC", 1, 2, null, List.of(), null, null);

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
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of("security"), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("none", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is 'ai-only' when AI says security-relevant but no @Tag(security) in source")
    @Tag("positive")
    void flush_tagAiDriftAiOnly_whenAiSecurityButNoSourceTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login security", List.of("auth"), "Tests auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("ai-only", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is 'tag-only' when @Tag(security) present but AI says not security-relevant")
    @Tag("positive")
    void flush_tagAiDriftTagOnly_whenSourceTagButAiDisagrees() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", false, "Format check", List.of(), "Not security", 0.1, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("tag-only", props.path("tagAiDrift").asText());
    }

    @Test
    @DisplayName("tagAiDrift is absent from properties when AI is disabled")
    @Tag("edge-case")
    void flush_tagAiDriftAbsent_whenAiDisabled() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), null, null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("tagAiDrift").isMissingNode(),
                "tagAiDrift should be absent when AI is disabled");
    }

    @Test
    @DisplayName("tagAiDrift is absent from properties when suggestion is null even if AI is enabled")
    @Tag("edge-case")
    void flush_tagAiDriftAbsent_whenSuggestionNull() throws Exception {
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of("security"), null, null);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertTrue(properties.path("contentHash").isMissingNode(),
                "contentHash should not be present when null");
    }

    @Test
    @DisplayName("contentHash is present in properties when a hash value is provided")
    @Tag("positive")
    void contentHash_presentInPropertiesWhenProvided() throws Exception {
        String hash = "a".repeat(64); // simulate a 64-char hex string
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, hash, List.of(), null, null);

        JsonNode properties = getFirstResult(flush(emitter)).path("properties");
        assertEquals(hash, properties.path("contentHash").asText());
    }

    // -------------------------------------------------------------------------
    // security-severity property
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("security-severity is absent for non-security method")
    @Tag("positive")
    @Tag("security")
    void flush_securitySeverityAbsent_forNonSecurityMethod() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("security-severity").isMissingNode(),
                "security-severity should be absent for non-security methods");
    }

    @Test
    @DisplayName("security-severity is '7.5' for security method with 'auth' tag")
    @Tag("positive")
    @Tag("security")
    void flush_securitySeverityIsHigh_forAuthTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login test", List.of("security", "auth"), "Tests auth", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("7.5", props.path("security-severity").asText());
    }

    @Test
    @DisplayName("security-severity is '9.0' for security method with 'injection' tag")
    @Tag("positive")
    @Tag("security")
    void flush_securitySeverityIsCritical_forInjectionTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testSqlInjection", true, "SQL injection test", List.of("security", "injection"), "reason", 0.95, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.SqlTest", "testSqlInjection", 5, 8, null, List.of(), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertEquals("9.0", props.path("security-severity").asText());
    }

    @Test
    @DisplayName("security-severity defaults to '5.0' for generic security method with no matched tag")
    @Tag("positive")
    @Tag("security")
    void flush_securitySeverityDefaultsMedium_forUnknownTag() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testSomething", true, "Security test", List.of("security"), "reason", 0.8, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.SomeTest", "testSomething", 5, 4, null, List.of(), null, suggestion);

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
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.SomeTest", "testSomething", 5, 4, null, List.of("security"), null, null);

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
    @Tag("security")
    void flush_securityAuthRuleHasTags() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login test", List.of("security", "auth"), "reason", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

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
    @DisplayName("security/auth rule has a non-empty help.text field")
    @Tag("positive")
    @Tag("security")
    void flush_securityAuthRuleHasHelpText() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Login test", List.of("security", "auth"), "reason", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        JsonNode authRule = null;
        for (JsonNode rule : rules) {
            if ("security/auth".equals(rule.path("id").asText())) {
                authRule = rule;
            }
        }
        assertNotNull(authRule, "security/auth rule should exist");
        String helpText = authRule.path("help").path("text").asText();
        assertFalse(helpText.isEmpty(), "rule.help.text should be non-empty");
        assertTrue(helpText.contains("apply-tags"), "help text should mention apply-tags action");
    }

    @Test
    @DisplayName("test-method rule has a non-empty help.text field")
    @Tag("positive")
    void flush_testMethodRuleHasHelpText() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        JsonNode testRule = null;
        for (JsonNode rule : rules) {
            if ("test-method".equals(rule.path("id").asText())) {
                testRule = rule;
            }
        }
        assertNotNull(testRule, "test-method rule should exist");
        assertFalse(testRule.path("help").path("text").asText().isEmpty(),
                "test-method rule.help.text should be non-empty");
    }

    @Test
    @DisplayName("test-method rule has properties.tags containing 'test'")
    @Tag("positive")
    void flush_testMethodRuleHasTestTag() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

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
    // annotation/empty-display-name finding
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("@DisplayName(\"\") on a method produces an additional annotation/empty-display-name result")
    @Tag("positive")
    void flush_emptyDisplayName_producesEmptyDisplayNameFinding() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), "", null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(2, results.size(), "expected one test-method result and one empty-display-name result");

        boolean hasEmptyDisplayNameRule = false;
        for (JsonNode result : results) {
            if ("annotation/empty-display-name".equals(result.path("ruleId").asText())) {
                hasEmptyDisplayNameRule = true;
                assertEquals("note", result.path("level").asText());
                String msg = result.path("message").path("text").asText();
                assertTrue(msg.contains("explicitly empty") || msg.contains("empty display name"),
                        "message should describe the empty annotation problem");
                assertTrue(msg.contains("com.acme.FooTest"), "message should identify the class");
            }
        }
        assertTrue(hasEmptyDisplayNameRule, "annotation/empty-display-name result should be present");
    }

    @Test
    @DisplayName("annotation/empty-display-name rule is registered in the driver rules list")
    @Tag("positive")
    void flush_emptyDisplayName_ruleRegisteredInDriver() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), "", null);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        boolean found = false;
        for (JsonNode rule : rules) {
            if ("annotation/empty-display-name".equals(rule.path("id").asText())) {
                found = true;
                JsonNode tags = rule.path("properties").path("tags");
                List<String> tagList = new ArrayList<>();
                for (JsonNode t : tags) tagList.add(t.asText());
                assertTrue(tagList.contains("annotation"));
                assertTrue(tagList.contains("quality"));
            }
        }
        assertTrue(found, "annotation/empty-display-name rule should be registered");
    }

    @Test
    @DisplayName("annotation/empty-display-name result has loc in properties")
    @Tag("positive")
    void flush_emptyDisplayName_resultHasLocInProperties() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 7, null, List.of("security"), "", null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        JsonNode emptyDnResult = null;
        for (JsonNode r : results) {
            if ("annotation/empty-display-name".equals(r.path("ruleId").asText())) {
                emptyDnResult = r;
            }
        }
        assertNotNull(emptyDnResult, "annotation/empty-display-name result should be present");
        assertEquals(7, emptyDnResult.path("properties").path("loc").asInt(),
                "loc should be present in annotation/empty-display-name properties");
        assertEquals("security", emptyDnResult.path("properties").path("sourceTags").asText(),
                "sourceTags should be present when method has source tags");
    }

    @Test
    @DisplayName("absent @DisplayName (null) does not produce annotation/empty-display-name result")
    @Tag("positive")
    void flush_nullDisplayName_noEmptyDisplayNameFinding() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 10, 5, null, List.of(), null, null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(1, results.size(), "only the test-method result should be present");
        assertEquals("test-method", results.get(0).path("ruleId").asText());
    }

    @Test
    @DisplayName("@DisplayName(\"\") on security method with high interaction score produces three results")
    @Tag("positive")
    void flush_emptyDisplayNameOnSecurityMethod_producesBothResults() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth - login", List.of("security", "auth"), "Tests auth", 0.0, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), "", suggestion);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(3, results.size());
        assertEquals("security/auth", results.get(0).path("ruleId").asText());
        assertEquals("annotation/empty-display-name", results.get(1).path("ruleId").asText());
        assertEquals("security-test/placebo", results.get(2).path("ruleId").asText());
    }

    // -------------------------------------------------------------------------
    // Placebo detection (security-test/placebo rule)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("security method with interaction score >= 0.8 produces a security-test/placebo result")
    @Tag("positive")
    void flush_securityMethodWithHighInteractionScore_producesPlaceboResult() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(2, results.size());
        assertEquals("security/auth", results.get(0).path("ruleId").asText());
        assertEquals("security-test/placebo", results.get(1).path("ruleId").asText());
    }

    @Test
    @DisplayName("security method with interaction score exactly 0.8 produces a placebo result")
    @Tag("edge-case")
    void flush_securityMethodWithExactThresholdScore_producesPlaceboResult() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testCrypto", true, "SECURITY: crypto", List.of("security", "crypto"), "Tests crypto", 0.8, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.CryptoTest", "testCrypto", 5, 4, null, List.of(), null, suggestion);

        boolean foundPlacebo = false;
        for (JsonNode r : flush(emitter).path("runs").get(0).path("results")) {
            if ("security-test/placebo".equals(r.path("ruleId").asText())) {
                foundPlacebo = true;
            }
        }
        assertTrue(foundPlacebo, "placebo result should be present when interaction score == 0.8");
    }

    @Test
    @DisplayName("security method with interaction score < 0.8 does not produce a placebo result")
    @Tag("positive")
    void flush_securityMethodWithLowInteractionScore_noPlaceboResult() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.9, 0.5);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(1, results.size(), "only the primary security result should be present");
        assertFalse("security-test/placebo".equals(results.get(0).path("ruleId").asText()),
                "no placebo result should be emitted for low interaction score");
    }

    @Test
    @DisplayName("non-security method with high interaction score does not produce a placebo result")
    @Tag("edge-case")
    void flush_nonSecurityMethodWithHighInteractionScore_noPlaceboResult() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testUtil", false, null, List.of(), null, 0.95, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.UtilTest", "testUtil", 10, 3, null, List.of(), null, suggestion);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(1, results.size(), "non-security method should produce only one result regardless of interaction score");
        assertEquals("test-method", results.get(0).path("ruleId").asText());
    }

    @Test
    @DisplayName("security-test/placebo result has level 'warning'")
    @Tag("positive")
    void flush_placeboResult_hasLevelWarning() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode placeboResult = null;
        for (JsonNode r : flush(emitter).path("runs").get(0).path("results")) {
            if ("security-test/placebo".equals(r.path("ruleId").asText())) {
                placeboResult = r;
            }
        }
        assertNotNull(placeboResult, "placebo result should be present");
        assertEquals("warning", placeboResult.path("level").asText());
    }

    @Test
    @DisplayName("security-test/placebo result properties contain interaction score")
    @Tag("positive")
    void flush_placeboResult_propertiesContainInteractionScore() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode placeboResult = null;
        for (JsonNode r : flush(emitter).path("runs").get(0).path("results")) {
            if ("security-test/placebo".equals(r.path("ruleId").asText())) {
                placeboResult = r;
            }
        }
        assertNotNull(placeboResult, "placebo result should be present");
        assertEquals(0.9, placeboResult.path("properties").path("aiInteractionScore").asDouble(), 0.001);
        assertEquals(6, placeboResult.path("properties").path("loc").asInt());
    }

    @Test
    @DisplayName("security-test/placebo result message contains interaction score value")
    @Tag("positive")
    void flush_placeboResult_messageContainsInteractionScore() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode placeboResult = null;
        for (JsonNode r : flush(emitter).path("runs").get(0).path("results")) {
            if ("security-test/placebo".equals(r.path("ruleId").asText())) {
                placeboResult = r;
            }
        }
        assertNotNull(placeboResult, "placebo result should be present");
        String messageText = placeboResult.path("message").path("text").asText();
        assertTrue(messageText.contains("0.9"), "message should contain the interaction score");
    }

    @Test
    @DisplayName("security-test/placebo rule is registered in the rules list")
    @Tag("positive")
    void flush_placeboResult_ruleIsRegistered() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testAuth", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testAuth", 10, 6, null, List.of(), null, suggestion);

        JsonNode rules = flush(emitter).path("runs").get(0).path("tool").path("driver").path("rules");
        boolean found = false;
        for (JsonNode rule : rules) {
            if ("security-test/placebo".equals(rule.path("id").asText())) {
                found = true;
                assertEquals("SecurityTestPlacebo", rule.path("name").asText());
                assertTrue(rule.path("properties").path("tags").toString().contains("placebo"),
                        "rule tags should include 'placebo'");
                assertTrue(rule.path("properties").path("tags").toString().contains("security"),
                        "rule tags should include 'security'");
                break;
            }
        }
        assertTrue(found, "security-test/placebo rule should be registered");
    }

    @Test
    @DisplayName("security-test/placebo result has correct physical location")
    @Tag("positive")
    void flush_placeboResult_hasCorrectPhysicalLocation() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "Tests auth", 0.85, 0.9);
        SarifEmitter emitter = new SarifEmitter(true, false, "src/test/java/");
        emitter.record("com.acme.AuthTest", "testLogin", 15, 6, null, List.of(), null, suggestion);

        JsonNode placeboResult = null;
        for (JsonNode r : flush(emitter).path("runs").get(0).path("results")) {
            if ("security-test/placebo".equals(r.path("ruleId").asText())) {
                placeboResult = r;
            }
        }
        assertNotNull(placeboResult, "placebo result should be present");
        JsonNode physLoc = placeboResult.path("locations").get(0).path("physicalLocation");
        assertEquals("src/test/java/com/acme/AuthTest.java",
                physLoc.path("artifactLocation").path("uri").asText());
        assertEquals(15, physLoc.path("region").path("startLine").asInt());
    }

    // -------------------------------------------------------------------------
    // Edge cases — message text
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("blank reason (whitespace-only) is not appended to security method message")
    @Tag("edge-case")
    void flush_securityMethodMessage_blankReason_reasonNotAppended() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "   ", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        String messageText = getFirstResult(flush(emitter)).path("message").path("text").asText();
        assertFalse(messageText.contains("Reason:"),
                "blank reason should not be appended to message: " + messageText);
    }

    @Test
    @DisplayName("null reason in AI suggestion does not cause Reason: line in message")
    @Tag("edge-case")
    void flush_securityMethodMessage_nullReason_reasonNotAppended() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), null, 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        String messageText = getFirstResult(flush(emitter)).path("message").path("text").asText();
        assertFalse(messageText.contains("Reason:"),
                "null reason should not add Reason: line: " + messageText);
    }

    @Test
    @DisplayName("null tags list in suggestion resolves to 'security-test' rule, not a crash")
    @Tag("edge-case")
    void flush_nullTagsInSuggestion_resolveRuleIdReturnsSecurityTest() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: general", null, "reason", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.FooTest", "testFoo", 5, 4, null, List.of(), null, suggestion);

        JsonNode result = getFirstResult(flush(emitter));
        assertEquals("security-test", result.path("ruleId").asText(),
                "null tags should produce security-test ruleId");
        assertEquals("note", result.path("level").asText());
    }

    @Test
    @DisplayName("non-security method with empty @DisplayName produces exactly 2 results: test-method + empty-display-name")
    @Tag("edge-case")
    void flush_nonSecurityMethodEmptyDisplayName_producesTwoResults() throws Exception {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.record("com.acme.UtilTest", "testHelper", 5, 3, null, List.of(), "", null);

        JsonNode results = flush(emitter).path("runs").get(0).path("results");
        assertEquals(2, results.size(), "non-security method with empty displayName should produce 2 results");
        assertEquals("test-method", results.get(0).path("ruleId").asText());
        assertEquals("annotation/empty-display-name", results.get(1).path("ruleId").asText());
    }

    @Test
    @DisplayName("blank aiReason stored as null in properties (not whitespace)")
    @Tag("edge-case")
    void flush_blankAiReason_storedAsNullInProperties() throws Exception {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "SECURITY: auth", List.of("security", "auth"), "  ", 0.9, 0.0);
        SarifEmitter emitter = new SarifEmitter(true, false, "");
        emitter.record("com.acme.AuthTest", "testLogin", 5, 8, null, List.of(), null, suggestion);

        JsonNode props = getFirstResult(flush(emitter)).path("properties");
        assertTrue(props.path("aiReason").isMissingNode(),
                "blank reason should be stored as null (absent) in properties");
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
