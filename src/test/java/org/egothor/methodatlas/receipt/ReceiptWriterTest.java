// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link ReceiptWriter}.
 */
class ReceiptWriterTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void writtenJsonRoundTripsThroughObjectMapper(@TempDir Path tempDir) throws IOException {
        ReproducibilityReceipt receipt = sample();
        Path file = tempDir.resolve("receipt.json");
        ReceiptWriter.write(receipt, mapper, file);

        JsonNode root = mapper.readTree(file.toFile());
        assertEquals("1", root.path("schemaVersion").asText());
        assertEquals("CSV", root.path("outputMode").asText());
        assertEquals("dev", root.path("methodAtlasVersion").asText());
        assertTrue(root.path("deterministicReplay").asBoolean());
        assertEquals(64, root.path("configHash").asText().length());
    }

    @Test
    void nullOptionalFieldsAreOmittedFromOutput(@TempDir Path tempDir) throws IOException {
        ReproducibilityReceipt receipt = sample();
        Path file = tempDir.resolve("receipt.json");
        ReceiptWriter.write(receipt, mapper, file);

        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(contents.contains("\"taxonomyFile\""),
                "taxonomyFile must be omitted when null");
        assertFalse(contents.contains("\"overrideFile\""),
                "overrideFile must be omitted when null");
        assertFalse(contents.contains("\"aiCacheFile\""),
                "aiCacheFile must be omitted when null");
        assertFalse(contents.contains("\"aiProvider\""),
                "aiProvider must be omitted when null");
    }

    @Test
    void outputIsIndented(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("receipt.json");
        ReceiptWriter.write(sample(), mapper, file);
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(contents.contains("\n"), "Indented JSON must span multiple lines");
    }

    @Test
    void writeThrowsIoExceptionWhenParentDirectoryMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("no-such-dir").resolve("receipt.json");
        assertThrows(IOException.class,
                () -> ReceiptWriter.write(sample(), mapper, missing),
                "Missing parent directory must surface as IOException");
    }

    private static ReproducibilityReceipt sample() {
        return new ReproducibilityReceipt(
                "1",
                "2026-05-29T14:03:11.042Z",
                "dev",
                "21.0.2",
                "CSV",
                List.of("/tmp/scan"),
                true,
                new ReceiptInputs(null, "DEFAULT", null, null, null, null, null),
                "0".repeat(64));
    }
}
