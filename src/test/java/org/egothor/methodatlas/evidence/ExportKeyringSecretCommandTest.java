// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ExportKeyringSecretCommand}, the {@code -export-keyring-secret} mode.
 */
class ExportKeyringSecretCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void printsARestorableBundle() throws Exception {
        Path keyring = tempDir.resolve("ring");
        SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false, TestKeyringPasswords.fixed());
        StringWriter sink = new StringWriter();

        int exit = ExportKeyringSecretCommand.run(new String[] { "-export-keyring-secret", keyring.toString() },
                new PrintWriter(sink));

        assertEquals(0, exit);
        String bundle = sink.toString();
        assertTrue(bundle.startsWith(KeyringFiles.BUNDLE_HEADER), "stdout must carry only the bundle");
        try (ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringBundle(bundle, TestKeyringPasswords.fixed(),
                null, null)) {
            assertEquals("Ed25519", signer.algorithm(), "the exported bundle must be usable for signing");
        }
    }

    @Test
    void missingKeyringArgumentIsBadArgs() throws Exception {
        assertEquals(2, ExportKeyringSecretCommand.run(new String[] { "-export-keyring-secret" },
                new PrintWriter(new StringWriter())));
    }

    @Test
    void nonexistentKeyringFails() {
        assertThrows(IOException.class, () -> ExportKeyringSecretCommand.run(
                new String[] { "-export-keyring-secret", tempDir.resolve("none").toString() },
                new PrintWriter(new StringWriter())));
    }
}
