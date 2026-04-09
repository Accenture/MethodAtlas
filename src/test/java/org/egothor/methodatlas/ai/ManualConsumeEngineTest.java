package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualConsumeEngineTest {

    private static final String FQCN = "com.acme.security.AccessControlServiceTest";

    @Test
    void suggestForClass_returnsEmptySuggestionWhenResponseFileAbsent(@TempDir Path responseDir) throws Exception {
        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);

        AiClassSuggestion suggestion = engine.suggestForClass(FQCN, "class AccessControlServiceTest {}",
                List.of(new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1)));

        assertNotNull(suggestion);
        assertEquals(FQCN, suggestion.className());
        assertNull(suggestion.classSecurityRelevant());
        assertEquals(List.of(), suggestion.classTags());
        assertNull(suggestion.classReason());
        assertEquals(List.of(), suggestion.methods());
    }

    @Test
    void suggestForClass_parsesValidJsonResponseFile(@TempDir Path responseDir) throws Exception {
        String responseJson = """
                {
                  "className": "com.acme.security.AccessControlServiceTest",
                  "classSecurityRelevant": true,
                  "classTags": ["security", "access-control"],
                  "classReason": "Class validates access control rules.",
                  "methods": [
                    {
                      "methodName": "shouldAllowOwner",
                      "securityRelevant": true,
                      "displayName": "SECURITY: access-control - owner can read own data",
                      "tags": ["security", "access-control"],
                      "reason": "Validates owner access."
                    }
                  ]
                }
                """;
        Files.writeString(responseDir.resolve(FQCN + ".response.txt"), responseJson, StandardCharsets.UTF_8);

        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);
        AiClassSuggestion suggestion = engine.suggestForClass(FQCN, "class AccessControlServiceTest {}",
                List.of(new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1)));

        assertEquals(FQCN, suggestion.className());
        assertEquals(Boolean.TRUE, suggestion.classSecurityRelevant());
        assertEquals(List.of("security", "access-control"), suggestion.classTags());
        assertEquals("Class validates access control rules.", suggestion.classReason());

        assertEquals(1, suggestion.methods().size());
        AiMethodSuggestion method = suggestion.methods().get(0);
        assertEquals("shouldAllowOwner", method.methodName());
        assertTrue(method.securityRelevant());
        assertEquals("SECURITY: access-control - owner can read own data", method.displayName());
        assertEquals(List.of("security", "access-control"), method.tags());
        assertEquals("Validates owner access.", method.reason());
    }

    @Test
    void suggestForClass_extractsJsonFromWrappedResponse(@TempDir Path responseDir) throws Exception {
        // Simulate an AI response that contains prose around the JSON object
        String responseWithProse = """
                Sure! Here is the security classification for the class:

                {
                  "className": "com.acme.security.AccessControlServiceTest",
                  "classSecurityRelevant": false,
                  "classTags": [],
                  "classReason": "Not security relevant.",
                  "methods": []
                }

                Let me know if you need anything else!
                """;
        Files.writeString(responseDir.resolve(FQCN + ".response.txt"), responseWithProse, StandardCharsets.UTF_8);

        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);
        AiClassSuggestion suggestion = engine.suggestForClass(FQCN, "class AccessControlServiceTest {}",
                List.of(new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1)));

        assertEquals(Boolean.FALSE, suggestion.classSecurityRelevant());
        assertEquals("Not security relevant.", suggestion.classReason());
        assertEquals(List.of(), suggestion.methods());
    }

    @Test
    void suggestForClass_normalizesNullMethodsAndTags(@TempDir Path responseDir) throws Exception {
        String responseJson = """
                {
                  "className": "com.acme.security.AccessControlServiceTest",
                  "classSecurityRelevant": true,
                  "classTags": null,
                  "classReason": "Has security methods.",
                  "methods": [
                    null,
                    {
                      "methodName": "shouldAllowOwner",
                      "securityRelevant": true,
                      "displayName": "SECURITY: access-control - owner access",
                      "tags": null,
                      "reason": "Validates owner."
                    },
                    {
                      "methodName": "",
                      "securityRelevant": false,
                      "displayName": null,
                      "tags": [],
                      "reason": "blank method name — must be filtered"
                    }
                  ]
                }
                """;
        Files.writeString(responseDir.resolve(FQCN + ".response.txt"), responseJson, StandardCharsets.UTF_8);

        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);
        AiClassSuggestion suggestion = engine.suggestForClass(FQCN, "",
                List.of(new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1)));

        // null classTags normalized to empty list
        assertEquals(List.of(), suggestion.classTags());

        // null entry and blank-name entry both filtered; one valid method remains
        assertEquals(1, suggestion.methods().size());
        AiMethodSuggestion method = suggestion.methods().get(0);
        assertEquals("shouldAllowOwner", method.methodName());
        // null tags normalized to empty list
        assertEquals(List.of(), method.tags());
    }

    @Test
    void suggestForClass_throwsWhenResponseFileContainsNoJson(@TempDir Path responseDir) throws Exception {
        Files.writeString(responseDir.resolve(FQCN + ".response.txt"),
                "The model refused to return JSON.", StandardCharsets.UTF_8);

        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);

        assertThrows(AiSuggestionException.class,
                () -> engine.suggestForClass(FQCN, "", List.of(new PromptBuilder.TargetMethod("m", 1, 1))));
    }

    @Test
    void suggestForClass_ignoresClassSourceParameter(@TempDir Path responseDir) throws Exception {
        // Response file present but classSource is empty — engine should not care
        String responseJson = """
                {"className":"%s","classSecurityRelevant":false,"classTags":[],"classReason":"","methods":[]}
                """.formatted(FQCN);
        Files.writeString(responseDir.resolve(FQCN + ".response.txt"), responseJson, StandardCharsets.UTF_8);

        ManualConsumeEngine engine = new ManualConsumeEngine(responseDir);
        AiClassSuggestion suggestion = engine.suggestForClass(FQCN, "" /* ignored */,
                List.of(new PromptBuilder.TargetMethod("m", 1, 1)));

        assertFalse(suggestion.classSecurityRelevant());
    }
}
