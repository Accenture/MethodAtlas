// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@code -emit-coverage} CLI flag.
 *
 * <p>
 * The tests drive {@link MethodAtlasApp#run(String[], PrintWriter)} so that
 * argument parsing, mapping load, fan-out, and report emission are all
 * exercised together against the reference template at
 * {@code docs/examples/asvs4-mapping.json}.
 * </p>
 */
class MethodAtlasAppCoverageTest {

    private static final Path REFERENCE_MAPPING = Path.of("docs", "examples", "asvs4-mapping.json");

    private PrintStream originalErr;

    @BeforeEach
    void captureStderr() {
        this.originalErr = System.err;
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void emitCoverage_withoutMapping_exitsWithCodeTwoAndPrintsHelpfulError(@TempDir Path tempDir)
            throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut, true, StandardCharsets.UTF_8));

        int exit = runApp(new String[] { "-emit-coverage", sourceDir.toString() });
        assertEquals(2, exit, "Missing -coverage-mapping must exit with code 2");
        String stderr = errOut.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("requires -coverage-mapping"),
                "stderr must explain the missing flag: " + stderr);
    }

    @Test
    void emitCoverage_withMissingMapping_exitsWithCodeTwo(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path missing = tempDir.resolve("absent.json");

        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut, true, StandardCharsets.UTF_8));

        int exit = runApp(new String[] {
                "-emit-coverage", "-coverage-mapping", missing.toString(), sourceDir.toString()
        });
        assertEquals(2, exit, "Missing mapping file must exit with code 2");
    }

    @Test
    void emitCoverage_withMalformedMapping_exitsWithCodeTwo(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path malformed = tempDir.resolve("bad.json");
        Files.writeString(malformed, "{ not json", StandardCharsets.UTF_8);

        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut, true, StandardCharsets.UTF_8));

        int exit = runApp(new String[] {
                "-emit-coverage", "-coverage-mapping", malformed.toString(), sourceDir.toString()
        });
        assertEquals(2, exit, "Malformed mapping must exit with code 2");
    }

    @Test
    void emitCoverage_writesReportToCustomPath(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        writeAccessControlFixture(sourceDir);
        Path coverageOut = tempDir.resolve("custom-coverage.json");

        int exit = runApp(new String[] {
                "-emit-coverage",
                "-coverage-mapping", REFERENCE_MAPPING.toAbsolutePath().toString(),
                "-coverage-file", coverageOut.toString(),
                sourceDir.toString()
        });

        assertEquals(0, exit);
        assertTrue(Files.isRegularFile(coverageOut),
                "Coverage report must be written to custom path");
        JsonNode root = new ObjectMapper().readTree(coverageOut.toFile());
        assertEquals("ASVS", root.path("framework").asText());
    }

    @Test
    void absentFlag_doesNotWriteCoverageReport(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path explicit = tempDir.resolve("must-not-be-written.json");

        // -coverage-file alone (without -emit-coverage) must not produce a file.
        runApp(new String[] {
                "-coverage-file", explicit.toString(), sourceDir.toString()
        });
        assertFalse(Files.exists(explicit),
                "Coverage report must NOT be written when -emit-coverage is absent");
    }

    @Test
    void accessControlAnnotation_mapsToAsvs4Dot1Dot1(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        writeAccessControlFixture(sourceDir);
        Path coverageOut = tempDir.resolve("coverage.json");

        runApp(new String[] {
                "-emit-coverage",
                "-coverage-mapping", REFERENCE_MAPPING.toAbsolutePath().toString(),
                "-coverage-file", coverageOut.toString(),
                sourceDir.toString()
        });

        JsonNode root = new ObjectMapper().readTree(coverageOut.toFile());
        JsonNode control = root.path("coverage").path("ASVS-4.1.1");
        assertFalse(control.isMissingNode(),
                "Annotated method must map to ASVS-4.1.1: coverage=" + root.path("coverage"));
        assertEquals("V4", control.path("chapter").asText());
        JsonNode tests = control.path("tests");
        assertTrue(tests.isArray() && tests.size() >= 1, "Must list at least one covering test");
        JsonNode testNode = tests.get(0);
        assertEquals("source", testNode.path("tagSource").asText(),
                "Source-derived evidence must yield tagSource = 'source'");
        assertEquals(1.0, testNode.path("confidence").asDouble(),
                "Source-derived evidence must yield confidence = 1.0");
    }

    @Test
    void coveragePercent_isInClosedZeroToHundredRange(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        writeAccessControlFixture(sourceDir);
        Path coverageOut = tempDir.resolve("coverage.json");

        runApp(new String[] {
                "-emit-coverage",
                "-coverage-mapping", REFERENCE_MAPPING.toAbsolutePath().toString(),
                "-coverage-file", coverageOut.toString(),
                sourceDir.toString()
        });

        double percent = new ObjectMapper().readTree(coverageOut.toFile())
                .path("statistics").path("coveragePercent").asDouble();
        assertTrue(percent >= 0.0 && percent <= 100.0,
                "coveragePercent must be in [0, 100]: " + percent);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void writeAccessControlFixture(Path destDir) throws IOException {
        Files.writeString(destDir.resolve("AccessCtrlSampleTest.java"), """
                package com.acme;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                class AccessCtrlSampleTest {
                    @Tag("access-control")
                    @Test
                    void denyAnonymousUserToProtectedResource() {
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppCoverageTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static int runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            return MethodAtlasApp.run(args, out);
        }
    }
}
