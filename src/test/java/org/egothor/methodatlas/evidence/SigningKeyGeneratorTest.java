// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import zeroecho.core.storage.KeyringStore;

/**
 * Round-trip tests for {@link SigningKeyGenerator}. Each supported algorithm is
 * generated into an encrypted keyring, reopened through ZeroEcho's
 * {@link KeyringStore}, and the materialised keys are asserted.
 */
class SigningKeyGeneratorTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = { "Ed25519", "RSA", "ECDSA", "SPHINCS+" })
    void generatesAndMaterialisesEachSupportedAlgorithm(String algorithm) throws Exception {
        Path keyring = tempDir.resolve(algorithm.replace('+', '_') + "-ring");

        SigningKeyGenerator.GeneratedKey result = generate(keyring, "signing", algorithm, false);

        assertEquals(algorithm, result.algorithm(), "canonical algorithm should be echoed back");
        assertEquals("signing.pub", result.publicAlias());
        assertEquals("signing.priv", result.privateAlias());
        assertTrue(result.created(), "a missing keyring must be created");

        try (KeyringStore reloaded = TestKeyringPasswords.open(keyring)) {
            assertNotNull(reloaded.getPublic("signing"), "public key must materialise after reopening");
            assertNotNull(reloaded.getPrivate("signing"), "private key must materialise after reopening");
        }
    }

    @Test
    void keyringIsEncryptedAndAccompaniedByNonceSidecar() throws Exception {
        Path keyring = tempDir.resolve("encrypted-ring");
        generate(keyring, "signing", "Ed25519", false);

        String raw = new String(Files.readAllBytes(keyring), StandardCharsets.ISO_8859_1);
        assertTrue(raw.startsWith("ZEKRING2"), "the keyring must use ZeroEcho's encrypted format");
        assertFalse(raw.contains("PRIVATE"), "no clear-text key material may appear in the keyring");
        assertTrue(Files.isRegularFile(KeyringFiles.nonceFile(keyring)), "the .nonce sidecar must be written");
    }

    @Test
    void exportsPublicKeyAsStandardPem() throws Exception {
        Path keyring = tempDir.resolve("pem-ring");

        SigningKeyGenerator.GeneratedKey result = generate(keyring, "audit", "Ed25519", false);

        assertTrue(Files.isRegularFile(result.publicKeyPem()), "a companion PEM file must be written");
        String pem = Files.readString(result.publicKeyPem());
        assertTrue(pem.contains("-----BEGIN PUBLIC KEY-----"),
                "the PEM must be a standard X.509 SubjectPublicKeyInfo readable by openssl");
        assertTrue(pem.contains("-----END PUBLIC KEY-----"), "the PEM must be terminated");
    }

    @Test
    void defaultsToEd25519WhenAlgorithmIsNull() throws Exception {
        SigningKeyGenerator.GeneratedKey result = generate(tempDir.resolve("default-ring"), "signing", null, false);

        assertEquals("Ed25519", result.algorithm(), "null algorithm must select the Ed25519 default");
    }

    @Test
    void acceptsAlgorithmCaseInsensitively() throws Exception {
        SigningKeyGenerator.GeneratedKey result =
                generate(tempDir.resolve("lower-ring"), "signing", "ed25519", false);

        assertEquals("Ed25519", result.algorithm(), "lower-case id must map to the canonical id");
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        Path keyring = tempDir.resolve("bad-ring");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> generate(keyring, "signing", "DSA", false));
        assertTrue(ex.getMessage().contains("DSA"), "message should name the rejected algorithm");
        assertFalse(Files.exists(keyring), "no keyring may be created for a rejected algorithm");
    }

    @Test
    void rejectsBlankAlias() {
        assertThrows(IllegalArgumentException.class,
                () -> generate(tempDir.resolve("blank-ring"), "  ", "Ed25519", false));
    }

    @Test
    void refusesToOverwriteExistingAliasWithoutFlag() throws Exception {
        Path keyring = tempDir.resolve("collide-ring");
        generate(keyring, "signing", "Ed25519", false);

        IOException ex = assertThrows(IOException.class, () -> generate(keyring, "signing", "Ed25519", false));
        assertTrue(ex.getMessage().contains("already exists"),
                "collision message should mention the existing alias");
    }

    @Test
    void overwriteFlagReplacesExistingAlias() throws Exception {
        Path keyring = tempDir.resolve("replace-ring");
        generate(keyring, "signing", "Ed25519", false);
        String before = Files.readString(keyring.resolveSibling("signing-public.pem"));

        SigningKeyGenerator.GeneratedKey result = generate(keyring, "signing", "Ed25519", true);

        assertEquals("Ed25519", result.algorithm());
        assertFalse(result.created(), "the existing keyring must be reused");
        assertFalse(before.equals(Files.readString(result.publicKeyPem())), "a new key pair must replace the old one");
        try (KeyringStore reloaded = TestKeyringPasswords.open(keyring)) {
            assertNotNull(reloaded.getPrivate("signing"));
        }
    }

    @Test
    void overwriteRemovesItsTemporaryBackup() throws Exception {
        Path keyring = tempDir.resolve("backup-ring");
        generate(keyring, "signing", "Ed25519", false);

        generate(keyring, "signing", "Ed25519", true);

        assertFalse(Files.exists(keyring.resolveSibling("backup-ring.bak")), "the store backup must be removed");
        assertFalse(Files.exists(keyring.resolveSibling("backup-ring.nonce.bak")), "the sidecar backup must be removed");
    }

    @ParameterizedTest
    @ValueSource(strings = { "signing.pub", "signing.priv", "classic/pqc" })
    void rejectsAliasesZeroEchoOrHybridSyntaxCannotRepresent(String alias) {
        Path keyring = tempDir.resolve("alias-ring");

        assertThrows(IllegalArgumentException.class, () -> generate(keyring, alias, "Ed25519", false));
        assertFalse(Files.exists(keyring), "no keyring may be created for a rejected alias");
    }

    @Test
    void appendsAdditionalAliasToExistingKeyring() throws Exception {
        Path keyring = tempDir.resolve("multi-ring");
        generate(keyring, "classic", "Ed25519", false);
        generate(keyring, "pqc", "SPHINCS+", false);

        try (KeyringStore reloaded = TestKeyringPasswords.open(keyring)) {
            assertNotNull(reloaded.getPrivate("classic"), "first key must survive the second write");
            assertNotNull(reloaded.getPrivate("pqc"), "second key must be appended");
        }
    }

    @Test
    void wrongPasswordForExistingKeyringIsReported() throws Exception {
        Path keyring = tempDir.resolve("wrong-ring");
        generate(keyring, "classic", "Ed25519", false);

        IOException ex = assertThrows(IOException.class, () -> SigningKeyGenerator.generate(keyring, "second",
                "Ed25519", false, TestKeyringPasswords.of("not-the-right-password")));
        assertTrue(ex.getMessage().contains("Wrong keyring password"), ex.getMessage());
    }

    @Test
    void tooShortPasswordIsRejectedForNewKeyring() {
        Path keyring = tempDir.resolve("short-ring");

        IOException ex = assertThrows(IOException.class, () -> SigningKeyGenerator.generate(keyring, "signing",
                "Ed25519", false, TestKeyringPasswords.of("short")));
        assertTrue(ex.getMessage().contains("at least"), ex.getMessage());
        assertFalse(Files.exists(keyring), "no keyring may be created with a too-short password");
    }

    @Test
    void createsMissingParentDirectoryOwnerOnly() throws Exception {
        Path keyring = tempDir.resolve("keys").resolve("nested-ring");

        generate(keyring, "signing", "Ed25519", false);

        PosixFileAttributeView posix = Files.getFileAttributeView(keyring.getParent(), PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only assertion");
        assertEquals(PosixFilePermissions.fromString("rwx------"), posix.readAttributes().permissions(),
                "a created keyring directory must be owner-only");
    }

    @Test
    void rejectsSharedDirectoryWithRemediationHint() throws Exception {
        Path shared = tempDir.resolve("shared");
        Files.createDirectory(shared);
        PosixFileAttributeView posix = Files.getFileAttributeView(shared, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only behaviour");
        posix.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"));

        IOException ex = assertThrows(IOException.class,
                () -> generate(shared.resolve("ring"), "signing", "Ed25519", false));
        assertTrue(ex.getMessage().contains("chmod 700"), "the error must tell the user how to fix it: "
                + ex.getMessage());
    }

    @Test
    void rejectsLegacyPlaintextKeyringWithMigrationHint() throws Exception {
        Path keyring = tempDir.resolve("legacy-ring.txt");
        Files.writeString(keyring, "# KeyringStore v1\nalias.priv ...\n");

        IOException ex = assertThrows(IOException.class, () -> generate(keyring, "new", "Ed25519", false));
        assertTrue(ex.getMessage().contains("-gen-signing-key"), "the error must explain how to migrate: "
                + ex.getMessage());
    }

    @Test
    void restrictsKeyringToOwnerOnlyOnPosix() throws Exception {
        Path keyring = tempDir.resolve("perm-ring");
        generate(keyring, "signing", "Ed25519", false);

        PosixFileAttributeView posix = Files.getFileAttributeView(keyring, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX-only assertion");
        Set<PosixFilePermission> perms = posix.readAttributes().permissions();
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "the keyring must be owner read/write only");
    }

    private static SigningKeyGenerator.GeneratedKey generate(Path keyring, String alias, String algorithm,
            boolean overwrite) throws Exception {
        return SigningKeyGenerator.generate(keyring, alias, algorithm, overwrite, TestKeyringPasswords.fixed());
    }
}
