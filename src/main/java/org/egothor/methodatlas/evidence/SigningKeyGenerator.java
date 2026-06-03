// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import zeroecho.core.CryptoAlgorithm;
import zeroecho.core.CryptoAlgorithms;
import zeroecho.core.spec.AlgorithmKeySpec;
import zeroecho.core.storage.KeyringStore;
import zeroecho.sdk.util.BouncyCastleActivator;

/**
 * Generates an asymmetric signing key pair and stores it in a ZeroEcho
 * {@link KeyringStore} so that {@code -evidence-pack} can later sign manifests
 * with it.
 *
 * <p>
 * The keyring is a plaintext UTF-8 file (see {@link KeyringStore}); it is
 * <em>not</em> a JDK PKCS12/JKS keystore. Because the file stores the private
 * key in clear text, the generator restricts the file to owner read/write
 * (POSIX {@code 0600}) after writing it; on platforms without POSIX
 * permissions a warning is emitted instructing the operator to restrict access
 * manually.
 * </p>
 *
 * <p>
 * Only asymmetric signing algorithms are offered, and the offered set is
 * deliberately limited to the algorithms covered by round-trip tests: the
 * default {@value #DEFAULT_ALGORITHM}, plus {@code RSA}, {@code ECDSA}, and
 * {@code SPHINCS+}. Storing a key requires an algorithm-specific
 * {@code AlgorithmKeySpec}; the spec class is discovered from the algorithm's
 * {@link CryptoAlgorithm#asymmetricBuildersInfo()} and instantiated from the
 * encoded key material by reflection — the same mechanism ZeroEcho's own
 * {@code KeyStoreManagement} tool uses.
 * </p>
 *
 * <p>
 * This type is a non-instantiable utility holder and is not thread-safe with
 * respect to concurrent writers of the same keyring file.
 * </p>
 *
 * @see ZeroEchoSigner
 * @see KeyringStore
 * @since 4.0.0
 */
final class SigningKeyGenerator {

    private static final Logger LOG = Logger.getLogger(SigningKeyGenerator.class.getName());

    /** Default signing algorithm when the caller supplies none. */
    /* default */ static final String DEFAULT_ALGORITHM = "Ed25519";

    /** Owner read/write only — the keyring holds a clear-text private key. */
    private static final String OWNER_ONLY_PERMISSIONS = "rw-------";

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
     * @since 4.0.0
     */
    /* default */ record GeneratedKey(String algorithm, String publicAlias, String privateAlias, Path keyringFile,
            Path publicKeyPem) {
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
     * keyring at {@code keyringFile}.
     *
     * <p>
     * When the keyring file already exists it is loaded and the new entries are
     * appended; otherwise a fresh keyring is created. The private key is written
     * in clear text, so a fresh keyring is created with owner-only permissions
     * (POSIX {@code 0600}) <em>before</em> it is written and re-verified afterwards
     * (see {@link #createOwnerOnlyFile(Path)} and {@link #restrictPermissions(Path)}),
     * leaving no window in which a newly created keyring is world-readable. The
     * public key is
     * additionally exported next to the keyring as an X.509 PEM file
     * ({@code <alias>-public.pem}) so a manifest signature can be verified with
     * standard tooling such as {@code openssl}.
     * </p>
     *
     * @param keyringFile target keyring file; must not be {@code null}
     * @param alias       base alias for the key pair; ZeroEcho stores it as
     *                    {@code alias.pub} and {@code alias.priv}; must not be
     *                    {@code null} or blank
     * @param algorithm   algorithm id ({@code null} selects
     *                    {@value #DEFAULT_ALGORITHM}); must be one of the
     *                    supported ids, case-insensitively
     * @param overwrite   when {@code true}, an existing entry under {@code alias}
     *                    is replaced; when {@code false}, a collision is an error
     * @return a {@link GeneratedKey} describing what was written
     * @throws IOException              if the keyring cannot be read or written
     * @throws GeneralSecurityException if key generation or spec construction fails
     * @throws IllegalArgumentException if {@code alias} is blank or {@code algorithm}
     *                                  is not supported
     */
    /* default */ static GeneratedKey generate(Path keyringFile, String alias, String algorithm, boolean overwrite)
            throws IOException, GeneralSecurityException {
        if (keyringFile == null) {
            throw new IllegalArgumentException("keyringFile must not be null");
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        String canonical = canonicalAlgorithm(algorithm);

        BouncyCastleActivator.init();
        KeyringStore store = Files.exists(keyringFile) ? KeyringStore.load(keyringFile) : new KeyringStore();
        if (!overwrite && store.contains(alias)) {
            throw new IOException("Alias already exists in keyring (use -overwrite to replace): " + alias);
        }

        CryptoAlgorithm alg = requireAsymmetric(canonical);
        KeyPair keyPair = alg.generateKeyPair();

        Class<? extends AlgorithmKeySpec> publicSpecType = findSpecType(alg, "Public");
        Class<? extends AlgorithmKeySpec> privateSpecType = findSpecType(alg, "Private");

        AlgorithmKeySpec publicSpec = importSpec(publicSpecType, keyPair.getPublic().getEncoded(), canonical);
        AlgorithmKeySpec privateSpec = importSpec(privateSpecType, keyPair.getPrivate().getEncoded(), canonical);

        store.putPublic(alias, canonical, publicSpec);
        store.putPrivate(alias, canonical, privateSpec);
        createOwnerOnlyFile(keyringFile);
        store.save(keyringFile);
        restrictPermissions(keyringFile);

        Path publicKeyPem = keyringFile.resolveSibling(alias + PUBLIC_PEM_SUFFIX);
        writePublicKeyPem(keyPair.getPublic(), publicKeyPem);

        return new GeneratedKey(canonical, alias + ".pub", alias + ".priv", keyringFile, publicKeyPem);
    }

    /**
     * Writes {@code publicKey} to {@code pemFile} as an X.509
     * {@code SubjectPublicKeyInfo} in PEM form ({@code -----BEGIN PUBLIC KEY-----}).
     *
     * <p>
     * The PEM is produced with Bouncy Castle's {@link JcaPEMWriter} — the same
     * mechanism ZeroEcho uses — so it is readable by standard tooling such as
     * {@code openssl}. The public key is not secret, so the file is left with
     * default permissions; it lets auditors verify a signed manifest without
     * trusting MethodAtlas or ZeroEcho.
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
     * Resolves the algorithm from the ZeroEcho catalog and verifies it exposes an
     * asymmetric builder.
     *
     * @param canonical canonical algorithm id
     * @return the catalog algorithm
     * @throws GeneralSecurityException if the algorithm is absent from the catalog
     *                                  or has no asymmetric builder
     */
    private static CryptoAlgorithm requireAsymmetric(String canonical) throws GeneralSecurityException {
        final CryptoAlgorithm alg;
        try {
            alg = CryptoAlgorithms.require(canonical);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Algorithm '" + canonical
                    + "' is not available in the ZeroEcho catalog", e);
        }
        if (alg.asymmetricBuildersInfo().isEmpty()) {
            throw new GeneralSecurityException("Algorithm '" + canonical + "' has no asymmetric key builder");
        }
        return alg;
    }

    /**
     * Finds the import-spec class for a public or private key by matching the
     * spec class simple name against {@code marker} (for example {@code "Public"}
     * or {@code "Private"}).
     *
     * @param alg    catalog algorithm to inspect
     * @param marker substring that identifies the key role in the spec class name
     * @return the matching spec class
     * @throws GeneralSecurityException if no matching spec class is registered
     */
    private static Class<? extends AlgorithmKeySpec> findSpecType(CryptoAlgorithm alg, String marker)
            throws GeneralSecurityException {
        for (CryptoAlgorithm.AsymBuilderInfo info : alg.asymmetricBuildersInfo()) {
            if (info.specType.getSimpleName().contains(marker)) {
                return info.specType;
            }
        }
        throw new GeneralSecurityException("Algorithm '" + alg.id() + "' exposes no "
                + marker.toLowerCase(Locale.ROOT) + "-key import spec");
    }

    /**
     * Constructs an {@link AlgorithmKeySpec} from encoded key material using the
     * conventional ZeroEcho factory methods, in order: {@code fromRaw(byte[])},
     * {@code of(byte[])}, then a single {@code byte[]} constructor.
     *
     * @param specType target spec class
     * @param material encoded key bytes (X.509 SPKI for public keys, PKCS#8 for
     *                 private keys)
     * @param algId    algorithm id, used only for diagnostics
     * @return the constructed spec
     * @throws GeneralSecurityException if none of the known factories accept the
     *                                  material
     */
    private static AlgorithmKeySpec importSpec(Class<? extends AlgorithmKeySpec> specType, byte[] material,
            String algId) throws GeneralSecurityException {
        AlgorithmKeySpec viaFactory = invokeStaticFactory(specType, "fromRaw", material);
        if (viaFactory == null) {
            viaFactory = invokeStaticFactory(specType, "of", material);
        }
        if (viaFactory != null) {
            return viaFactory;
        }
        AlgorithmKeySpec viaCtor = invokeByteArrayConstructor(specType, material);
        if (viaCtor != null) {
            return viaCtor;
        }
        throw new GeneralSecurityException("Cannot construct " + specType.getName() + " for algorithm '" + algId
                + "'; no fromRaw(byte[]), of(byte[]) or byte[] constructor accepted the key material");
    }

    /**
     * Invokes a static single-{@code byte[]} factory method named {@code name} on
     * {@code specType}, returning {@code null} when the method is absent.
     *
     * @param specType spec class to invoke on
     * @param name     factory method name
     * @param material encoded key bytes
     * @return the constructed spec, or {@code null} if no such method exists
     * @throws GeneralSecurityException if the method exists but fails to construct
     *                                  a spec
     */
    private static AlgorithmKeySpec invokeStaticFactory(Class<? extends AlgorithmKeySpec> specType, String name,
            byte[] material) throws GeneralSecurityException {
        try {
            Method method = specType.getMethod(name, byte[].class);
            return (AlgorithmKeySpec) method.invoke(null, (Object) material);
        } catch (NoSuchMethodException absent) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new GeneralSecurityException("Factory " + specType.getName() + "." + name
                    + "(byte[]) failed", unwrap(e));
        }
    }

    /**
     * Invokes a single-{@code byte[]} constructor on {@code specType}, returning
     * {@code null} when no such constructor exists.
     *
     * @param specType spec class to instantiate
     * @param material encoded key bytes
     * @return the constructed spec, or {@code null} if no such constructor exists
     * @throws GeneralSecurityException if the constructor exists but fails
     */
    private static AlgorithmKeySpec invokeByteArrayConstructor(Class<? extends AlgorithmKeySpec> specType,
            byte[] material) throws GeneralSecurityException {
        try {
            Constructor<? extends AlgorithmKeySpec> ctor = specType.getConstructor(byte[].class);
            return ctor.newInstance((Object) material);
        } catch (NoSuchMethodException absent) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new GeneralSecurityException("Constructor " + specType.getName() + "(byte[]) failed", unwrap(e));
        }
    }

    /**
     * Unwraps an {@link InvocationTargetException} to its underlying cause so the
     * thrown {@link GeneralSecurityException} carries the real failure.
     *
     * @param e reflective exception
     * @return the underlying cause when present, otherwise {@code e}
     */
    private static Throwable unwrap(ReflectiveOperationException e) {
        return e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
    }

    /**
     * Creates the keyring file with owner-only permissions before it is written,
     * so a freshly generated keyring is never momentarily world-readable while it
     * holds a clear-text private key (closing the create-then-{@code chmod} race).
     *
     * <p>
     * Applies only to a newly created file; when the keyring already exists (a key
     * is being added to it) the file keeps its current permissions and
     * {@link #restrictPermissions(Path)} tightens them after the write. On file
     * systems without POSIX support the file is created normally and
     * {@link #restrictPermissions(Path)} logs a warning.
     * </p>
     *
     * @param keyringFile keyring file to create
     * @throws IOException if the file cannot be created
     */
    private static void createOwnerOnlyFile(Path keyringFile) throws IOException {
        if (Files.exists(keyringFile)) {
            return;
        }
        try {
            Files.createFile(keyringFile,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_PERMISSIONS)));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(keyringFile);
        }
    }

    /**
     * Restricts the keyring file to owner read/write. On POSIX file systems this
     * applies {@code 0600}; on file systems without POSIX support (for example
     * Windows) a warning is logged because the private key is stored in clear
     * text and the operator must restrict access by other means.
     *
     * @param keyringFile file to lock down
     */
    private static void restrictPermissions(Path keyringFile) {
        try {
            Files.setPosixFilePermissions(keyringFile, PosixFilePermissions.fromString(OWNER_ONLY_PERMISSIONS));
        } catch (UnsupportedOperationException notPosix) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Keyring {0} holds a clear-text private key but this file system does not "
                        + "support POSIX permissions; restrict access to your account manually (e.g. via NTFS ACLs).",
                        keyringFile);
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to restrict permissions on keyring " + keyringFile
                        + "; verify it is readable only by you", e);
            }
        }
    }
}
