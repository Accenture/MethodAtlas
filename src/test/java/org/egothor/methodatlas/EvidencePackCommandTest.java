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
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration-style tests for the {@code -evidence-pack} command. The tests
 * drive the public CLI entry point so they exercise argument parsing, the
 * dispatch path, and the full pack-writing pipeline end-to-end. Signing is
 * intentionally left unsigned to keep the test self-contained: the spec
 * delegates signed-path coverage to
 * {@code org.egothor.methodatlas.evidence.ZeroEchoSignerTest}.
 */
class EvidencePackCommandTest {

    @Test
    void writesExpectedArtefactsForUnsignedAsvsPack(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path packDir = tempDir.resolve("pack-asvs");
        int exit = runApp(new String[] {
                "-evidence-pack", "ASVS",
                "-evidence-pack-dir", packDir.toString(),
                sourceDir.toString()
        });
        assertEquals(0, exit, "command must exit with 0 on success");

        assertTrue(Files.isRegularFile(packDir.resolve("findings.sarif")), "findings.sarif missing");
        assertTrue(Files.isRegularFile(packDir.resolve("findings.csv")), "findings.csv missing");
        assertTrue(Files.isRegularFile(packDir.resolve("pack-meta.json")), "pack-meta.json missing");
        assertTrue(Files.isRegularFile(packDir.resolve("manifest.sha256")), "manifest.sha256 missing");
        assertFalse(Files.exists(packDir.resolve("manifest.sha256.signed")),
                "manifest.sha256.signed must be absent when no keystore was supplied");
        assertFalse(Files.exists(packDir.resolve("overrides.yaml")),
                "overrides.yaml must be absent when no override file was supplied");
        assertFalse(Files.exists(packDir.resolve("ai-responses.jsonl")),
                "ai-responses.jsonl must be absent when AI is disabled");
    }

    @Test
    void signsManifestWithGeneratedEd25519Keyring(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path keyring = tempDir.resolve("audit-keyring.txt");
        assertEquals(0, runApp(new String[] {
                "-gen-signing-key", keyring.toString(),
                "-key-alias", "audit"
        }), "key generation must succeed");

        Path packDir = tempDir.resolve("pack-signed");
        int exit = runApp(new String[] {
                "-evidence-pack", "ASVS",
                "-evidence-pack-dir", packDir.toString(),
                "-evidence-pack-keyring", keyring.toString(),
                "-evidence-pack-key-alias", "audit",
                sourceDir.toString()
        });
        assertEquals(0, exit, "signed pack must exit 0");

        assertTrue(Files.isRegularFile(packDir.resolve("manifest.sha256.signed")),
                "manifest.sha256.signed must be present when a keyring is supplied");
        assertFalse(Files.exists(packDir.resolve(keyring.getFileName().toString())),
                "the private keyring must never be copied into the distributed pack");

        JsonNode meta = new ObjectMapper().readTree(packDir.resolve("pack-meta.json").toFile());
        assertTrue(meta.path("signed").asBoolean(), "signed flag must be true");
        assertEquals("Ed25519", meta.path("signatureAlgorithm").asString(),
                "algorithm must be derived from the generated Ed25519 key");
        assertEquals("1.1.1", meta.path("zeroEchoLibVersion").asString());
        assertEquals("audit", meta.path("keyAlias").asString());
    }

    @Test
    void signingFailureLeavesAConsistentUnsignedPack(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path keyring = tempDir.resolve("audit-keyring.txt");
        assertEquals(0, runApp(new String[] {
                "-gen-signing-key", keyring.toString(), "-key-alias", "audit"
        }), "key generation must succeed");

        // A valid key loads, so the signer is built, but the unsupported algorithm
        // makes the signing step itself fail — exercising the failure branch.
        Path packDir = tempDir.resolve("pack-signfail");
        int exit = runApp(new String[] {
                "-evidence-pack", "ASVS",
                "-evidence-pack-dir", packDir.toString(),
                "-evidence-pack-keyring", keyring.toString(),
                "-evidence-pack-key-alias", "audit",
                "-evidence-pack-sign-algo", "NOT-A-REAL-ALGORITHM",
                sourceDir.toString()
        });
        assertEquals(0, exit, "a signing failure must not fail the scan");

        assertFalse(Files.exists(packDir.resolve("manifest.sha256.signed")),
                "no signature envelope must be left behind when signing fails");

        JsonNode meta = new ObjectMapper().readTree(packDir.resolve("pack-meta.json").toFile());
        assertFalse(meta.path("signed").asBoolean(), "signed must be false after signing failed");

        // The integrity guarantee: the manifest's digest for pack-meta.json must
        // match the pack-meta.json actually on disk (no false tamper signal).
        String metaDigest = sha256Hex(packDir.resolve("pack-meta.json"));
        List<String> manifestLines = Files.readAllLines(packDir.resolve("manifest.sha256"),
                StandardCharsets.UTF_8);
        boolean consistent = manifestLines.stream()
                .anyMatch(line -> line.endsWith("pack-meta.json") && line.startsWith(metaDigest));
        assertTrue(consistent,
                "manifest digest for pack-meta.json must match the rewritten file on disk: " + manifestLines);
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    @Test
    void manifestHasOneLinePerArtefactWith64HexDigest(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path packDir = tempDir.resolve("pack-pci");
        runApp(new String[] {
                "-evidence-pack", "pci-6.4.1",
                "-evidence-pack-dir", packDir.toString(),
                sourceDir.toString()
        });

        List<String> lines = Files.readAllLines(packDir.resolve("manifest.sha256"), StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 3, () -> "Manifest should list every artefact, got: " + lines);
        for (String line : lines) {
            String digest = line.substring(0, 64);
            assertTrue(digest.matches("[0-9a-f]{64}"),
                    () -> "Each entry must start with a 64-char lowercase hex digest: " + line);
            assertTrue(line.substring(64).startsWith("  "), () -> "Two-space separator missing: " + line);
        }
    }

    @Test
    void packMetaJsonRecordsFrameworkAndUnsignedFlag(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path packDir = tempDir.resolve("pack-nist");
        runApp(new String[] {
                "-evidence-pack", "NIST-SSDF-PW.8",
                "-evidence-pack-dir", packDir.toString(),
                sourceDir.toString()
        });

        JsonNode meta = new ObjectMapper().readTree(packDir.resolve("pack-meta.json").toFile());
        assertEquals("NIST-SSDF-PW.8", meta.path("framework").asString());
        assertFalse(meta.path("signed").asBoolean(), "signed must be false when no keystore was supplied");
        assertTrue(meta.path("signatureAlgorithm").isNull(),
                "signatureAlgorithm must be null when unsigned");
        assertTrue(meta.path("zeroEchoLibVersion").isNull(),
                "zeroEchoLibVersion must be null when unsigned");
        assertTrue(meta.path("keyAlias").isNull(), "keyAlias must be null when unsigned");
        assertFalse(meta.path("generatedUtc").asString().isEmpty(),
                "generatedUtc must be populated");
        assertFalse(meta.path("scanRoots").isMissingNode(), "scanRoots must be present");
    }

    @Test
    void unknownFrameworkExitsWithCodeTwo(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        int exit = runApp(new String[] {
                "-evidence-pack", "HIPAA",
                "-evidence-pack-dir", tempDir.resolve("pack-bad").toString(),
                sourceDir.toString()
        });
        assertEquals(2, exit, "Unknown framework token must trigger exit code 2");
    }

    @Test
    void caseInsensitiveFrameworkTokenIsAccepted(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path packDir = tempDir.resolve("pack-iso");
        int exit = runApp(new String[] {
                "-evidence-pack", "iso-27001-8.29",
                "-evidence-pack-dir", packDir.toString(),
                sourceDir.toString()
        });
        assertEquals(0, exit);
        JsonNode meta = new ObjectMapper().readTree(packDir.resolve("pack-meta.json").toFile());
        assertEquals("ISO-27001-8.29", meta.path("framework").asString());
    }

    @Test
    void existingDirectoryWithoutOverwriteFlagIsAnError(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path packDir = tempDir.resolve("pack-existing");
        Files.createDirectories(packDir);

        IOException ex = assertThrowsIOException(() -> runApp(new String[] {
                "-evidence-pack", "ASVS",
                "-evidence-pack-dir", packDir.toString(),
                sourceDir.toString()
        }));
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("already exists"),
                "Error message should mention 'already exists': " + ex.getMessage());
    }

    @Test
    void overwriteFlagAllowsReuseOfExistingDirectory(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        Path packDir = tempDir.resolve("pack-reuse");
        Files.createDirectories(packDir);

        int exit = runApp(new String[] {
                "-evidence-pack", "ASVS",
                "-evidence-pack-dir", packDir.toString(),
                "-evidence-pack-overwrite",
                sourceDir.toString()
        });
        assertEquals(0, exit);
        assertTrue(Files.isRegularFile(packDir.resolve("findings.sarif")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static IOException assertThrowsIOException(ThrowingRunnable r) {
        try {
            r.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException e) {
            return e;
        } catch (Exception other) {
            throw new AssertionError("Expected IOException, got " + other.getClass(), other);
        }
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = EvidencePackCommandTest.class.getResourceAsStream(resourcePath)) {
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
