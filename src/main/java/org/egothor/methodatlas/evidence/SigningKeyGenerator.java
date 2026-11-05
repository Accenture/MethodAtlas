// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import zeroecho.core.CryptoAlgorithm;
import zeroecho.core.CryptoAlgorithms;
import zeroecho.core.KeyOperation;
import zeroecho.core.KeyOperationInfo;
import zeroecho.core.spec.AlgorithmKeySpec;
import zeroecho.core.storage.KeyringPassword;
import zeroecho.core.storage.KeyringStore;
import zeroecho.sdk.util.BouncyCastleActivator;

/**
 * Generates an asymmetric signing key pair and stores it in an encrypted
 * ZeroEcho {@link KeyringStore} so that {@code -evidence-pack} can later sign
 * manifests with it.
 *
 * <p>
 * The keyring is encrypted with a password (PBKDF2-HMAC-SHA-256 + AES-256-GCM);
 * it is <em>not</em> a JDK PKCS12/JKS keystore. ZeroEcho requires the keyring
 * directory and files to be owner-only (POSIX {@code 0700}/{@code 0600}, or an
 * owner-only ACL on Windows): a missing directory is created that way, and an
 * existing directory with broader access is rejected with a remediation hint
 * (see {@link KeyringFiles#explain(zeroecho.core.storage.KeyringException, Path)}).
 * </p>
 *
 * <p>
 * Only asymmetric signing algorithms are offered, and the offered set is
 * deliberately limited to the algorithms covered by round-trip tests: the
 * default {@value #DEFAULT_ALGORITHM}, plus {@code RSA}, {@code ECDSA}, and
 * {@code SPHINCS+}. Each key pair is generated with the algorithm's default
 * key-generation parameters as published by
 * {@link CryptoAlgorithm#keyOperations()}.
 * </p>
 *
 * <p>
 * This type is a non-instantiable utility holder. ZeroEcho's ownership lock
 * rejects concurrent writers of the same keyring.
 * </p>
 *
 * @see ZeroEchoSigner
 * @see KeyringStore
 * @since 4.0.0
 */
final class SigningKeyGenerator {

    /** Default signing algorithm when the caller supplies none. */
    /* default */ static final String DEFAULT_ALGORITHM = "Ed25519";

    /** Suffix of the temporary keyring backup taken while an alias is replaced. */
    private static final String BACKUP_SUFFIX = ".bak";

    /** Filename suffix for the exported X.509 public key in PEM form. */
    private static final String PUBLIC_PEM_SUFFIX = "-public.pem";

    /**
     * Canonical algorithm tokens accepted by the generator, keyed by their
     * upper-case form so lookups are case-insensitive while the stored id keeps
     * ZeroEcho's exact spelling.
     */
    private static final Map<String, String> SUPPORTED_ALGORITHMS = Map.of(
            "ED25519", "Ed25519",
            "RSA", "RSA",
            "ECDSA", "ECDSA",
            "SPHINCS+", "SPHINCS+");

    /** Prevents instantiation. */
    private SigningKeyGenerator() {
    }

    /**
     * Outcome of a successful key generation.
     *
     * @param algorithm     canonical algorithm id that was generated
     * @param publicAlias   alias under which the public key was stored
     * @param privateAlias  alias under which the private key was stored
     * @param keyringFile   keyring file the key pair was written to
     * @param publicKeyPem  companion file holding the public key in X.509 PEM
     *                      form, for verification with standard tools
     * @param created       {@code true} when the keyring was newly created,
     *                      {@code false} when the key was added to an existing one
     * @since 4.0.0
     */
    /* default */ record GeneratedKey(String algorithm, String publicAlias, String privateAlias, Path keyringFile,
            Path publicKeyPem, boolean created) {
    }

    /**
     * Returns the canonical algorithm ids this generator supports, in a stable,
     * human-readable order suitable for help text.
     *
     * @return comma-separated list of supported algorithm ids; never empty
     */
    /* default */ static String supportedAlgorithmsHint() {
        return "Ed25519 (default), RSA, ECDSA, SPHINCS+";
    }

    /**
     * Generates a signing key pair and stores it under {@code alias} in the
     * encrypted keyring at {@code keyringFile}.
     *
     * <p>
     * When the keyring already exists it is unlocked and the new pair is added;
     * otherwise a new keyring is created and protected with a new password
     * (at least {@value KeyringPasswordSource#MIN_NEW_PASSWORD_LENGTH}
     * characters). The public key is additionally exported next to the keyring as
     * an X.509 PEM file ({@code <alias>-public.pem}) so a manifest signature can
     * be verified with standard tooling such as {@code openssl}.
     * </p>
     *
     * @param keyringFile target keyring file; must not be {@code null}
     * @param alias       logical alias for the key pair; ZeroEcho stores it as
     *                    {@code alias.pub} and {@code alias.priv}; must not be
     *                    {@code null} or blank
     * @param algorithm   algorithm id ({@code null} selects
     *                    {@value #DEFAULT_ALGORITHM}); must be one of the
     *                    supported ids, case-insensitively
     * @param overwrite   when {@code true}, an existing entry under {@code alias}
     *                    is replaced; when {@code false}, a collision is an error
     * @param passwords   source of the keyring password; must not be {@code null}
     * @return a {@link GeneratedKey} describing what was written
     * @throws IOException              if the password is unavailable or wrong, the
     *                                  alias collides, or the keyring cannot be
     *                                  read or written
     * @throws GeneralSecurityException if key generation fails
     * @throws IllegalArgumentException if {@code alias} is blank or {@code algorithm}
     *                                  is not supported
     */
    /* default */ static GeneratedKey generate(Path keyringFile, String alias, String algorithm, boolean overwrite,
            KeyringPasswordSource passwords) throws IOException, GeneralSecurityException {
        if (keyringFile == null) {
            throw new IllegalArgumentException("keyringFile must not be null");
        }
        validateAlias(alias);
        String canonical = canonicalAlgorithm(algorithm);
        CryptoAlgorithm alg = requireAsymmetric(canonical);

        BouncyCastleActivator.init();
        // Generate first so a key-generation failure never leaves an empty new keyring behind.
        KeyPair keyPair = generateKeyPair(alg);
        boolean created = !Files.exists(keyringFile);
        boolean stored = false;
        try {
            store(keyringFile, alias, canonical, keyPair, overwrite, created, passwords);
            stored = true;
        } finally {
            if (created && !stored) {
                deleteKeyringFiles(keyringFile);
            }
        }

        Path publicKeyPem = keyringFile.resolveSibling(alias + PUBLIC_PEM_SUFFIX);
        writePublicKeyPem(keyPair.getPublic(), publicKeyPem);

        return new GeneratedKey(canonical, alias + ".pub", alias + ".priv", keyringFile, publicKeyPem, created);
    }

    /**
     * Rejects aliases that ZeroEcho or the hybrid alias syntax cannot represent.
     *
     * @param alias requested alias
     * @throws IllegalArgumentException if the alias is blank, carries a
     *                                  {@code .pub}/{@code .priv} suffix, or contains
     *                                  the hybrid separator {@value ZeroEchoSigner#ALIAS_SEP}
     */
    private static void validateAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (alias.endsWith(".pub") || alias.endsWith(".priv") || alias.contains(ZeroEchoSigner.ALIAS_SEP)) {
            throw new IllegalArgumentException("alias must not end with .pub or .priv and must not contain '"
                    + ZeroEchoSigner.ALIAS_SEP + "': " + alias);
        }
    }

    /**
     * Opens or creates the keyring and stores the key pair. When an existing
     * alias is replaced, the previous keyring files are backed up first and the
     * backup is removed only after the new pair is durable, because ZeroEcho
     * removes the old pair and inserts the new one in two separate snapshots.
     *
     * @param keyringFile keyring store path
     * @param alias       logical alias
     * @param canonical   canonical algorithm id
     * @param keyPair     key pair to store
     * @param overwrite   whether an existing alias may be replaced
     * @param created     whether the keyring is created by this call
     * @param passwords   source of the keyring password
     * @throws IOException              if the keyring cannot be opened or written
     * @throws GeneralSecurityException if encryption fails
     */
    // ZeroEcho signals alias-namespace and limit violations with unchecked
    // exceptions; any of them must keep the backup and report how to restore it.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void store(Path keyringFile, String alias, String canonical, KeyPair keyPair, boolean overwrite,
            boolean created, KeyringPasswordSource passwords) throws IOException, GeneralSecurityException {
        try (KeyringPassword password = passwords.resolve(created);
                KeyringStore store = created
                        ? KeyringFiles.create(keyringFile, password)
                        : KeyringFiles.open(keyringFile, password)) {
            if (!store.contains(alias)) {
                store.putKeyPairIfAbsent(alias, canonical, keyPair);
                return;
            }
            if (!overwrite) {
                throw new IOException("Alias already exists in keyring (use -overwrite to replace): " + alias);
            }
            List<Path[]> backups = backup(keyringFile);
            try {
                store.removeAlias(alias);
                store.putKeyPairIfAbsent(alias, canonical, keyPair);
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                throw new IOException("Replacing alias '" + alias + "' failed; the previous keyring is preserved as "
                        + backups.get(0)[1] + " and " + backups.get(1)[1] + " — restore both by renaming them back",
                        e);
            }
            for (Path[] pair : backups) {
                Files.deleteIfExists(pair[1]);
            }
        }
    }

    /**
     * Copies the keyring store and its sidecar to {@code .bak} files in the same
     * owner-only directory.
     *
     * @param keyringFile keyring store path
     * @return {original, backup} path pairs for the store and the sidecar
     * @throws IOException if a copy fails
     */
    private static List<Path[]> backup(Path keyringFile) throws IOException {
        List<Path[]> pairs = new ArrayList<>(2);
        for (Path original : List.of(keyringFile, KeyringFiles.nonceFile(keyringFile))) {
            Path copy = original.resolveSibling(original.getFileName() + BACKUP_SUFFIX);
            Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            pairs.add(new Path[] { original, copy });
        }
        return pairs;
    }

    /**
     * Removes the files of a keyring that was created by a failed generation, so
     * a retry starts from scratch (and asks for a new password again).
     *
     * @param keyringFile keyring store path
     * @throws IOException if a file cannot be deleted
     */
    private static void deleteKeyringFiles(Path keyringFile) throws IOException {
        Files.deleteIfExists(keyringFile);
        Files.deleteIfExists(KeyringFiles.nonceFile(keyringFile));
        Files.deleteIfExists(keyringFile.resolveSibling(keyringFile.getFileName() + ".lock"));
    }

    /**
     * Writes {@code publicKey} to {@code pemFile} as an X.509
     * {@code SubjectPublicKeyInfo} in PEM form ({@code -----BEGIN PUBLIC KEY-----}).
     *
     * <p>
     * The PEM is produced with Bouncy Castle's {@link JcaPEMWriter} — the same
     * mechanism ZeroEcho uses — so it is readable by standard tooling such as
     * {@code openssl}. The public key is not secret; it lets auditors verify a
     * signed manifest without trusting MethodAtlas or ZeroEcho.
     * </p>
     *
     * @param publicKey public key to export; must not be {@code null}
     * @param pemFile   destination PEM file
     * @throws IOException if the PEM cannot be written
     */
    private static void writePublicKeyPem(PublicKey publicKey, Path pemFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(pemFile, StandardCharsets.UTF_8);
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(publicKey);
        }
    }

    /**
     * Resolves the requested algorithm to its canonical ZeroEcho id.
     *
     * @param algorithm requested id, or {@code null} for the default
     * @return canonical id
     * @throws IllegalArgumentException if the algorithm is not supported
     */
    private static String canonicalAlgorithm(String algorithm) {
        String requested = algorithm == null ? DEFAULT_ALGORITHM : algorithm;
        String canonical = SUPPORTED_ALGORITHMS.get(requested.toUpperCase(Locale.ROOT));
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported signing algorithm '" + requested
                    + "'. Supported: " + supportedAlgorithmsHint());
        }
        return canonical;
    }

    /**
     * Resolves the algorithm from the ZeroEcho catalog.
     *
     * @param canonical canonical algorithm id
     * @return the catalog algorithm
     * @throws GeneralSecurityException if the algorithm is absent from the catalog
     */
    private static CryptoAlgorithm requireAsymmetric(String canonical) throws GeneralSecurityException {
        BouncyCastleActivator.init();
        try {
            return CryptoAlgorithms.require(canonical);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Algorithm '" + canonical
                    + "' is not available in the ZeroEcho catalog", e);
        }
    }

    /**
     * Generates a key pair with the algorithm's default key-generation
     * specification.
     *
     * @param alg catalog algorithm
     * @return the generated key pair
     * @throws GeneralSecurityException if the algorithm publishes no default
     *                                  asymmetric key-generation specification
     */
    private static KeyPair generateKeyPair(CryptoAlgorithm alg) throws GeneralSecurityException {
        for (KeyOperationInfo info : alg.keyOperations()) {
            if (info.operation() == KeyOperation.ASYMMETRIC_KEY_PAIR_GENERATE && info.defaultSpec() != null) {
                return generateWith(alg, info.specType(), info.defaultSpec());
            }
        }
        throw new GeneralSecurityException("Algorithm '" + alg.id() + "' has no default asymmetric key generator");
    }

    private static <S extends AlgorithmKeySpec> KeyPair generateWith(CryptoAlgorithm alg, Class<S> specType,
            AlgorithmKeySpec spec) throws GeneralSecurityException {
        return alg.asymmetricKeyPairGenerator(specType).generateKeyPair(specType.cast(spec));
    }
}
