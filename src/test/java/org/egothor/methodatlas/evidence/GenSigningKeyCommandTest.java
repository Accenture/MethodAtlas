// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zeroecho.core.storage.KeyringStore;

/**
 * Tests for {@link GenSigningKeyCommand}, the {@code -gen-signing-key} CLI mode.
 */
class GenSigningKeyCommandTest {

    @TempDir
    Path tempDir;

    private Path passwordFile;

    @BeforeEach
    void writePasswordFile() throws IOException {
        passwordFile = tempDir.resolve("password.txt");
        Files.writeString(passwordFile, TestKeyringPasswords.PASSWORD + "\n");
    }

    @Test
    void generatesDefaultKeyringWithDefaultAlias() throws Exception {
        Path keyring = tempDir.resolve("ring");
        StringBuilder out = new StringBuilder(256);

        int exit = run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        assertEquals(0, exit, "default generation must succeed");
        assertTrue(Files.isRegularFile(keyring), "keyring file must be created");
        try (KeyringStore store = TestKeyringPasswords.open(keyring)) {
            assertNotNull(store.getPrivate("methodatlas-signing"), "default alias must materialise");
        }
        assertTrue(out.toString().contains("Ed25519"), "summary should name the algorithm");
        assertTrue(out.toString().contains("-export-keyring-secret"), "summary should explain the CI next step");
    }

    @Test
    void honoursCustomAliasAndAlgorithm() throws Exception {
        Path keyring = tempDir.resolve("ecdsa-ring");
        StringBuilder out = new StringBuilder(256);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(),
                "-key-alias", "release-signing",
                "-key-algo", "ECDSA"
        }, out);

        assertEquals(0, exit);
        try (KeyringStore store = TestKeyringPasswords.open(keyring)) {
            assertNotNull(store.getPrivate("release-signing"), "custom alias must materialise");
        }
    }

    @Test
    void returnsBadArgsWhenKeyringValueMissing() throws Exception {
        StringBuilder out = new StringBuilder(64);

        int exit = run(new String[] { "-gen-signing-key" }, out);

        assertEquals(2, exit, "missing keyring path must yield exit code 2");
    }

    @Test
    void returnsBadArgsForUnsupportedAlgorithm() throws Exception {
        Path keyring = tempDir.resolve("bad-ring");
        StringBuilder out = new StringBuilder(64);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(),
                "-key-algo", "DSA"
        }, out);

        assertEquals(2, exit, "unsupported algorithm must yield exit code 2");
    }

    @Test
    void collidingAliasFailsWithoutOverwrite() throws Exception {
        Path keyring = tempDir.resolve("collide-ring");
        StringBuilder out = new StringBuilder(64);
        run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        assertThrows(IOException.class,
                () -> run(new String[] { "-gen-signing-key", keyring.toString() }, out));
    }

    @Test
    void overwriteFlagAllowsRegeneration() throws Exception {
        Path keyring = tempDir.resolve("overwrite-ring");
        StringBuilder out = new StringBuilder(64);
        run(new String[] { "-gen-signing-key", keyring.toString() }, out);

        int exit = run(new String[] {
                "-gen-signing-key", keyring.toString(), "-overwrite"
        }, out);

        assertEquals(0, exit, "regeneration with -overwrite must succeed");
    }

    @Test
    void passwordFromNamedMissingEnvironmentVariableFails() {
        Path keyring = tempDir.resolve("env-ring");

        IOException ex = assertThrows(IOException.class, () -> GenSigningKeyCommand.run(new String[] {
                "-gen-signing-key", keyring.toString(), "-key-password-env", "METHODATLAS_TEST_SURELY_UNSET"
        }, new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("METHODATLAS_TEST_SURELY_UNSET"), ex.getMessage());
        assertFalse(Files.exists(keyring), "no keyring may be created without a password");
    }

    private int run(String[] args, StringBuilder sink) throws IOException {
        // Prepend so a trailing mode flag without its value stays the last token.
        String[] withPassword = new String[args.length + 2];
        withPassword[0] = "-key-password-file";
        withPassword[1] = passwordFile.toString();
        System.arraycopy(args, 0, withPassword, 2, args.length);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(baos, true, StandardCharsets.UTF_8)) {
            int exit = GenSigningKeyCommand.run(withPassword, out);
            out.flush();
            sink.append(baos.toString(StandardCharsets.UTF_8));
            return exit;
        }
    }
}
