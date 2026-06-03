// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zeroecho.core.storage.KeyringStore;

/**
 * Tests for {@link GenSigningKeyCommand}, the {@code -gen-signing-key} CLI mode.
 */
class GenSigningKeyCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesDefaultKeyringWithDefaultAlias() throws Exception {
        Path keyring = tempDir.resolve("ring.txt");
        StringBuilder out = new StringBuilder(256);

        int exit = run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        assertEquals(0, exit, "default generation must succeed");
        assertTrue(Files.isRegularFile(keyring), "keyring file must be created");
        assertNotNull(KeyringStore.load(keyring).getPrivate("methodatlas-signing"),
                "default alias must materialise");
        assertTrue(out.toString().contains("Ed25519"), "summary should name the algorithm");
    }

    @Test
    void honoursCustomAliasAndAlgorithm() throws Exception {
        Path keyring = tempDir.resolve("ecdsa-ring.txt");
        StringBuilder out = new StringBuilder(256);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(),
                "-key-alias", "release-signing",
                "-key-algo", "ECDSA"
        }, out);

        assertEquals(0, exit);
        assertNotNull(KeyringStore.load(keyring).getPrivate("release-signing"),
                "custom alias must materialise");
    }

    @Test
    void returnsBadArgsWhenKeyringValueMissing() throws Exception {
        StringBuilder out = new StringBuilder(64);

        int exit = run(new String[] { "-gen-signing-key" }, out);

        assertEquals(2, exit, "missing keyring path must yield exit code 2");
    }

    @Test
    void returnsBadArgsForUnsupportedAlgorithm() throws Exception {
        Path keyring = tempDir.resolve("bad-ring.txt");
        StringBuilder out = new StringBuilder(64);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(),
                "-key-algo", "DSA"
        }, out);

        assertEquals(2, exit, "unsupported algorithm must yield exit code 2");
    }

    @Test
    void collidingAliasFailsWithoutOverwrite() throws Exception {
        Path keyring = tempDir.resolve("collide-ring.txt");
        StringBuilder out = new StringBuilder(64);
        run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        assertThrows(IOException.class,
                () -> run(new String[] { "-gen-signing-key", keyring.toString() }, out));
    }

    @Test
    void overwriteFlagAllowsRegeneration() throws Exception {
        Path keyring = tempDir.resolve("overwrite-ring.txt");
        StringBuilder out = new StringBuilder(64);
        run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(), "-overwrite"
        }, out);

        assertEquals(0, exit, "regeneration with -overwrite must succeed");
    }

    private static int run(String[] args, StringBuilder sink) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(baos, true, StandardCharsets.UTF_8)) {
            int exit = GenSigningKeyCommand.run(args, out);
            out.flush();
            sink.append(baos.toString(StandardCharsets.UTF_8));
            return exit;
        }
    }
}
