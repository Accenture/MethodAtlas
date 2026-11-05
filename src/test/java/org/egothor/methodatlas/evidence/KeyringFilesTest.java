// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zeroecho.core.storage.KeyringStore;

/**
 * Tests for {@link KeyringFiles}: the CI secret bundle round trip, rejection of
 * malformed and legacy inputs, and clean-up of restored keyrings.
 */
class KeyringFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void bundleRoundTripRestoresAnOpenableKeyring() throws Exception {
        Path keyring = newKeyring("ring");

        String bundle = KeyringFiles.exportBundle(keyring);
        assertTrue(bundle.startsWith(KeyringFiles.BUNDLE_HEADER), "the bundle must start with its header");

        Path directory;
        try (KeyringFiles.RestoredKeyring restored = KeyringFiles.restoreBundle(bundle)) {
            directory = restored.directory();
            assertArrayEquals(Files.readAllBytes(keyring), Files.readAllBytes(restored.keyring()),
                    "the restored store must be byte-identical");
            try (KeyringStore store = TestKeyringPasswords.open(restored.keyring())) {
                assertNotNull(store.getPrivate("signing"), "the restored keyring must unlock and yield the key");
            }
        }
        assertFalse(Files.exists(directory), "closing a restored keyring must delete its temporary directory");
    }

    @Test
    void restoredDirectoryIsOwnerOnly() throws Exception {
        String bundle = KeyringFiles.exportBundle(newKeyring("perm"));

        try (KeyringFiles.RestoredKeyring restored = KeyringFiles.restoreBundle(bundle)) {
            PosixFileAttributeView posix = Files.getFileAttributeView(restored.directory(),
                    PosixFileAttributeView.class);
            assumeTrue(posix != null, "POSIX-only assertion");
            assertEquals(PosixFilePermissions.fromString("rwx------"), posix.readAttributes().permissions());
        }
    }

    @Test
    void bundleToleratesSurroundingWhitespaceAndCrLf() throws Exception {
        String bundle = KeyringFiles.exportBundle(newKeyring("crlf")).replace("\n", "\r\n");

        try (KeyringFiles.RestoredKeyring restored = KeyringFiles.restoreBundle("\n  " + bundle + "  \n")) {
            assertTrue(Files.isRegularFile(restored.keyring()));
        }
    }

    @Test
    void rejectsLegacyPlaintextSecretWithMigrationHint() {
        IOException ex = assertThrows(IOException.class,
                () -> KeyringFiles.restoreBundle("# KeyringStore v1\nsigning.priv=...\n"));
        assertTrue(ex.getMessage().contains("-gen-signing-key"), ex.getMessage());
    }

    @Test
    void rejectsUnrelatedSecret() {
        IOException ex = assertThrows(IOException.class, () -> KeyringFiles.restoreBundle("hunter2"));
        assertTrue(ex.getMessage().contains("-export-keyring-secret"), ex.getMessage());
    }

    @Test
    void rejectsInvalidBase64() {
        String bundle = KeyringFiles.BUNDLE_HEADER + "\nkeyring=@@@\nnonce=AAAA\n";
        assertThrows(IOException.class, () -> KeyringFiles.restoreBundle(bundle));
    }

    @Test
    void rejectsBundleWhoseStoreIsNotAKeyring() {
        String bundle = KeyringFiles.BUNDLE_HEADER + "\nkeyring=aGVsbG8=\nnonce=AAAA\n";
        assertThrows(IOException.class, () -> KeyringFiles.restoreBundle(bundle));
    }

    @Test
    void exportRequiresTheNonceSidecar() throws Exception {
        Path keyring = newKeyring("lonely");
        Files.delete(KeyringFiles.nonceFile(keyring));

        IOException ex = assertThrows(IOException.class, () -> KeyringFiles.exportBundle(keyring));
        assertTrue(ex.getMessage().contains(".nonce"), ex.getMessage());
    }

    @Test
    void exportRejectsMissingFile() {
        assertThrows(IOException.class, () -> KeyringFiles.exportBundle(tempDir.resolve("absent")));
    }

    @Test
    void openReportsMissingFile() {
        IOException ex = assertThrows(IOException.class, () -> ZeroEchoSigner.fromKeyringFile(
                tempDir.resolve("absent"), TestKeyringPasswords.fixed(), null, null));
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    private Path newKeyring(String name) throws Exception {
        Path keyring = tempDir.resolve(name);
        SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false, TestKeyringPasswords.fixed());
        return keyring;
    }
}
