package org.egothor.methodatlas.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runtime configuration handed to a {@link CredentialDetector} before its first
 * {@link CredentialDetector#detect(CredentialScanUnit)} call.
 *
 * @param entropyThreshold default Shannon-entropy floor (bits per character) for
 *                         unanchored high-entropy rules that do not specify their
 *                         own threshold
 * @param customCatalog    optional path to a user-supplied rule catalog that
 *                         replaces the bundled one; {@link Optional#empty()} uses
 *                         the bundled catalog
 * @param properties       open-ended plugin-specific settings; never {@code null}
 * @since 4.1.0
 */
public record CredentialDetectorConfig(
        double entropyThreshold, Optional<Path> customCatalog, Map<String, List<String>> properties) {

    /**
     * Defensively deep-copies {@code properties} to an unmodifiable map of
     * unmodifiable lists.
     *
     * @throws NullPointerException if {@code customCatalog} or {@code properties} is {@code null}
     */
    public CredentialDetectorConfig {
        Objects.requireNonNull(customCatalog, "customCatalog");
        Objects.requireNonNull(properties, "properties");
        properties = properties.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
