// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure utility helpers for content fingerprints and scan-root path prefixes.
 *
 * <p>
 * Two unrelated-but-small concerns live here because both are stateless,
 * dependency-free, and named-focused. Pulling them into one
 * single-responsibility utility class keeps each concern visible without
 * the cost of constructor injection — both methods are pure functions
 * mandated by their specifications (SHA-256 for hashing, OS-native
 * relativisation for the prefix), so there is nothing to substitute:
 * </p>
 * <ul>
 *   <li>{@link #hashClass(String)} — computes a SHA-256 fingerprint of the
 *       canonical pretty-printed AST text of a class. This is the value
 *       exposed as {@code content_hash} in CSV / SARIF output and used as the
 *       cache key by {@link org.egothor.methodatlas.AiResultCache}.</li>
 *   <li>{@link #filePrefix(List)} — derives the forward-slashed path prefix
 *       used in GitHub Actions workflow annotations and SARIF location URIs,
 *       relativised to the current working directory so that paths resolve
 *       to inline positions in PR diffs.</li>
 * </ul>
 *
 * <p>
 * Both methods are pure functions and therefore exposed as {@code static}.
 * Test code calls them directly with handcrafted inputs; no dependency
 * injection is needed because there is nothing to substitute — the SHA-256
 * implementation is mandated by the Java SE specification and the path
 * relativisation has only one correct answer.
 * </p>
 *
 * @see ScanOrchestrator
 * @since 1.0.0
 */
public final class ContentHasher {

    private ContentHasher() {
        // Utility class; instantiation is prevented to make the static
        // intent obvious to callers and to satisfy PMD.
    }

    /**
     * Computes a SHA-256 content fingerprint of a class source string.
     *
     * <p>
     * The input is expected to be the canonical AST text of the class — for
     * Java this is the JavaParser pretty-printed form, which normalises
     * whitespace and comments so that semantically equivalent classes that
     * differ only in formatting produce identical hashes. The output is
     * suitable for incremental scanning, AI-cache lookups, and audit
     * traceability across two pipeline stages.
     * </p>
     *
     * <p>
     * Algorithm: SHA-256 (FIPS 180-4) applied to the UTF-8 bytes of
     * {@code classSource}. Time complexity is {@code O(n)} in the source
     * size. The result is a 64-character lowercase hexadecimal string.
     * </p>
     *
     * @param classSource canonical pretty-printed form of the class
     *                    declaration; must not be {@code null}
     * @return 64-character lowercase hexadecimal SHA-256 digest; never
     *         {@code null}, never empty
     * @throws IllegalStateException if SHA-256 is unavailable — never in
     *                               practice, because SHA-256 is mandated by
     *                               the Java SE specification
     */
    public static String hashClass(String classSource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(classSource.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Computes a SHA-256 content fingerprint of the raw bytes of a file.
     *
     * <p>
     * The algorithm and output format are identical to
     * {@link #hashClass(String)} — SHA-256 (FIPS 180-4) followed by lowercase
     * hexadecimal encoding via {@link HexFormat#of()}. The only difference is
     * the input source: this overload reads bytes from disk verbatim rather
     * than taking an in-memory canonical class source string. Use it to
     * fingerprint configuration artefacts such as override YAML files,
     * taxonomy files, and AI cache CSVs whose semantic identity is the
     * entire file contents, not a parsed/normalised view.
     * </p>
     *
     * @param file path to the file to fingerprint; must not be {@code null}
     *             and must point to a readable regular file
     * @return 64-character lowercase hexadecimal SHA-256 digest; never
     *         {@code null}, never empty
     * @throws IOException           if {@code file} cannot be read
     * @throws IllegalStateException if SHA-256 is unavailable — never in
     *                               practice, because SHA-256 is mandated by
     *                               the Java SE specification
     */
    public static String hashFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Derives the forward-slashed path prefix used in GitHub Actions
     * workflow annotations and SARIF location URIs.
     *
     * <p>
     * The first configured scan root is relativised against the current
     * working directory and converted to forward slashes. A trailing slash
     * is appended unless the prefix is empty. The resulting string is
     * concatenated with the per-method relative path to produce annotation
     * paths that GitHub resolves to inline positions in the PR diff
     * (for example {@code src/test/java/com/acme/AuthTest.java}).
     * </p>
     *
     * <p>
     * When {@code roots} is empty the returned prefix is the empty string,
     * which produces unprefixed annotation paths — appropriate when no scan
     * root was configured because the caller is operating on the current
     * directory directly.
     * </p>
     *
     * <p>
     * On Windows, scan roots that resolve to a different drive than the
     * current working directory cannot be relativised. The method falls
     * back to the absolute path of the root in that case rather than
     * throwing.
     * </p>
     *
     * @param roots configured scan roots; must not be {@code null}; may be
     *              empty
     * @return forward-slash path ending with {@code /}, or the empty string
     *         when {@code roots} is empty
     */
    public static String filePrefix(List<Path> roots) {
        if (roots.isEmpty()) {
            return "";
        }
        Path root = roots.get(0).toAbsolutePath().normalize();
        String prefix;
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            prefix = cwd.relativize(root).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            // Different drive on Windows — fall back to the absolute path.
            prefix = root.toString().replace('\\', '/');
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix;
    }
}
