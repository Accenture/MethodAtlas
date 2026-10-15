// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TaxonomyVersion} and the built-in taxonomy version
 * accessors.
 *
 * @since 6.1.0
 */
@Tag("unit")
@Tag("taxonomy-version")
class TaxonomyVersionTest {

    /**
     * SHA-256 of {@link DefaultSecurityTaxonomy#text()} at revision 1. If this
     * assertion fails the taxonomy text changed: bump
     * {@code DefaultSecurityTaxonomy.REVISION} and update this digest together.
     */
    private static final String DEFAULT_TEXT_SHA256_REV1 =
            "0536962b0ced804d7444c8152b1cc2bc98e9d9e6744525851f600e4ab65adbbd";

    /**
     * SHA-256 of {@link OptimizedSecurityTaxonomy#text()} at revision 1. See
     * {@link #DEFAULT_TEXT_SHA256_REV1} for the update rule.
     */
    private static final String OPTIMIZED_TEXT_SHA256_REV1 =
            "49fa0da31a8da784b3fb1db6dbbd70be343de75f1a03d0df5ce8ac5e191cf24e";

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("built-in default version carries its revision")
    void builtInDefault_hasRevision() {
        assertEquals("built-in/default@" + DefaultSecurityTaxonomy.REVISION,
                TaxonomyVersion.builtIn(AiOptions.TaxonomyMode.DEFAULT));
        assertEquals(DefaultSecurityTaxonomy.version(), TaxonomyVersion.builtIn(AiOptions.TaxonomyMode.DEFAULT));
    }

    @Test
    @DisplayName("built-in optimized version carries its revision and differs from default")
    void builtInOptimized_hasRevision() {
        String v = TaxonomyVersion.builtIn(AiOptions.TaxonomyMode.OPTIMIZED);
        assertEquals("built-in/optimized@" + OptimizedSecurityTaxonomy.REVISION, v);
        assertNotEquals(TaxonomyVersion.builtIn(AiOptions.TaxonomyMode.DEFAULT), v);
    }

    @Test
    @DisplayName("built-in revision is pinned to the taxonomy text so edits force a bump")
    void builtInRevision_pinnedToText() throws NoSuchAlgorithmException {
        assertEquals(DEFAULT_TEXT_SHA256_REV1,
                sha256(DefaultSecurityTaxonomy.text().getBytes(StandardCharsets.UTF_8)),
                "default taxonomy text changed: bump DefaultSecurityTaxonomy.REVISION and re-pin");
        assertEquals(OPTIMIZED_TEXT_SHA256_REV1,
                sha256(OptimizedSecurityTaxonomy.text().getBytes(StandardCharsets.UTF_8)),
                "optimized taxonomy text changed: bump OptimizedSecurityTaxonomy.REVISION and re-pin");
    }

    @Test
    @DisplayName("custom file version is sha256 of the raw bytes")
    void ofFile_isSha256OfBytes(@TempDir Path dir) throws IOException, NoSuchAlgorithmException {
        Path file = dir.resolve("taxonomy.txt");
        byte[] content = "tags: auth, crypto\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        assertEquals("sha256:" + sha256(content), TaxonomyVersion.ofFile(file));
    }

    @Test
    @DisplayName("custom file version is stable for equal content and differs for changed content")
    void ofFile_stableAndSensitive(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.txt");
        Files.writeString(a, "same text");
        Files.writeString(b, "same text");

        String first = TaxonomyVersion.ofFile(a);
        assertEquals(first, TaxonomyVersion.ofFile(a));
        assertEquals(first, TaxonomyVersion.ofFile(b));

        Files.writeString(b, "same text.");
        assertNotEquals(first, TaxonomyVersion.ofFile(b));
        assertTrue(first.matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("ofFile fails for a missing file")
    void ofFile_missing_throws(@TempDir Path dir) {
        assertThrows(IOException.class, () -> TaxonomyVersion.ofFile(dir.resolve("absent.txt")));
    }

    @Test
    @DisplayName("resolve prefers the custom file over the built-in mode")
    void resolve_fileWinsOverMode(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("taxonomy.txt");
        Files.writeString(file, "custom");
        AiOptions withFile = AiOptions.builder().taxonomyFile(file)
                .taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED).build();
        AiOptions optimized = AiOptions.builder().taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED).build();
        AiOptions defaults = AiOptions.builder().build();

        assertEquals(TaxonomyVersion.ofFile(file), TaxonomyVersion.resolve(withFile));
        assertEquals(OptimizedSecurityTaxonomy.version(), TaxonomyVersion.resolve(optimized));
        assertEquals(DefaultSecurityTaxonomy.version(), TaxonomyVersion.resolve(defaults));
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullArguments_rejected() {
        assertThrows(NullPointerException.class, () -> TaxonomyVersion.builtIn(null));
        assertThrows(NullPointerException.class, () -> TaxonomyVersion.ofFile(null));
        assertThrows(NullPointerException.class, () -> TaxonomyVersion.resolve(null));
    }
}
