package org.egothor.methodatlas.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 *
 * @since 3.0.0
 */
@FunctionalInterface
public interface SourceContent {

    /** Logger used by {@link #ofFile(Path)} to report a failed source read. */
    Logger LOG = Logger.getLogger(SourceContent.class.getName());

    /**
     * Returns the source text of the enclosing test class.
     *
     * @return source text, or {@link Optional#empty()} when the source is
     *         unavailable (e.g. the scanner operates on compiled artifacts)
     */
    Optional<String> get();

    /**
     * Returns a {@code SourceContent} that lazily reads {@code file} as UTF-8 on
     * first access and caches the outcome for every subsequent call.
     *
     * <p>
     * The first {@link #get()} reads the file; the result — the text on success,
     * or {@link Optional#empty()} if the file cannot be read — is memoised, so
     * later calls neither re-read the file nor observe changes made to it after
     * the first read. This gives a stable, read-once view to all consumers
     * (content hashing, AI prompt assembly, …) of the same class, which is the
     * behaviour every bundled discovery plugin relies on. The returned instance
     * is safe to call from multiple threads; the file is read at most once.
     * </p>
     *
     * @param file path of the source file to read; must not be {@code null}
     * @return a caching, lazy {@code SourceContent} for {@code file}; never
     *         {@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     * @since 4.0.0
     */
    static SourceContent ofFile(final Path file) {
        Objects.requireNonNull(file, "file");
        final AtomicReference<Optional<String>> cache = new AtomicReference<>();
        return () -> {
            Optional<String> value = cache.get();
            if (value == null) {
                Optional<String> read;
                try {
                    read = Optional.of(Files.readString(file));
                } catch (IOException e) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.log(Level.WARNING,
                                "Cannot read source file for AI analysis / content hashing: "
                                + file + " (" + e.getMessage() + ")", e);
                    }
                    read = Optional.empty();
                }
                cache.compareAndSet(null, read);
                value = cache.get();
            }
            return value;
        };
    }
}
