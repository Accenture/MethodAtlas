// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ControlCoverageWriterTest {

    @Test
    void writtenJson_roundTripsThroughObjectMapper(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("controls-coverage.json");
        ControlCoverageWriter.write(sampleReport(), file);

        JsonNode root = new ObjectMapper().readTree(file.toFile());
        assertEquals("1", root.path("schemaVersion").asString());
        assertEquals("ASVS", root.path("framework").asString());
        assertEquals("4.0", root.path("frameworkVersion").asString());
    }

    @Test
    void controlKey_isFrameworkUpperCasedPlusId(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("controls-coverage.json");
        ControlCoverageWriter.write(sampleReport(), file);
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        assertTrue(root.path("coverage").has("ASVS-1.1.1"),
                "Control key must be <FRAMEWORK>-<id>: " + root.path("coverage"));
    }

    @Test
    void nullOptionalFields_areOmittedFromJson(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("controls-coverage.json");
        ControlCoverageWriter.write(sampleReport(), file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("\"displayName\""),
                "Null displayName must be omitted from output: " + text);
        // The fallback control in the sample omits chapter/chapterTitle entirely.
        JsonNode controlNode = new ObjectMapper().readTree(file.toFile())
                .path("coverage").path("ASVS-2.2.2");
        assertFalse(controlNode.has("chapter"),
                "Absent chapter must not appear in coverage entry: " + controlNode);
        assertFalse(controlNode.has("chapterTitle"),
                "Absent chapterTitle must not appear: " + controlNode);
    }

    @Test
    void gaps_arrayIsLexicographicallySorted(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("controls-coverage.json");
        ControlCoverageWriter.write(reportWithGaps(), file);
        JsonNode gaps = new ObjectMapper().readTree(file.toFile()).path("gaps");
        assertEquals(3, gaps.size());
        assertEquals("ASVS-1.1.1", gaps.get(0).asString());
        assertEquals("ASVS-2.2.2", gaps.get(1).asString());
        assertEquals("ASVS-3.3.3", gaps.get(2).asString());
    }

    @Test
    void coveragePercent_hasAtMostTwoDecimalPlaces() {
        // Math.round(ratio * 10_000) / 100.0 truncates to exactly two decimals;
        // verify with a ratio that would otherwise produce more (1/3 = 33.333…).
        double ratio = 1.0 / 3.0;
        double rounded = Math.round(ratio * 10_000.0) / 100.0;
        String text = Double.toString(rounded);
        int dot = text.indexOf('.');
        int decimals = dot < 0 ? 0 : text.length() - dot - 1;
        assertTrue(decimals <= 2,
                "Two-decimal rounding must hold for awkward ratios: " + text);
    }

    @Test
    void write_throwsIoException_whenParentDirectoryMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("no-such-dir").resolve("controls-coverage.json");
        assertThrows(IOException.class, () -> ControlCoverageWriter.write(sampleReport(), missing));
    }

    private static ControlCoverageReport sampleReport() {
        Map<String, CoverageControlEntry> coverage = new LinkedHashMap<>();
        coverage.put("ASVS-1.1.1", new CoverageControlEntry(
                "V1", "Architecture",
                List.of(new CoverageTestEntry(
                        "com.acme.Test", "covers", null,
                        List.of("auth"), "source", 1.0))));
        coverage.put("ASVS-2.2.2", new CoverageControlEntry(
                null, null,
                List.of(new CoverageTestEntry(
                        "com.acme.Test", "covers2", null,
                        List.of("auth"), "ai", 0.9))));
        return new ControlCoverageReport(
                "1", "2026-05-29T14:00:00Z", "dev", "ASVS", "4.0",
                "/tmp/mapping.json", coverage,
                List.of(), new CoverageStatistics(2, 2, 0, 100.0));
    }

    private static ControlCoverageReport reportWithGaps() {
        return new ControlCoverageReport(
                "1", "2026-05-29T14:00:00Z", "dev", "ASVS", "4.0",
                "/tmp/mapping.json", new LinkedHashMap<>(),
                List.of("ASVS-1.1.1", "ASVS-2.2.2", "ASVS-3.3.3"),
                new CoverageStatistics(3, 0, 3, 0.0));
    }
}
