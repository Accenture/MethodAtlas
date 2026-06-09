package org.egothor.methodatlas.api;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Source of credential candidates for one scanned file.
 *
 * <p>
 * Implementations are deterministic and AI-free: identical input must yield
 * identical candidates on every run. Unlike {@link TestDiscovery}, a detector
 * does not walk the file tree — the orchestration layer selects files (by mask)
 * and presents each as a {@link CredentialScanUnit}.
 * </p>
 *
 * <p>
 * Providers are discovered via {@link java.util.ServiceLoader}; each provider JAR
 * ships a {@code META-INF/services/org.egothor.methodatlas.api.CredentialDetector}
 * registration. An empty provider set is legitimate and disables the feature.
 * </p>
 *
 * @since 4.1.0
 */
public interface CredentialDetector extends Closeable {

    /**
     * Returns the unique identifier of this detector. Must be unique across all
     * detectors on the classpath; the orchestration layer rejects duplicates.
     *
     * @return non-null, non-empty identifier
     */
    String detectorId();

    /**
     * Configures this detector before the first {@link #detect(CredentialScanUnit)} call.
     * The default is a no-op.
     *
     * @param config runtime configuration; never {@code null}
     */
    default void configure(CredentialDetectorConfig config) {
        // default: no-op
    }

    /**
     * Scans a single source unit and returns every credential candidate found.
     *
     * @param unit the file to scan; never {@code null}
     * @return candidates in source order; never {@code null}; may be empty
     */
    List<CredentialCandidate> detect(CredentialScanUnit unit);

    /**
     * Returns {@code true} if any prior {@link #detect} call encountered a
     * non-fatal error.
     *
     * @return {@code true} when at least one unit could not be fully processed
     */
    boolean hadErrors();

    /**
     * Releases any resources held by this detector. The default is a no-op.
     *
     * @throws IOException if releasing a resource fails
     */
    @Override
    default void close() throws IOException {
        // default: no-op
    }
}
