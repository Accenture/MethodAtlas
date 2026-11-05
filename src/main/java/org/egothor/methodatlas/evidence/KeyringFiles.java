// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import zeroecho.core.storage.KeyringException;
import zeroecho.core.storage.KeyringPassword;
import zeroecho.core.storage.KeyringStore;

/**
 * File-level helpers for encrypted ZeroEcho 1.2 keyrings: opening and creating
 * them with actionable error messages, detecting the retired plaintext format,
 * and moving a keyring through a CI secret as a text bundle.
 *
 * <h2>On-disk layout</h2>
 * <p>
 * A keyring at {@code dir/name} consists of the encrypted store {@code name},
 * its authenticated nonce-reservation sidecar {@code name.nonce}, and a
 * {@code name.lock} file that ZeroEcho holds while the keyring is open. The
 * directory and both data files must be accessible only by their owner (POSIX
 * {@code 0700}/{@code 0600}, or an owner-only ACL on Windows); ZeroEcho refuses
 * anything else.
 * </p>
 *
 * <h2>Secret bundle</h2>
 * <p>
 * A CI platform secret carries one text value, so {@link #exportBundle(Path)}
 * encodes the store and its sidecar as Base64 lines under a
 * {@value #BUNDLE_HEADER} header. {@link #restoreBundle(String)} writes them
 * into a fresh owner-only temporary directory that is deleted again when the
 * returned {@link RestoredKeyring} is closed. The bundle is still encrypted with
 * the keyring password; it is not a plaintext key.
 * </p>
 *
 * @since 6.2.0
 */
final class KeyringFiles {

    private static final Logger LOG = Logger.getLogger(KeyringFiles.class.getName());

    /** First line of a keyring secret bundle. */
    /* default */ static final String BUNDLE_HEADER = "# MethodAtlas keyring secret v1";

    /** Bundle line prefix for the encrypted keyring store. */
    private static final String KEYRING_PREFIX = "keyring=";

    /** Bundle line prefix for the nonce-reservation sidecar. */
    private static final String NONCE_PREFIX = "nonce=";

    /** Suffix of ZeroEcho's nonce-reservation sidecar file. */
    private static final String NONCE_SUFFIX = ".nonce";

    /** Header line of the plaintext keyring format used by ZeroEcho 1.1. */
    private static final String LEGACY_HEADER = "# KeyringStore";

    /** Magic bytes that open an encrypted ZeroEcho 1.2 keyring. */
    private static final byte[] KEYRING_MAGIC = "ZEKRING2".getBytes(StandardCharsets.US_ASCII);

    /** Upper bound on a restored keyring image, matching ZeroEcho's own limit. */
    private static final int MAX_IMAGE_BYTES = 128 * 1024 * 1024;

    /** File name of a keyring restored from a secret bundle. */
    private static final String RESTORED_NAME = "keyring";

    /** Name prefix of the temporary directory a secret bundle is restored into. */
    private static final String TEMP_PREFIX = "methodatlas-keyring-";

    private static final String OWNER_ONLY_DIR = "rwx------";
    private static final String OWNER_ONLY_FILE = "rw-------";

    /** Prevents instantiation. */
    private KeyringFiles() {
    }

    /**
     * A keyring materialised from a secret bundle in a private temporary
     * directory. Closing it deletes the directory and everything in it; a
     * deletion failure is logged rather than thrown, so cleanup can never
     * discard a signer that was already built from the restored keyring.
     *
     * @param directory owner-only temporary directory
     * @param keyring   path of the restored keyring store inside {@code directory}
     */
    /* default */ record RestoredKeyring(Path directory, Path keyring) implements AutoCloseable {
        @Override
        public void close() {
            deleteTree(directory);
        }
    }

    /**
     * Deletes {@code directory} and its direct children (the restored store, its
     * sidecar and lock file), logging instead of throwing on failure.
     *
     * @param directory directory to delete
     */
    private static void deleteTree(Path directory) {
        try {
            try (Stream<Path> entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Could not delete the temporary keyring copy in {0}; remove it manually "
                        + "(it is still encrypted): {1}", new Object[] { directory, e.getMessage() });
            }
        }
    }

    /**
     * Opens an existing encrypted keyring.
     *
     * @param keyringFile keyring store path
     * @param password    unlock password
     * @return the open keyring; the caller must close it
     * @throws IOException              with an actionable message when the file is
     *                                  missing, uses the retired plaintext format,
     *                                  has unsafe permissions, or the password is wrong
     * @throws GeneralSecurityException if cryptographic initialisation fails
     */
    /* default */ static KeyringStore open(Path keyringFile, KeyringPassword password)
            throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(keyringFile)) {
            throw new IOException("Keyring file not found: " + keyringFile);
        }
        rejectLegacy(keyringFile);
        try {
            return KeyringStore.open(keyringFile, password);
        } catch (KeyringException e) {
            throw explain(e, keyringFile);
        }
    }

    /**
     * Creates a new encrypted keyring. A missing parent directory is created
     * owner-only.
     *
     * @param keyringFile keyring store path; must not exist yet
     * @param password    password that will protect the keyring
     * @return the open, empty keyring; the caller must close it
     * @throws IOException              with an actionable message when the
     *                                  location is unsuitable
     * @throws GeneralSecurityException if cryptographic initialisation fails
     */
    /* default */ static KeyringStore create(Path keyringFile, KeyringPassword password)
            throws IOException, GeneralSecurityException {
        try {
            return KeyringStore.create(keyringFile, password);
        } catch (KeyringException e) {
            throw explain(e, keyringFile);
        }
    }

    /**
     * Encodes a keyring and its nonce sidecar as a single text value suitable for
     * a CI platform secret.
     *
     * @param keyringFile keyring store path
     * @return the bundle text, ending with a line break
     * @throws IOException if either file is missing, the store is not an encrypted
     *                     ZeroEcho 1.2 keyring, or reading fails
     */
    /* default */ static String exportBundle(Path keyringFile) throws IOException {
        Path nonce = sibling(keyringFile, NONCE_SUFFIX);
        if (!Files.isRegularFile(keyringFile)) {
            throw new IOException("Keyring file not found: " + keyringFile);
        }
        rejectLegacy(keyringFile);
        byte[] image = Files.readAllBytes(keyringFile);
        if (!startsWith(image, KEYRING_MAGIC)) {
            throw new IOException(keyringFile + " is not an encrypted ZeroEcho keyring");
        }
        if (!Files.isRegularFile(nonce)) {
            throw new IOException("Keyring sidecar " + nonce + " is missing; a keyring is only usable together "
                    + "with its .nonce file");
        }
        Base64.Encoder encoder = Base64.getEncoder();
        String keyringLine = KEYRING_PREFIX + encoder.encodeToString(image);
        String nonceLine = NONCE_PREFIX + encoder.encodeToString(Files.readAllBytes(nonce));
        return BUNDLE_HEADER + '\n' + keyringLine + '\n' + nonceLine + '\n';
    }

    /**
     * Restores a keyring secret bundle into a fresh owner-only temporary
     * directory.
     *
     * @param bundle bundle text produced by {@link #exportBundle(Path)}
     * @return the restored keyring; close it to delete the temporary copy
     * @throws IOException if the bundle is malformed or the files cannot be written
     */
    // The restored keyring is returned open on success, so try-with-resources does
    // not fit; the finally block deletes it on every failure path instead.
    @SuppressWarnings("PMD.UseTryWithResources")
    /* default */ static RestoredKeyring restoreBundle(String bundle) throws IOException {
        if (bundle == null || bundle.isBlank()) {
            throw new IOException("Keyring secret is empty");
        }
        String trimmed = bundle.strip();
        if (trimmed.startsWith(LEGACY_HEADER)) {
            throw legacyFormat("the keyring secret");
        }
        List<String> lines = trimmed.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() != 3 || !BUNDLE_HEADER.equals(lines.get(0)) || !lines.get(1).startsWith(KEYRING_PREFIX)
                || !lines.get(2).startsWith(NONCE_PREFIX)) {
            throw new IOException("Keyring secret is not a MethodAtlas keyring bundle; create it with "
                    + ExportKeyringSecretCommand.FLAG_EXPORT_KEYRING_SECRET + " <keyring-file>");
        }
        byte[] image = decode(lines.get(1).substring(KEYRING_PREFIX.length()));
        byte[] nonce = decode(lines.get(2).substring(NONCE_PREFIX.length()));
        if (image.length > MAX_IMAGE_BYTES || !startsWith(image, KEYRING_MAGIC)) {
            throw new IOException("Keyring secret does not contain an encrypted ZeroEcho keyring");
        }

        boolean posix = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                .supportsFileAttributeView(PosixFileAttributeView.class);
        Path directory = posix
                ? Files.createTempDirectory(TEMP_PREFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_DIR)))
                : Files.createTempDirectory(TEMP_PREFIX);
        RestoredKeyring restored = new RestoredKeyring(directory, directory.resolve(RESTORED_NAME));
        boolean complete = false;
        try {
            if (!posix) {
                restrictAclToOwner(directory, true);
            }
            writeOwnerOnly(restored.keyring(), image, posix);
            writeOwnerOnly(sibling(restored.keyring(), NONCE_SUFFIX), nonce, posix);
            complete = true;
            return restored;
        } finally {
            if (!complete) {
                restored.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /**
     * Fails fast with a migration hint when {@code file} uses the plaintext
     * keyring format of ZeroEcho 1.1 (MethodAtlas 6.1 and earlier).
     *
     * @param file candidate keyring file
     * @throws IOException if the file is a legacy plaintext keyring or unreadable
     */
    private static void rejectLegacy(Path file) throws IOException {
        byte[] head = new byte[LEGACY_HEADER.length()];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read == head.length && LEGACY_HEADER.equals(new String(head, StandardCharsets.US_ASCII))) {
            throw legacyFormat(file.toString());
        }
    }

    private static IOException legacyFormat(String what) {
        return new IOException(what + " uses the plaintext keyring format of MethodAtlas 6.1 and earlier, which "
                + "ZeroEcho 1.2 no longer reads. Generate a new encrypted keyring with "
                + GenSigningKeyCommand.FLAG_GEN_SIGNING_KEY + ", publish its new public key to your auditors, "
                + "and destroy the old plaintext keyring.");
    }

    /**
     * Translates ZeroEcho's redacted keyring error codes into actionable messages.
     *
     * @param e           ZeroEcho failure
     * @param keyringFile keyring the operation targeted
     * @return an {@link IOException} carrying {@code e} as its cause
     */
    /* default */ static IOException explain(KeyringException e, Path keyringFile) {
        Path dir = keyringFile.toAbsolutePath().normalize().getParent();
        String message = switch (e.code()) {
            case KEYRING_UNLOCK_FAILED -> "Wrong keyring password for " + keyringFile;
            case KEYRING_FILESYSTEM_UNSUPPORTED -> "Keyring location " + keyringFile + " is not private: the "
                    + "directory " + dir + " and the keyring files must be accessible only by you, and must not be "
                    + "symbolic or hard links. On Linux/macOS run 'chmod 700 " + dir + "' and 'chmod 600' on the "
                    + "keyring and its .nonce file, or keep keyrings in a dedicated directory such as "
                    + "~/.methodatlas/keys/.";
            case KEYRING_ALREADY_OPEN -> "Keyring " + keyringFile + " is in use by another process";
            case KEYRING_FORMAT_INVALID -> "Keyring " + keyringFile + " is corrupted or does not match its .nonce "
                    + "file; restore both files together from the same backup";
            case KEYRING_IO_FAILED -> "Keyring " + keyringFile + " cannot be accessed; it must not exist yet when "
                    + "a new keyring is created, and its directory must exist when an existing keyring is opened";
            default -> "Keyring operation failed on " + keyringFile + ": "
                    + e.code().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
        return new IOException(message, e);
    }

    // -------------------------------------------------------------------------
    // Owner-only file creation
    // -------------------------------------------------------------------------

    private static void writeOwnerOnly(Path file, byte[] content, boolean posix) throws IOException {
        if (posix) {
            Files.createFile(file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_FILE)));
        } else {
            Files.createFile(file);
            restrictAclToOwner(file, false);
        }
        Files.write(file, content);
    }

    /**
     * Replaces the ACL of {@code path} with a single owner ALLOW entry, the same
     * protection ZeroEcho verifies on Windows. On a directory the entry is
     * inheritable so files created inside it are owner-only too.
     */
    private static void restrictAclToOwner(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("File system of " + path + " supports neither POSIX permissions nor ACLs");
        }
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT));
        }
        view.setAcl(List.of(builder.build()));
    }

    // -------------------------------------------------------------------------
    // Small utilities
    // -------------------------------------------------------------------------

    private static Path sibling(Path keyringFile, String suffix) {
        return keyringFile.resolveSibling(keyringFile.getFileName() + suffix);
    }

    /**
     * Returns the nonce-reservation sidecar that must accompany a keyring.
     *
     * @param keyringFile keyring store path
     * @return the {@code .nonce} sidecar path
     */
    /* default */ static Path nonceFile(Path keyringFile) {
        return sibling(keyringFile, NONCE_SUFFIX);
    }

    private static byte[] decode(String base64) throws IOException {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IOException("Keyring secret contains invalid Base64 data", e);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
