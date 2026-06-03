// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestWriterTest {

    private static final Pattern DIGEST_LINE = Pattern.compile("^[0-9a-f]{64} {2}.+$");

    @Test
    void writeListsFilesLexicographicallyAndComputesSha256(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.txt"), "beta", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("c.txt"), "gamma", StandardCharsets.UTF_8);

        Path manifest = dir.resolve("manifest.sha256");
        ManifestWriter.write(dir, manifest);

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), () -> "Expected 3 lines, got: " + lines);
        assertTrue(lines.get(0).endsWith("  a.txt"), lines.get(0));
        assertTrue(lines.get(1).endsWith("  b.txt"), lines.get(1));
        assertTrue(lines.get(2).endsWith("  c.txt"), lines.get(2));
        for (String line : lines) {
            assertTrue(DIGEST_LINE.matcher(line).matches(),
                    () -> "Each line must be '<64-hex>  <filename>': " + line);
        }

        assertEquals(sha256Hex("alpha"), lines.get(0).substring(0, 64));
        assertEquals(sha256Hex("beta"), lines.get(1).substring(0, 64));
        assertEquals(sha256Hex("gamma"), lines.get(2).substring(0, 64));
    }

    @Test
    void writeExcludesManifestAndSignedSiblings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.sha256.signed"), "fake", StandardCharsets.UTF_8);

        Path manifest = dir.resolve("manifest.sha256");
        Files.writeString(manifest, "pre-existing", StandardCharsets.UTF_8);
        ManifestWriter.write(dir, manifest);

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), () -> "Only a.txt should be included: " + lines);
        assertTrue(lines.get(0).endsWith("  a.txt"));
        assertFalse(lines.get(0).contains("manifest.sha256"));
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
