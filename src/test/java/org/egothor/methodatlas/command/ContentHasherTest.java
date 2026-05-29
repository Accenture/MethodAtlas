// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ContentHasher}.
 *
 * <p>
 * Two responsibilities are exercised:
 * </p>
 * <ul>
 *   <li>{@link ContentHasher#hashClass(String)} — verified to produce a stable
 *       64-character lowercase hex SHA-256 digest, to differ when the input
 *       differs by a single character, and to match the published SHA-256
 *       digest of the empty string.</li>
 *   <li>{@link ContentHasher#filePrefix(List)} — verified to return the empty
 *       string for an empty list, end with a forward slash for any
 *       non-empty root, and never include a backslash on any platform.</li>
 * </ul>
 *
 * @since 1.0.0
 */
class ContentHasherTest {

    // ── hashClass ────────────────────────────────────────────────────────────

    @Test
    void hashClass_emptyString_matchesPublishedSha256Digest() {
        // SHA-256("") is a well-known fixed value; using it as the oracle
        // documents the algorithm under test and catches any accidental
        // change to the input encoding or digest construction.
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentHasher.hashClass(""));
    }

    @Test
    void hashClass_returnsLowercaseHex64Characters() {
        String hash = ContentHasher.hashClass("class Sample { void m() {} }");
        assertEquals(64, hash.length(), "SHA-256 hex digest is 64 characters");
        assertTrue(hash.matches("[0-9a-f]{64}"),
                "Digest must be lowercase hexadecimal");
    }

    @Test
    void hashClass_singleCharacterDifference_producesDifferentHash() {
        String a = ContentHasher.hashClass("class Sample { void m() {} }");
        String b = ContentHasher.hashClass("class Sample { void M() {} }");
        assertNotEquals(a, b,
                "Hashes for inputs differing in a single character must differ");
    }

    @Test
    void hashClass_deterministicAcrossInvocations() {
        String input = "class Sample { void m() { return; } }";
        assertEquals(
                ContentHasher.hashClass(input),
                ContentHasher.hashClass(input),
                "hashClass must be deterministic for the same input");
    }

    // ── filePrefix ───────────────────────────────────────────────────────────

    @Test
    void filePrefix_emptyList_returnsEmptyString() {
        assertEquals("", ContentHasher.filePrefix(List.of()));
    }

    @Test
    void filePrefix_relativeRoot_endsWithSlash(@TempDir Path tempDir) {
        String prefix = ContentHasher.filePrefix(List.of(tempDir));
        assertTrue(prefix.endsWith("/"), "Non-empty prefix must end with a forward slash");
    }

    @Test
    void filePrefix_usesForwardSlashesOnAllPlatforms(@TempDir Path tempDir) {
        String prefix = ContentHasher.filePrefix(List.of(tempDir));
        assertFalse(prefix.contains("\\"),
                "Prefix must use forward slashes regardless of host OS");
    }

    // ── hashFile ─────────────────────────────────────────────────────────────

    @Test
    void hashFile_matchesHashClassForSameUtf8Content(@TempDir Path tempDir) throws IOException {
        String content = "MethodAtlas reproducibility content\n";
        Path file = tempDir.resolve("input.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertEquals(ContentHasher.hashClass(content), ContentHasher.hashFile(file),
                "hashFile and hashClass must agree for identical UTF-8 byte sequences");
    }

    @Test
    void hashFile_isDeterministicAcrossCalls(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("stable.txt");
        Files.writeString(file, "alpha-beta-gamma", StandardCharsets.UTF_8);
        assertEquals(ContentHasher.hashFile(file), ContentHasher.hashFile(file),
                "Two calls on the same file must return equal digests");
    }

    @Test
    void hashFile_throwsIoExceptionOnMissingFile(@TempDir Path tempDir) {
        Path absent = tempDir.resolve("does-not-exist.txt");
        assertThrows(IOException.class, () -> ContentHasher.hashFile(absent),
                "Missing files must surface as IOException, not silently succeed");
    }

    @Test
    void hashFile_emptyFileMatchesPublishedShaOfEmptyInput(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentHasher.hashFile(file),
                "Empty file must match the canonical SHA-256 of an empty byte string");
    }
}
