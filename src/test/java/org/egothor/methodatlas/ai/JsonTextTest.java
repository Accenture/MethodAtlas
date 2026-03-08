package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JsonTextTest {

    @Test
    void extractFirstJsonObject_returnsJsonWhenInputIsExactlyJson() throws Exception {
        String json = "{\"className\":\"AccessControlServiceTest\",\"methods\":[]}";

        String extracted = JsonText.extractFirstJsonObject(json);

        assertEquals(json, extracted);
    }

    @Test
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
    void extractFirstJsonObject_nullInput_throwsAiSuggestionException() {
        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(null));

        assertEquals("Model returned an empty response", ex.getMessage());
    }

    @Test
    void extractFirstJsonObject_blankInput_throwsAiSuggestionException() {
        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject("   \n\t  "));

        assertEquals("Model returned an empty response", ex.getMessage());
    }

    @Test
    void extractFirstJsonObject_missingOpeningBrace_throwsAiSuggestionException() {
        String text = "No JSON object here at all";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }

    @Test
    void extractFirstJsonObject_missingClosingBrace_throwsAiSuggestionException() {
        String text = "{\"className\":\"AccessControlServiceTest\"";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }

    @Test
    void extractFirstJsonObject_closingBraceBeforeOpeningBrace_throwsAiSuggestionException() {
        String text = "} not json {";

        AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                () -> JsonText.extractFirstJsonObject(text));

        assertEquals("Model response does not contain a JSON object: " + text, ex.getMessage());
    }
}
