// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import zeroecho.core.context.SignatureContext;
import zeroecho.core.spec.VoidSpec;
import zeroecho.core.storage.KeyringPassword;
import zeroecho.core.storage.KeyringStore;
import zeroecho.core.tag.TagEngine;
import zeroecho.core.tag.TagEngineBuilder;
import zeroecho.sdk.builders.TagTrailerDataContentBuilder;
import zeroecho.sdk.content.api.DataContent;
import zeroecho.sdk.content.builtin.PlainFile;
import zeroecho.sdk.hybrid.signature.HybridSignatureContexts;
import zeroecho.sdk.ZeroEchoSession;
import zeroecho.sdk.hybrid.signature.HybridSignatureProfile;
import zeroecho.sdk.util.BouncyCastleActivator;

/**
 * Produces a signed envelope of the evidence-pack manifest using the ZeroEcho
 * cryptographic toolkit (version 1.2.1).
 *
 * <p>
 * The signing key is read from an encrypted ZeroEcho {@link KeyringStore}
 * (PBKDF2 + AES-256-GCM) — <em>not</em> a JDK PKCS12/JKS keystore and not
 * anything produced by {@code keytool}. The keyring is unlocked with a password
 * from a {@link KeyringPasswordSource}, the private key is resolved by alias, and
 * the keyring is closed again before signing starts. Generate a keyring with
 * MethodAtlas's {@code -gen-signing-key} mode (see {@link SigningKeyGenerator}).
 * </p>
 *
 * <h2>Single-algorithm signing</h2>
 * <p>
 * When the signature algorithm contains no {@value #HYBRID_SEP} separator the
 * manifest is signed with one algorithm. The algorithm defaults to
 * {@value #DEFAULT_ALGO}; when no explicit algorithm is supplied the value
 * stored alongside the key in the keyring is used. RSA keys are signed with
 * RSA-PSS (SHA-256) and ECDSA keys with the P-256 curve; every other algorithm
 * (Ed25519, Ed448, SPHINCS+, ML-DSA, SLH-DSA, …) is dispatched through
 * {@link TagEngineBuilder#signature(ZeroEchoSession, String, java.security.Key, zeroecho.core.spec.ContextSpec)}.
 * </p>
 *
 * <h2>Hybrid signing</h2>
 * <p>
 * A signature algorithm of the form {@code classic+pqc} (for example
 * {@code Ed25519+SPHINCS+}) selects a hybrid composite that signs with both a
 * classical and a post-quantum primitive. The key alias must then carry both
 * component aliases separated by {@value #ALIAS_SEP}
 * ({@code classicAlias/pqcAlias}). Verification of a hybrid envelope requires
 * both component signatures (AND rule).
 * </p>
 *
 * <h2>Thread-safety and lifecycle</h2>
 * <p>
 * Instances are <strong>not</strong> thread-safe and are single-use: create one
 * signer per {@link #sign(Path, Path)} call. Hybrid signers in particular hold a
 * stateful {@link SignatureContext} that must not be reused across streams. The
 * signer is {@link AutoCloseable}; callers should use it in a
 * try-with-resources block (or call {@link #close()}) so the owned hybrid
 * context is released — close it even if {@link #sign(Path, Path)} is never
 * invoked.
 * </p>
 *
 * <p>
 * Package-private because only {@link EvidencePackCommand} composes this signer.
 * </p>
 *
 * @see SigningKeyGenerator
 * @see <a href="https://github.com/Egothor/ZeroEcho">ZeroEcho</a>
 * @since 4.0.0
 */
final class ZeroEchoSigner implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ZeroEchoSigner.class.getName());

    /** Stream buffer size used when copying through the signing pipeline. */
    /* default */ static final int BUFFER_SIZE = 8192;

    /**
     * Upper bound on the manifest body buffered by the hybrid DoS guard — 16 MiB
     * is far more than any realistic SHA-256 manifest.
     */
    /* default */ static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Default single-algorithm identifier when none is supplied and none is stored. */
    /* default */ static final String DEFAULT_ALGO = "Ed25519";

    /** Separator that distinguishes hybrid algorithm specifications ({@code classic+pqc}). */
    /* default */ static final String HYBRID_SEP = "+";

    /** Separator used inside the key-alias parameter to split classical and PQC aliases. */
    /* default */ static final String ALIAS_SEP = "/";

    /** Version of the ZeroEcho library recorded in {@code pack-meta.json}. */
    /* default */ static final String ZEROECHO_LIB_VERSION = "1.2.1";

    /**
     * ZeroEcho runtime session shared by all signers: the default permissive
     * policy with auditing off. Sessions are immutable and thread-safe.
     */
    private static final ZeroEchoSession SESSION = new ZeroEchoSession();

    /** Factory that supplies a fresh signing engine for the manifest pipeline. */
    private final Supplier<TagEngine<Signature>> engineFactory;

    /** Resolved algorithm string, reported by callers in pack metadata. */
    private final String algorithm;

    /** Effective keystore alias actually used to load the signing key. */
    private final String resolvedAlias;

    /**
     * Closeable cryptographic context owned by this signer, or {@code null}. The
     * hybrid path eagerly creates a {@link SignatureContext} that this signer
     * retains so {@link #close()} can release it; the single-algorithm path has
     * no owned context (its per-stream engines are created and owned by the
     * signing pipeline).
     */
    private final SignatureContext ownedContext;

    /**
     * Private constructor; instances are obtained through
     * {@link #fromKeyringFile(Path, KeyringPasswordSource, String, String)} or
     * {@link #fromKeyringBundle(String, KeyringPasswordSource, String, String)}.
     *
     * @param engineFactory supplier of the signing engine; must not be {@code null}
     * @param algorithm     algorithm string (e.g. {@code "Ed25519"})
     * @param resolvedAlias keyring alias actually used
     * @param ownedContext  closeable context this signer owns, or {@code null}
     *                      when there is none to release
     */
    private ZeroEchoSigner(Supplier<TagEngine<Signature>> engineFactory, String algorithm, String resolvedAlias,
            SignatureContext ownedContext) {
        this.engineFactory = engineFactory;
        this.algorithm = algorithm;
        this.resolvedAlias = resolvedAlias;
        this.ownedContext = ownedContext;
    }

    /**
     * Releases the cryptographic context this signer owns. A no-op for the
     * single-algorithm path; for hybrid signers it closes the retained
     * {@link SignatureContext}. Failures to close are logged at {@code FINE} and
     * swallowed, since {@code close()} is best-effort cleanup and must not mask a
     * signing result.
     */
    @Override
    public void close() {
        if (ownedContext != null) {
            try {
                ownedContext.close();
            } catch (IOException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Failed to close hybrid signature context", e);
                }
            }
        }
    }

    /**
     * Returns the algorithm string that will be reported in pack metadata.
     *
     * @return algorithm identifier; never {@code null}
     */
    /* default */ String algorithm() {
        return algorithm;
    }

    /**
     * Returns the keyring alias actually used to retrieve the signing key.
     *
     * @return alias; never {@code null} once construction succeeded
     */
    /* default */ String resolvedAlias() {
        return resolvedAlias;
    }

    /**
     * Creates a signer from an encrypted ZeroEcho keyring file on disk. This is
     * the path used for interactive CLI signing.
     *
     * @param keyringFile        path to the ZeroEcho keyring; must not be {@code null}
     * @param passwords          source of the keyring password; must not be {@code null}
     * @param keyAlias           alias to load; {@code null} means use the first
     *                           alias in the keyring; for hybrid the format is
     *                           {@code classicAlias/pqcAlias}
     * @param signatureAlgorithm algorithm identifier; {@code null} means derive it
     *                           from the keyring entry (single-algorithm only); a
     *                           value of the form {@code classic+pqc} selects
     *                           hybrid signing
     * @return signer ready to sign; never {@code null}
     * @throws IOException              if the password is unavailable or wrong, the
     *                                  keyring cannot be opened, or building the
     *                                  signing context fails
     * @throws GeneralSecurityException if a key cannot be materialised
     */
    /* default */ static ZeroEchoSigner fromKeyringFile(Path keyringFile, KeyringPasswordSource passwords,
            String keyAlias, String signatureAlgorithm) throws IOException, GeneralSecurityException {
        try (KeyringPassword password = passwords.resolve(false);
                KeyringStore keyring = KeyringFiles.open(keyringFile, password)) {
            return build(keyring, keyAlias, signatureAlgorithm);
        }
    }

    /**
     * Creates a signer from a keyring secret bundle (see
     * {@link KeyringFiles#exportBundle(Path)}). This is the path used in CI/CD
     * pipelines, where the bundle is delivered through a platform secret. The
     * still-encrypted keyring is restored into an owner-only temporary directory
     * that is deleted as soon as the key has been read.
     *
     * @param bundle             keyring secret bundle text, typically the value of
     *                           a CI secret variable; must not be {@code null} or blank
     * @param passwords          source of the keyring password; must not be {@code null}
     * @param keyAlias           alias to load; {@code null} means use the first
     *                           alias in the keyring; for hybrid the format is
     *                           {@code classicAlias/pqcAlias}
     * @param signatureAlgorithm algorithm identifier; {@code null} means derive it
     *                           from the keyring entry; a value of the form
     *                           {@code classic+pqc} selects hybrid signing
     * @return signer ready to sign; never {@code null}
     * @throws IOException              if the bundle is malformed, the password is
     *                                  unavailable or wrong, or building the signing
     *                                  context fails
     * @throws GeneralSecurityException if a key cannot be materialised
     */
    /* default */ static ZeroEchoSigner fromKeyringBundle(String bundle, KeyringPasswordSource passwords,
            String keyAlias, String signatureAlgorithm) throws IOException, GeneralSecurityException {
        try (KeyringFiles.RestoredKeyring restored = KeyringFiles.restoreBundle(bundle)) {
            return fromKeyringFile(restored.keyring(), passwords, keyAlias, signatureAlgorithm);
        }
    }

    /**
     * Builds a signer from an open keyring, dispatching to the single or hybrid
     * construction path based on {@code signatureAlgorithm}. The keys are
     * materialised eagerly so the caller may close the keyring afterwards.
     *
     * @param keyring            open keyring store; must not be {@code null}
     * @param keyAlias           alias to load, or {@code null} for the first alias
     * @param signatureAlgorithm algorithm identifier, or {@code null} to derive it
     * @return configured signer
     * @throws IOException              if an alias is unknown, the alias/algorithm
     *                                  pairing is invalid, or ZeroEcho rejects the
     *                                  configuration
     * @throws GeneralSecurityException if a key cannot be materialised
     */
    // ZeroEcho reports unknown entries and bad hybrid profiles with unchecked
    // exceptions; translating them keeps a signing misconfiguration from crashing
    // the evidence-pack run (it must degrade to an unsigned pack).
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static ZeroEchoSigner build(KeyringStore keyring, String keyAlias, String signatureAlgorithm)
            throws IOException, GeneralSecurityException {
        // SPHINCS+ and the other PQC primitives are provided by Bouncy Castle;
        // init is idempotent and harmless for classical algorithms.
        BouncyCastleActivator.init();
        try {
            if (signatureAlgorithm != null && signatureAlgorithm.contains(HYBRID_SEP)) {
                return buildHybrid(keyring, keyAlias, signatureAlgorithm);
            }
            return buildSingle(keyring, keyAlias, signatureAlgorithm);
        } catch (RuntimeException e) {
            throw new IOException("ZeroEcho rejected the signing configuration (" + signatureAlgorithm + ", alias "
                    + keyAlias + "): " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the private key stored under {@code alias}, failing with the list
     * of available aliases when there is none.
     *
     * @param keyring open keyring
     * @param alias   logical alias
     * @return the private key entry
     * @throws IOException              if the keyring holds no private key under {@code alias}
     * @throws GeneralSecurityException if the key cannot be materialised
     */
    private static KeyringStore.PrivateWithId privateEntry(KeyringStore keyring, String alias)
            throws IOException, GeneralSecurityException {
        if (!keyring.contains(alias + ".priv")) {
            String available = keyring.aliases().stream().distinct().collect(Collectors.joining(", "));
            throw new IOException("Keyring has no private key with alias '" + alias + "'; available aliases: "
                    + (available.isEmpty() ? "(none)" : available));
        }
        return keyring.getPrivateWithId(alias);
    }

    /**
     * Signs {@code inputFile} and writes the resulting signed envelope to
     * {@code outputFile}. The output contains the original bytes followed by a
     * ZeroEcho signature trailer.
     *
     * @param inputFile  manifest to sign
     * @param outputFile destination for the signed envelope
     * @return {@code outputFile} on success
     * @throws IOException              if reading, signing, or writing fails
     * @throws GeneralSecurityException if the cryptographic step fails
     */
    // ZeroEcho's builders surface configuration failures as unchecked exceptions
    // (IllegalStateException from the engine factory, IllegalArgumentException for
    // bad parameters); translating them to IOException keeps the CLI from crashing
    // mid-pack with a raw stack trace.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ Path sign(Path inputFile, Path outputFile) throws IOException, GeneralSecurityException {
        try {
            DataContent tail = new TagTrailerDataContentBuilder<>(engineFactory)
                    .bufferSize(BUFFER_SIZE)
                    .build(true);
            tail.setInput(new PlainFile(inputFile.toUri().toURL()));
            try (InputStream in = tail.getStream();
                    OutputStream out = Files.newOutputStream(outputFile)) {
                in.transferTo(out);
            }
            return outputFile;
        } catch (RuntimeException e) {
            throw new IOException("Failed to sign manifest with ZeroEcho (" + algorithm + ")", e);
        }
    }

    // -------------------------------------------------------------------------
    // Construction helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a single-algorithm signer, deriving the algorithm from the keyring
     * entry when {@code explicitAlgorithm} is {@code null}.
     *
     * @param keyring           loaded keyring store
     * @param keyAlias          requested alias, or {@code null} for the first alias
     * @param explicitAlgorithm caller-supplied algorithm, or {@code null} to derive it
     * @return configured signer
     * @throws IOException              if the keyring is empty
     * @throws GeneralSecurityException if the private key cannot be materialised
     */
    private static ZeroEchoSigner buildSingle(KeyringStore keyring, String keyAlias, String explicitAlgorithm)
            throws IOException, GeneralSecurityException {
        String alias = resolveAlias(keyring, keyAlias);
        KeyringStore.PrivateWithId entry = privateEntry(keyring, alias);
        String algo = explicitAlgorithm != null ? explicitAlgorithm : entry.algorithm();
        Supplier<TagEngine<Signature>> factory = engineFactoryFor(algo, entry.key());
        return new ZeroEchoSigner(factory, algo, alias, null);
    }

    /**
     * Builds a hybrid (classical + post-quantum) signer.
     *
     * @param keyring  loaded keyring store
     * @param keyAlias combined alias {@code classicAlias/pqcAlias}; must not be {@code null}
     * @param algo     combined algorithm {@code classic+pqc}
     * @return configured signer
     * @throws IOException              if the alias is not a valid hybrid pair
     * @throws GeneralSecurityException if either private key cannot be materialised
     */
    private static ZeroEchoSigner buildHybrid(KeyringStore keyring, String keyAlias, String algo)
            throws IOException, GeneralSecurityException {
        String[] algoParts = splitPair(algo, HYBRID_SEP,
                "Hybrid algorithm must be 'classic+pqc' (e.g. Ed25519+SPHINCS+): " + algo);
        if (keyAlias == null || !keyAlias.contains(ALIAS_SEP)) {
            throw new IOException("Hybrid signing requires a 'classicAlias/pqcAlias' key alias via "
                    + "-evidence-pack-key-alias; got: " + keyAlias);
        }
        String[] aliasParts = splitPair(keyAlias, ALIAS_SEP,
                "Hybrid key alias must be 'classicAlias/pqcAlias': " + keyAlias);

        PrivateKey classicKey = privateEntry(keyring, aliasParts[0]).key();
        PrivateKey pqcKey = privateEntry(keyring, aliasParts[1]).key();

        HybridSignatureProfile profile = new HybridSignatureProfile(
                algoParts[0], algoParts[1], null, null, HybridSignatureProfile.VerifyRule.AND);
        SignatureContext context = HybridSignatureContexts.sign(SESSION, profile, classicKey, pqcKey,
                MAX_BODY_BYTES);
        // The hybrid context is stateful and single-use; the signer is documented as
        // single-use, so reusing the same instance across the one sign() call is safe.
        // The signer retains the context and releases it in close().
        return new ZeroEchoSigner(() -> context, algo, keyAlias, context);
    }

    /**
     * Resolves the per-algorithm ZeroEcho engine factory. RSA keys use RSA-PSS
     * (SHA-256, 32-byte salt) and ECDSA keys use the P-256 curve, matching the
     * dedicated {@link TagEngineBuilder} factories; every other identifier is
     * dispatched through the generic
     * {@link TagEngineBuilder#signature(ZeroEchoSession, String, java.security.Key, zeroecho.core.spec.ContextSpec)}
     * factory, which covers Ed25519, Ed448, SPHINCS+, ML-DSA, and SLH-DSA.
     *
     * @param algo       canonical algorithm string
     * @param privateKey loaded private key
     * @return supplier producing fresh signing engines
     */
    private static Supplier<TagEngine<Signature>> engineFactoryFor(String algo, PrivateKey privateKey) {
        String normalised = algo.toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "RSA", "RSA-PSS", "RSASSA-PSS" -> TagEngineBuilder.rsaSign(SESSION, privateKey, null);
            case "ECDSA" -> TagEngineBuilder.ecdsaSign(SESSION, privateKey, null);
            default -> TagEngineBuilder.signature(SESSION, algo, privateKey, VoidSpec.INSTANCE);
        };
    }

    /**
     * Returns the alias to use: {@code keyAlias} when non-null, otherwise the
     * first alias in the keyring.
     *
     * @param keyring  keyring to query
     * @param keyAlias requested alias, or {@code null}
     * @return resolved alias
     * @throws IOException if the keyring contains no aliases
     */
    private static String resolveAlias(KeyringStore keyring, String keyAlias) throws IOException {
        if (keyAlias != null) {
            return keyAlias;
        }
        List<String> aliases = keyring.aliases();
        if (aliases.isEmpty()) {
            throw new IOException("Keyring contains no aliases");
        }
        return aliases.get(0);
    }

    /**
     * Splits {@code value} into exactly two non-blank halves around {@code sep}.
     *
     * @param value   value to split
     * @param sep     literal separator
     * @param message error message used when the split does not yield two halves
     * @return a two-element array of trimmed, non-blank parts
     * @throws IOException if {@code value} does not split into two non-blank parts
     */
    private static String[] splitPair(String value, String sep, String message) throws IOException {
        int idx = value.indexOf(sep);
        if (idx <= 0 || idx >= value.length() - sep.length()) {
            throw new IOException(message);
        }
        String first = value.substring(0, idx).trim();
        String second = value.substring(idx + sep.length()).trim();
        if (first.isEmpty() || second.isEmpty()) {
            throw new IOException(message);
        }
        return new String[] { first, second };
    }
}
