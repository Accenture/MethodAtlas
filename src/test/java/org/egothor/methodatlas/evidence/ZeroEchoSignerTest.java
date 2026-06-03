// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.Signature;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import zeroecho.core.context.SignatureContext;
import zeroecho.core.spec.VoidSpec;
import zeroecho.core.storage.KeyringStore;
import zeroecho.core.tag.TagEngine;
import zeroecho.core.tag.TagEngineBuilder;
import zeroecho.sdk.builders.TagTrailerDataContentBuilder;
import zeroecho.sdk.content.api.DataContent;
import zeroecho.sdk.content.builtin.PlainFile;
import zeroecho.sdk.hybrid.signature.HybridSignatureContexts;
import zeroecho.sdk.hybrid.signature.HybridSignatureProfile;
import zeroecho.sdk.util.BouncyCastleActivator;

/**
 * Tests for {@link ZeroEchoSigner}. Keys are produced by
 * {@link SigningKeyGenerator} into a ZeroEcho {@link KeyringStore}, the manifest
 * is signed, and the resulting envelope is verified through ZeroEcho's own
 * verification path — proving the full generate → sign → verify round-trip
 * against the 1.1.1 API rather than against JDK keytool quirks.
 */
class ZeroEchoSignerTest {

    private static final byte[] MANIFEST = "deadbeef  manifest.sha256\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void enableBouncyCastle() {
        BouncyCastleActivator.init();
    }

    @Test
    void fromKeyringTextRejectsEmptyContent() {
        assertThrows(IOException.class, () -> ZeroEchoSigner.fromKeyringText("  ", null, null),
                "blank keyring content must be rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Ed25519", "RSA", "ECDSA" })
    void signsAndVerifiesWithSingleAlgorithm(String algorithm) throws Exception {
        Path keyring = newKeyring(algorithm.toLowerCase(java.util.Locale.ROOT), algorithm);
        Path signed = sign(keyring, "signing", algorithm);

        byte[] recovered = verifySingle(keyring, "signing", algorithm, signed);
        assertArrayEquals(MANIFEST, recovered, "verification must recover the original manifest bytes");
    }

    @Test
    void signsFromInMemoryKeyringText() throws Exception {
        Path keyring = newKeyring("intext", "Ed25519");
        String keyringText = Files.readString(keyring, StandardCharsets.UTF_8);

        Path manifest = tempDir.resolve("intext-manifest.txt");
        Files.write(manifest, MANIFEST);
        Path signed = tempDir.resolve("intext-manifest.signed");
        ZeroEchoSigner.fromKeyringText(keyringText, "signing", null).sign(manifest, signed);

        byte[] recovered = verifySingle(keyring, "signing", "Ed25519", signed);
        assertArrayEquals(MANIFEST, recovered, "the CI in-memory keyring path must produce a valid signature");
    }

    @Test
    void derivesAlgorithmFromKeyringWhenUnspecified() throws Exception {
        Path keyring = newKeyring("derive", "Ed25519");

        ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringFile(keyring, "signing", null);
        assertEquals("Ed25519", signer.algorithm(),
                "algorithm must be read from the keyring entry when not supplied");
        assertEquals("signing", signer.resolvedAlias());
    }

    @Test
    void usesFirstAliasWhenNoneRequested() throws Exception {
        Path keyring = newKeyring("first", "Ed25519");

        ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringFile(keyring, null, null);
        assertEquals("signing", signer.resolvedAlias(), "the sole alias must be selected");
    }

    @Test
    void verificationFailsWhenSignedEnvelopeIsTampered() throws Exception {
        Path keyring = newKeyring("tamper", "Ed25519");
        Path signed = sign(keyring, "signing", "Ed25519");

        byte[] bytes = Files.readAllBytes(signed);
        bytes[0] ^= 0x01; // flip a bit in the manifest body
        Path corrupted = tempDir.resolve("corrupted.signed");
        Files.write(corrupted, bytes);

        assertThrows(IOException.class, () -> verifySingle(keyring, "signing", "Ed25519", corrupted),
                "a tampered envelope must fail signature verification");
    }

    @Test
    void signsAndVerifiesWithHybridEd25519AndSphincsPlus() throws Exception {
        Path keyring = tempDir.resolve("hybrid-ring.txt");
        SigningKeyGenerator.generate(keyring, "classic", "Ed25519", false);
        SigningKeyGenerator.generate(keyring, "pqc", "SPHINCS+", false);

        Path signed = sign(keyring, "classic/pqc", "Ed25519+SPHINCS+");

        byte[] recovered = verifyHybrid(keyring, "classic", "pqc", "Ed25519", "SPHINCS+", signed);
        assertArrayEquals(MANIFEST, recovered, "hybrid verification must recover the original manifest");
    }

    @Test
    void hybridSigningRejectsAliasWithoutSeparator() throws Exception {
        Path keyring = newKeyring("hybrid-bad", "Ed25519");

        IOException ex = assertThrows(IOException.class,
                () -> ZeroEchoSigner.fromKeyringFile(keyring, "signing", "Ed25519+SPHINCS+"));
        assertTrue(ex.getMessage().contains("classicAlias/pqcAlias"),
                "error must explain the required hybrid alias format: " + ex.getMessage());
    }

    @Test
    void closeReleasesTheHybridSignatureContext() throws Exception {
        Path keyring = tempDir.resolve("hybrid-close-ring.txt");
        SigningKeyGenerator.generate(keyring, "classic", "Ed25519", false);
        SigningKeyGenerator.generate(keyring, "pqc", "SPHINCS+", false);

        SignatureContext mockContext = mock(SignatureContext.class);
        try (MockedStatic<HybridSignatureContexts> mocked = mockStatic(HybridSignatureContexts.class)) {
            mocked.when(() -> HybridSignatureContexts.sign(any(), any(), any(), anyInt()))
                    .thenReturn(mockContext);
            ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringFile(keyring, "classic/pqc", "Ed25519+SPHINCS+");
            signer.close();
        }
        Mockito.verify(mockContext).close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path newKeyring(String stem, String algorithm) throws Exception {
        Path keyring = tempDir.resolve(stem + "-ring.txt");
        SigningKeyGenerator.generate(keyring, "signing", algorithm, false);
        return keyring;
    }

    private Path sign(Path keyring, String alias, String algorithm) throws Exception {
        Path manifest = tempDir.resolve(alias.replace('/', '_') + "-manifest.txt");
        Files.write(manifest, MANIFEST);
        Path signed = tempDir.resolve(alias.replace('/', '_') + "-manifest.signed");

        return ZeroEchoSigner.fromKeyringFile(keyring, alias, algorithm).sign(manifest, signed);
    }

    private static byte[] verifySingle(Path keyring, String alias, String algorithm, Path signed) throws Exception {
        KeyringStore store = KeyringStore.load(keyring);
        PublicKey publicKey = store.getPublic(alias);
        // Mirror ZeroEchoSigner's signing dispatch so the verifier matches the signer:
        // RSA uses RSA-PSS, ECDSA uses P-256, everything else the generic factory.
        TagEngine<Signature> engine = switch (algorithm.toUpperCase(java.util.Locale.ROOT)) {
            case "RSA" -> TagEngineBuilder.rsaVerify(publicKey, null).get();
            case "ECDSA" -> TagEngineBuilder.ecdsaVerify(publicKey, null).get();
            default -> TagEngineBuilder.signature(algorithm, publicKey, VoidSpec.INSTANCE).get();
        };
        return verify(engine, signed);
    }

    private static byte[] verifyHybrid(Path keyring, String classicAlias, String pqcAlias, String classicId,
            String pqcId, Path signed) throws Exception {
        KeyringStore store = KeyringStore.load(keyring);
        PublicKey classicPublic = store.getPublic(classicAlias);
        PublicKey pqcPublic = store.getPublic(pqcAlias);
        HybridSignatureProfile profile = new HybridSignatureProfile(
                classicId, pqcId, null, null, HybridSignatureProfile.VerifyRule.AND);
        SignatureContext engine = HybridSignatureContexts.verify(
                profile, classicPublic, pqcPublic, ZeroEchoSigner.MAX_BODY_BYTES);
        return verify(engine, signed);
    }

    private static byte[] verify(TagEngine<Signature> engine, Path signed) throws Exception {
        DataContent tail = new TagTrailerDataContentBuilder<>(engine)
                .bufferSize(ZeroEchoSigner.BUFFER_SIZE)
                .throwOnMismatch()
                .build(false);
        tail.setInput(new PlainFile(signed.toUri().toURL()));
        try (InputStream in = tail.getStream()) {
            return in.readAllBytes();
        }
    }
}
