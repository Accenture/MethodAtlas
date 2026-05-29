// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlMappingTest {

    @Test
    void load_validMapping_populatesFieldsAndSource(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "tagToControls": {
                    "auth": [
                      { "id": "2.1.1", "chapter": "V2", "chapterTitle": "Authentication" }
                    ]
                  }
                }
                """);
        ControlMapping mapping = ControlMapping.load(file);
        assertEquals("ASVS", mapping.framework());
        assertEquals("4.0", mapping.frameworkVersion());
        assertEquals(file.toAbsolutePath().toString(), mapping.source());
        assertEquals(1, mapping.tagToControls().size());
        assertEquals("2.1.1", mapping.tagToControls().get("auth").get(0).id());
        assertEquals("V2", mapping.tagToControls().get("auth").get(0).chapter());
    }

    @Test
    void load_tagToControls_isUnmodifiable(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "tagToControls": {
                    "auth": [{ "id": "2.1.1" }]
                  }
                }
                """);
        ControlMapping mapping = ControlMapping.load(file);
        assertThrows(UnsupportedOperationException.class,
                () -> mapping.tagToControls().put("new", java.util.List.of()),
                "tagToControls map must be immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> mapping.tagToControls().get("auth").add(new ControlEntry("x", null, null)),
                "tagToControls value list must be immutable");
    }

    @Test
    void load_missingFile_throwsIoException(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.json");
        assertThrows(IOException.class, () -> ControlMapping.load(missing));
    }

    @Test
    void load_wrongSchemaVersion_throwsIllegalArgument(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "2",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "tagToControls": { "auth": [{ "id": "2.1.1" }] }
                }
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ControlMapping.load(file));
        assertTrue(ex.getMessage().contains("schemaVersion"),
                "Error must identify the failing constraint: " + ex.getMessage());
    }

    @Test
    void load_emptyTagToControls_throwsIllegalArgument(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "tagToControls": {}
                }
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ControlMapping.load(file));
        assertTrue(ex.getMessage().contains("tagToControls"),
                "Error must mention tagToControls: " + ex.getMessage());
    }

    @Test
    void load_blankId_throwsIllegalArgument(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "tagToControls": {
                    "auth": [{ "id": "  " }]
                  }
                }
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ControlMapping.load(file));
        assertTrue(ex.getMessage().contains("id"),
                "Error must mention the failing field: " + ex.getMessage());
    }

    @Test
    void load_unknownTopLevelFields_areSilentlyIgnored(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "4.0",
                  "_comment": "harmless",
                  "futureField": [1, 2, 3],
                  "tagToControls": {
                    "auth": [{ "id": "2.1.1" }]
                  }
                }
                """);
        ControlMapping mapping = ControlMapping.load(file);
        assertNotNull(mapping, "Unknown top-level fields must not cause failure");
        assertEquals(1, mapping.tagToControls().size());
    }

    @Test
    void load_blankFrameworkVersion_throwsIllegalArgument(@TempDir Path tempDir) throws IOException {
        Path file = writeMapping(tempDir, """
                {
                  "schemaVersion": "1",
                  "framework": "ASVS",
                  "frameworkVersion": "",
                  "tagToControls": { "auth": [{ "id": "2.1.1" }] }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> ControlMapping.load(file));
    }

    @Test
    void load_referenceTemplate_parsesCleanly() throws IOException {
        Path referenceTemplate = Path.of("docs", "examples", "asvs4-mapping.json");
        assertTrue(Files.exists(referenceTemplate),
                "Reference template must be committed at " + referenceTemplate);
        ControlMapping mapping = ControlMapping.load(referenceTemplate);
        assertEquals("ASVS", mapping.framework());
        assertEquals("4.0", mapping.frameworkVersion());
        assertTrue(mapping.tagToControls().containsKey("auth"));
        assertTrue(mapping.tagToControls().containsKey("crypto"));
    }

    private static Path writeMapping(Path dir, String json) throws IOException {
        Path file = dir.resolve("mapping.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
