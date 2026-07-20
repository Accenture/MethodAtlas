package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonText}.
 *
 * <p>
 * This class verifies that {@link JsonText#extractFirstJsonObject(String)}
 * correctly extracts JSON objects from raw model responses in various formats:
 * exact JSON, JSON wrapped in plain text, JSON wrapped in markdown code fences,
 * nested structures, and the minimal empty-object case. Error cases for null,
 * blank, and malformed inputs are also covered.
 * </p>
 */
@Tag("unit")
@Tag("json-text")
class JsonTextTest {

    @Test
    @DisplayName("extractFirstJsonObject returns the string unchanged when input is exactly a JSON object")
    @Tag("positive")
    void extractFirstJsonObject_returnsJsonWhenInputIsExactlyJson() throws Exception {
        String json = "{\"className\":\"AccessControlServiceTest\",\"methods\":[]}";

        String extracted = JsonText.extractFirstJsonObject(json);

        assertEquals(json, extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject extracts JSON object when wrapped by plain text")
    @Tag("positive")
    void extractFirstJsonObject_extractsJsonWrappedByPlainText() throws Exception {
        String text = """
                Here is the analysis result:
                {"className":"AccessControlServiceTest","methods":[{"methodName":"shouldRejectUnauthenticatedRequest"}]}
                Thank you.
                """;

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals(
                "{\"className\":\"AccessControlServiceTest\",\"methods\":[{\"methodName\":\"shouldRejectUnauthenticatedRequest\"}]}",
                extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject extracts JSON object when wrapped in a markdown code fence")
    @Tag("positive")
    void extractFirstJsonObject_extractsJsonWrappedByMarkdownFence() throws Exception {
        String text = """
                ```json
                {"className":"AuditLoggingTest","methods":[{"methodName":"shouldNotLogRawBearerToken"}]}
                ```
                """;

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals(
                "{\"className\":\"AuditLoggingTest\",\"methods\":[{\"methodName\":\"shouldNotLogRawBearerToken\"}]}",
                extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject preserves nested objects and arrays in the extracted JSON")
    @Tag("positive")
    void extractFirstJsonObject_preservesNestedObjectsAndArrays() throws Exception {
        String text = """
                Model output:
                {
                  "className":"PathTraversalValidationTest",
                  "methods":[
                    {
                      "methodName":"shouldRejectRelativePathTraversalSequence",
                      "securityRelevant":true,
                      "tags":["security","input-validation","path-traversal"]
                    }
                  ]
                }
                End.
                """;

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals("""
                {
                  "className":"PathTraversalValidationTest",
                  "methods":[
                    {
                      "methodName":"shouldRejectRelativePathTraversalSequence",
                      "securityRelevant":true,
                      "tags":["security","input-validation","path-traversal"]
                    }
                  ]
                }""", extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject throws AiSuggestionException for null input")
    @Tag("negative")
    @Tag("security")
    void extractFirstJsonObject_nullInput_throwsAiSuggestionException() {
        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(null));

        assertEquals("Model returned an empty response", ex.getMessage());
    }

    @Test
    @DisplayName("extractFirstJsonObject throws AiSuggestionException for blank input")
    @Tag("negative")
    @Tag("security")
    void extractFirstJsonObject_blankInput_throwsAiSuggestionException() {
        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject("   \n\t  "));

        assertEquals("Model returned an empty response", ex.getMessage());
    }

    @Test
    @DisplayName("extractFirstJsonObject throws AiSuggestionException when input has no opening brace")
    @Tag("negative")
    @Tag("security")
    void extractFirstJsonObject_missingOpeningBrace_throwsAiSuggestionException() {
        String text = "No JSON object here at all";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }

    @Test
    @DisplayName("extractFirstJsonObject throws AiSuggestionException when input has no closing brace")
    @Tag("negative")
    @Tag("security")
    void extractFirstJsonObject_missingClosingBrace_throwsAiSuggestionException() {
        String text = "{\"className\":\"AccessControlServiceTest\"";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }

    @Test
    @DisplayName("extractFirstJsonObject throws AiSuggestionException when closing brace appears before opening brace")
    @Tag("negative")
    @Tag("security")
    void extractFirstJsonObject_closingBraceBeforeOpeningBrace_throwsAiSuggestionException() {
        String text = "} not json {";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }

    @Test
    @DisplayName("extractFirstJsonObject returns '{}' for minimal valid JSON object input")
    @Tag("edge-case")
    void extractFirstJsonObject_minimalValidObject_returnsItself() throws Exception {
        String extracted = JsonText.extractFirstJsonObject("{}");
        assertEquals("{}", extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject stops at the first complete object, ignoring trailing commentary")
    @Tag("positive")
    void extractFirstJsonObject_trailingComment_returnsOnlyFirstObject() throws Exception {
        String text = "{\"className\":\"AuthTest\",\"methods\":[]}  // done, hope this helps!";

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals("{\"className\":\"AuthTest\",\"methods\":[]}", extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject returns the first of two concatenated objects")
    @Tag("positive")
    void extractFirstJsonObject_twoConcatenatedObjects_returnsFirst() throws Exception {
        String text = "{\"a\":1}{\"b\":2}";

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals("{\"a\":1}", extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject ignores braces that appear inside string values")
    @Tag("positive")
    @Tag("security")
    void extractFirstJsonObject_bracesInsideStringValue_areNotStructural() throws Exception {
        String text = "prefix {\"note\":\"a } here and a { there\",\"ok\":true} suffix";

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals("{\"note\":\"a } here and a { there\",\"ok\":true}", extracted);
    }

    @Test
    @DisplayName("extractFirstJsonObject honours escaped quotes inside string values")
    @Tag("positive")
    @Tag("security")
    void extractFirstJsonObject_escapedQuoteInsideString_isHonoured() throws Exception {
        String text = "{\"note\":\"he said \\\"hi\\\" }\",\"ok\":true}";

        String extracted = JsonText.extractFirstJsonObject(text);

        assertEquals(text, extracted);
    }
}
