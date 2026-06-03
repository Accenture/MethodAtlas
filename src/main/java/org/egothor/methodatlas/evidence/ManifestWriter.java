// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the {@code manifest.sha256} file that anchors an evidence pack.
 *
 * <p>
 * The manifest enumerates every file in the pack directory other than
 * itself and any {@code .signed} sibling, listing one entry per line in the
 * format {@code <hex-digest>  <filename>}. Files are sorted lexicographically
 * by filename so the manifest is byte-stable between runs that produce the
 * same artefacts.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the evidence module needs to call
 * this writer.
 * </p>
 */
final class ManifestWriter {

    /** Java standard digest algorithm name for SHA-256. */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Buffer size used when streaming files through the digest. */
    private static final int BUFFER_SIZE = 8192;

    /** Filename suffix marking a signed-envelope sibling that must be excluded. */
    private static final String SIGNED_SUFFIX = ".signed";

    /** Two-space separator required by the shasum/coreutils manifest format. */
    private static final String SEPARATOR = "  ";

    private ManifestWriter() {
        // Utility class — instantiation makes no sense.
    }

    /**
     * Writes a SHA-256 manifest of every regular file in {@code dir}, except
     * {@code manifestFile} itself and any file whose name ends with
     * {@code .signed}.
     *
     * @param dir          directory to enumerate; must exist
     * @param manifestFile output path; overwritten if it already exists
     * @throws IOException if directory listing, digest computation, or
     *                     writing fails
     */
    /* default */ static void write(Path dir, Path manifestFile) throws IOException {
        List<Path> files = collectFiles(dir, manifestFile);
        try (BufferedWriter writer = Files.newBufferedWriter(manifestFile, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String digest = sha256Hex(file);
                writer.write(digest);
                writer.write(SEPARATOR);
                writer.write(filenameOf(file));
                writer.write('\n');
            }
        }
    }

    /**
     * Returns the filename portion of {@code p} as a non-null string. Used
     * defensively because {@link Path#getFileName()} can theoretically
     * return {@code null} (root paths); inside {@link Files#list(Path)}
     * results that case is unreachable, but SpotBugs flags the chained
     * {@code toString()} on a possibly-null return.
     *
     * @param p path to inspect
     * @return filename as string; never {@code null}
     */
    private static String filenameOf(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * Lists candidate files in lexicographic order.
     *
     * @param dir          directory to enumerate
     * @param manifestFile manifest path to exclude
     * @return sorted list of files eligible for inclusion
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> collectFiles(Path dir, Path manifestFile) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(manifestFile))
                    .filter(p -> !filenameOf(p).endsWith(SIGNED_SUFFIX))
                    .forEach(files::add);
        }
        Collections.sort(files, (a, b) -> filenameOf(a).compareTo(filenameOf(b)));
        return files;
    }

    /**
     * Computes a lowercase-hex SHA-256 digest of the supplied file.
     *
     * @param file path to digest
     * @return 64-character lowercase hex string
     * @throws IOException if reading fails or SHA-256 is unavailable
     */
    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Creates a fresh SHA-256 digest, wrapping the checked exception into an
     * {@link IOException} so callers do not need a separate catch clause.
     *
     * @return new {@link MessageDigest} instance
     * @throws IOException when SHA-256 is unexpectedly unavailable (never on a
     *                     standard JDK)
     */
    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
