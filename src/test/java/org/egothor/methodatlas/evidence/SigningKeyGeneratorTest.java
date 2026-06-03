// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import zeroecho.core.storage.KeyringStore;

/**
 * Round-trip tests for {@link SigningKeyGenerator}. Each supported algorithm is
 * generated into a keyring, reloaded through ZeroEcho's {@link KeyringStore},
 * and the materialised keys are asserted, proving the reflective import-spec
 * construction works end-to-end.
 */
class SigningKeyGeneratorTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = { "Ed25519", "RSA", "ECDSA", "SPHINCS+" })
    void generatesAndMaterialisesEachSupportedAlgorithm(String algorithm) throws Exception {
        Path keyring = tempDir.resolve(algorithm.replace('+', '_') + "-ring.txt");

        SigningKeyGenerator.GeneratedKey result =
                SigningKeyGenerator.generate(keyring, "signing", algorithm, false);

        assertEquals(algorithm, result.algorithm(), "canonical algorithm should be echoed back");
        assertEquals("signing.pub", result.publicAlias());
        assertEquals("signing.priv", result.privateAlias());

        KeyringStore reloaded = KeyringStore.load(keyring);
        assertNotNull(reloaded.getPublic("signing"), "public key must materialise after reload");
        assertNotNull(reloaded.getPrivate("signing"), "private key must materialise after reload");
    }

    @Test
    void exportsPublicKeyAsStandardPem() throws Exception {
        Path keyring = tempDir.resolve("pem-ring.txt");

        SigningKeyGenerator.GeneratedKey result =
                SigningKeyGenerator.generate(keyring, "audit", "Ed25519", false);

        assertTrue(Files.isRegularFile(result.publicKeyPem()), "a companion PEM file must be written");
        String pem = Files.readString(result.publicKeyPem());
        assertTrue(pem.contains("-----BEGIN PUBLIC KEY-----"),
                "the PEM must be a standard X.509 SubjectPublicKeyInfo readable by openssl");
        assertTrue(pem.contains("-----END PUBLIC KEY-----"), "the PEM must be terminated");
    }

    @Test
    void defaultsToEd25519WhenAlgorithmIsNull() throws Exception {
        Path keyring = tempDir.resolve("default-ring.txt");

        SigningKeyGenerator.GeneratedKey result =
                SigningKeyGenerator.generate(keyring, "signing", null, false);

        assertEquals("Ed25519", result.algorithm(), "null algorithm must select the Ed25519 default");
    }

    @Test
    void acceptsAlgorithmCaseInsensitively() throws Exception {
        Path keyring = tempDir.resolve("lower-ring.txt");

        SigningKeyGenerator.GeneratedKey result =
                SigningKeyGenerator.generate(keyring, "signing", "ed25519", false);

        assertEquals("Ed25519", result.algorithm(), "lower-case id must map to the canonical id");
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        Path keyring = tempDir.resolve("bad-ring.txt");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SigningKeyGenerator.generate(keyring, "signing", "DSA", false));
        assertTrue(ex.getMessage().contains("DSA"), "message should name the rejected algorithm");
    }

    @Test
    void rejectsBlankAlias() {
        Path keyring = tempDir.resolve("blank-ring.txt");

        assertThrows(IllegalArgumentException.class,
                () -> SigningKeyGenerator.generate(keyring, "  ", "Ed25519", false));
    }

    @Test
    void refusesToOverwriteExistingAliasWithoutFlag() throws Exception {
        Path keyring = tempDir.resolve("collide-ring.txt");
        SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false);

        IOException ex = assertThrows(IOException.class,
                () -> SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false));
        assertTrue(ex.getMessage().contains("already exists"),
                "collision message should mention the existing alias");
    }

    @Test
    void overwriteFlagReplacesExistingAlias() throws Exception {
        Path keyring = tempDir.resolve("replace-ring.txt");
        SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false);

        SigningKeyGenerator.GeneratedKey result =
                SigningKeyGenerator.generate(keyring, "signing", "Ed25519", true);

        assertEquals("Ed25519", result.algorithm());
        assertNotNull(KeyringStore.load(keyring).getPrivate("signing"));
    }

    @Test
    void appendsAdditionalAliasToExistingKeyring() throws Exception {
        Path keyring = tempDir.resolve("multi-ring.txt");
        SigningKeyGenerator.generate(keyring, "classic", "Ed25519", false);
        SigningKeyGenerator.generate(keyring, "pqc", "SPHINCS+", false);

        KeyringStore reloaded = KeyringStore.load(keyring);
        assertNotNull(reloaded.getPrivate("classic"), "first key must survive the second write");
        assertNotNull(reloaded.getPrivate("pqc"), "second key must be appended");
    }

    @Test
    void restrictsKeyringToOwnerOnlyOnPosix() throws Exception {
        Path keyring = tempDir.resolve("perm-ring.txt");
        SigningKeyGenerator.generate(keyring, "signing", "Ed25519", false);

        PosixFileAttributeView posix = Files.getFileAttributeView(keyring, PosixFileAttributeView.class);
        if (posix == null) {
            return; // Non-POSIX file system (e.g. Windows): permission lock-down is a warning, not a guarantee.
        }
        Set<PosixFilePermission> perms = posix.readAttributes().permissions();
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "keyring holding a clear-text private key must be owner read/write only");
    }
}
