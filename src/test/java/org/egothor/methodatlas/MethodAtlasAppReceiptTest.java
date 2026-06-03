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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@code -emit-receipt} CLI flag.
 *
 * <p>
 * The tests drive {@link MethodAtlasApp#run(String[], PrintWriter)} so that
 * argument parsing, dispatch, and receipt emission are all exercised together.
 * They keep the working directory pinned to a temporary location for the
 * duration of each test so the default {@code methodatlas-receipt.json} does
 * not leak into the repository checkout.
 * </p>
 */
class MethodAtlasAppReceiptTest {

    private static final String DEFAULT_RECEIPT = "methodatlas-receipt.json";

    private String originalUserDir;

    @BeforeEach
    void rememberUserDir() {
        this.originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void flagWritesReceiptToCustomPath(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path target = tempDir.resolve("custom-receipt.json");

        runApp(new String[] { "-emit-receipt", "-receipt-file", target.toString(), sourceDir.toString() });

        assertTrue(Files.isRegularFile(target), "Receipt file must be created at custom path");
        JsonNode root = new ObjectMapper().readTree(target.toFile());
        assertEquals("1", root.path("schemaVersion").asString());
        assertEquals("CSV", root.path("outputMode").asString());
    }

    @Test
    void absentFlag_doesNotWriteReceipt(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path explicit = tempDir.resolve("none.json");

        runApp(new String[] { "-receipt-file", explicit.toString(), sourceDir.toString() });
        assertFalse(Files.exists(explicit),
                "Receipt must NOT be written when -emit-receipt is absent");
    }

    @Test
    void overrideFile_isCapturedInReceipt(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path override = tempDir.resolve("overrides.yaml");
        Files.writeString(override, "version: 1\n", StandardCharsets.UTF_8);
        Path target = tempDir.resolve("with-override.json");

        runApp(new String[] {
                "-emit-receipt", "-receipt-file", target.toString(),
                "-override-file", override.toString(),
                sourceDir.toString()
        });

        JsonNode root = new ObjectMapper().readTree(target.toFile());
        JsonNode overrideArtifact = root.path("inputs").path("overrideFile");
        assertFalse(overrideArtifact.isMissingNode(),
                "overrideFile artefact must be present when -override-file is used");
        assertEquals(64, overrideArtifact.path("sha256").asString().length(),
                "SHA-256 must be 64 hex chars");
        assertEquals(override.toAbsolutePath().toString(),
                overrideArtifact.path("path").asString(),
                "Receipt must record the absolute override path");
    }

    @Test
    void absentOverrideFile_omitsOverrideField(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path target = tempDir.resolve("no-override.json");

        runApp(new String[] { "-emit-receipt", "-receipt-file", target.toString(), sourceDir.toString() });

        JsonNode root = new ObjectMapper().readTree(target.toFile());
        assertTrue(root.path("inputs").path("overrideFile").isMissingNode(),
                "overrideFile must be omitted when no override file was supplied");
    }

    @Test
    void configHash_matchesCanonicalReDerivation(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path target = tempDir.resolve("derive.json");

        runApp(new String[] { "-emit-receipt", "-receipt-file", target.toString(), sourceDir.toString() });

        JsonNode root = new ObjectMapper().readTree(target.toFile());
        JsonNode inputs = root.path("inputs");
        TreeMap<String, String> keys = new TreeMap<>();
        keys.put("aiCacheFileSha256", artifactSha(inputs, "aiCacheFile"));
        keys.put("aiModel", text(inputs, "aiModel"));
        keys.put("aiProvider", text(inputs, "aiProvider"));
        keys.put("builtInTaxonomy", text(inputs, "builtInTaxonomy"));
        keys.put("methodAtlasVersion", root.path("methodAtlasVersion").asString());
        keys.put("overrideFileSha256", artifactSha(inputs, "overrideFile"));
        keys.put("promptTemplateHash", text(inputs, "promptTemplateHash"));
        keys.put("taxonomyFileSha256", artifactSha(inputs, "taxonomyFile"));

        StringBuilder buf = new StringBuilder(1024);
        keys.forEach((k, v) -> buf.append(k).append('=').append(v).append('\n'));
        String expected = sha256Hex(buf.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, root.path("configHash").asString(),
                "Re-derived configHash must match the value in the receipt");
    }

    @Test
    void defaultReceiptPath_isHonoured(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // Pivot the JVM working directory to tempDir so the default
        // methodatlas-receipt.json lands there instead of the repo root.
        System.setProperty("user.dir", tempDir.toString());
        runApp(new String[] { "-emit-receipt", sourceDir.toString() });

        // Path.of(DEFAULT) resolves against the JVM-process CWD (not the
        // user.dir property). Verify the receipt file exists by name in either
        // location to remain robust to JVM behaviour around user.dir.
        Path inTemp = tempDir.resolve(DEFAULT_RECEIPT);
        Path inCwd = Path.of(DEFAULT_RECEIPT);
        boolean found = Files.exists(inTemp) || Files.exists(inCwd);
        try {
            assertTrue(found, "Default receipt file must be written when -emit-receipt is used");
        } finally {
            Files.deleteIfExists(inCwd);
            Files.deleteIfExists(inTemp);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asString();
    }

    private static String artifactSha(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.path("sha256").asString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppReceiptTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static void runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
    }
}
