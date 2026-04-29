package org.egothor.methodatlas.api;

import java.util.Optional;

/**
 * Lazy provider of the source text for a test class.
 *
 * <p>
 * Each {@link DiscoveredMethod} carries one instance that is shared by all
 * methods of the same class. The content is accessed on demand by the
 * orchestration layer (e.g. for AI analysis or content hashing) and is never
 * fetched when those features are disabled.
 * </p>
 *
 * <p>
 * Platform-specific {@link TestDiscovery} implementations supply the content
 * in whatever way is natural for that platform: a file read, an in-memory
 * string captured during AST traversal, etc.
 * </p>
 */
@FunctionalInterface
public interface SourceContent {

    /**
     * Returns the source text of the enclosing test class.
     *
     * @return source text, or {@link Optional#empty()} when the source is
     *         unavailable (e.g. the scanner operates on compiled artifacts)
     */
    Optional<String> get();
}
