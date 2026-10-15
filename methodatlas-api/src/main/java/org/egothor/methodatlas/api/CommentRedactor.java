package org.egothor.methodatlas.api;

import java.nio.file.Path;

/**
 * Language-specific service that blanks every comment in a source text before
 * it is sent to an AI provider (data minimisation).
 *
 * <p>
 * Implementations are discovered at runtime via {@link java.util.ServiceLoader}
 * from {@code META-INF/services/org.egothor.methodatlas.api.CommentRedactor}.
 * </p>
 *
 * <h2>Redaction contract</h2>
 * <ul>
 *   <li>Every comment is replaced by U+0020 space characters.</li>
 *   <li>Every line terminator inside a comment is preserved unchanged.</li>
 *   <li>The result has exactly the same number of {@code char}s as the input,
 *       so every offset, line and column is identical before and after.</li>
 *   <li>The implementation must be string-aware: comment markers inside string
 *       or character literals are not comments and are left untouched.</li>
 *   <li>The given {@code String} is transformed; the file is not re-read.</li>
 *   <li>If the implementation cannot guarantee the above for a given input it
 *       throws {@link CommentRedactionException}; it must never return a
 *       partly redacted text.</li>
 * </ul>
 *
 * <p>
 * A language without a registered redactor cannot be scanned with
 * {@code -redact-comments}: the run is aborted (fail-closed).
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <p>
 * {@link #configure(TestDiscoveryConfig)} is called once after loading, before
 * {@link #supports(Path)} or {@link #redact(Path, String)}.
 * </p>
 *
 * @see CommentRedactionException
 * @see SourcePatcher
 * @since 4.0.0
 */
public interface CommentRedactor {

    /**
     * Returns the unique identifier of this redactor; two redactors with the
     * same identifier cause an {@link IllegalStateException} at load time.
     *
     * @return non-null, non-empty identifier, unique across loaded redactors
     */
    String pluginId();

    /**
     * Receives the runtime configuration; the default implementation is a
     * no-op.
     *
     * @param config runtime configuration; never {@code null}
     */
    default void configure(TestDiscoveryConfig config) {
        // no-op by default
    }

    /**
     * Returns {@code true} if this redactor handles the given source file.
     *
     * @param sourceFile path to the candidate source file; never {@code null}
     * @return {@code true} if this redactor accepts the file
     */
    boolean supports(Path sourceFile);

    /**
     * Returns {@code source} with every comment replaced by spaces, preserving
     * newlines and the exact character count.
     *
     * @param sourceFile path of the file the text came from; used only to
     *                   select dialect, never read; never {@code null}
     * @param source     full source text; never {@code null}
     * @return redacted text of the same length as {@code source}; never
     *         {@code null}
     * @throws CommentRedactionException if redaction cannot be guaranteed
     */
    String redact(Path sourceFile, String source) throws CommentRedactionException;
}
