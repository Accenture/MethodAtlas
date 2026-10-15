package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Resolves the taxonomy version identifier that is stamped into each
 * AI-classified output record (the {@code ai_taxonomy_version} column / field).
 *
 * <p>
 * The identifier answers "under which controlled vocabulary was this record
 * classified?" and so must change whenever that vocabulary changes:
 * </p>
 * <ul>
 * <li><strong>Built-in taxonomies</strong> report their explicit revision, e.g.
 * {@code built-in/default@1}; see {@link DefaultSecurityTaxonomy#version()} and
 * {@link OptimizedSecurityTaxonomy#version()}.</li>
 * <li><strong>Custom taxonomy files</strong> (the free-text {@code -ai-taxonomy}
 * format has no version field) report a content fingerprint,
 * {@code sha256:<64 lowercase hex>}, of the file's raw bytes. This is the same
 * digest the reproducibility receipt records as {@code taxonomyFileSha256}, so
 * the two always agree.</li>
 * </ul>
 *
 * <p>
 * This class is a stateless, thread-safe utility holder.
 * </p>
 *
 * @see AiOptions#taxonomyFile()
 * @see AiOptions#taxonomyMode()
 * @since 6.1.0
 */
public final class TaxonomyVersion {

    /** Prefix of a content-fingerprint version identifier. */
    public static final String FINGERPRINT_PREFIX = "sha256:";

    private TaxonomyVersion() {
    }

    /**
     * Returns the version identifier of a built-in taxonomy.
     *
     * @param mode built-in taxonomy variant; must not be {@code null}
     * @return version identifier such as {@code built-in/default@1}
     * @throws NullPointerException if {@code mode} is {@code null}
     */
    public static String builtIn(AiOptions.TaxonomyMode mode) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case DEFAULT -> DefaultSecurityTaxonomy.version();
            case OPTIMIZED -> OptimizedSecurityTaxonomy.version();
        };
    }

    /**
     * Returns the version identifier of a custom taxonomy file: the SHA-256 of its
     * raw bytes, prefixed with {@value #FINGERPRINT_PREFIX}.
     *
     * @param taxonomyFile path to the taxonomy file; must not be {@code null}
     *                     and must be a readable regular file
     * @return {@code sha256:} followed by 64 lowercase hex characters; the same
     *         file content always yields the same value
     * @throws IOException          if the file cannot be read
     * @throws NullPointerException if {@code taxonomyFile} is {@code null}
     */
    public static String ofFile(Path taxonomyFile) throws IOException {
        Objects.requireNonNull(taxonomyFile, "taxonomyFile");
        byte[] bytes = Files.readAllBytes(taxonomyFile);
        try {
            return FINGERPRINT_PREFIX
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE specification; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Resolves the version of the taxonomy that the supplied options select: the
     * custom file when {@link AiOptions#taxonomyFile()} is set, otherwise the
     * built-in {@link AiOptions#taxonomyMode()}.
     *
     * @param options AI options; must not be {@code null}
     * @return version identifier; never {@code null} or blank
     * @throws IOException          if a configured taxonomy file cannot be read
     * @throws NullPointerException if {@code options} is {@code null}
     */
    public static String resolve(AiOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        if (options.taxonomyFile() != null) {
            return ofFile(options.taxonomyFile());
        }
        return builtIn(options.taxonomyMode());
    }
}
