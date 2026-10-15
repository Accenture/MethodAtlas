package org.egothor.methodatlas.api;

/**
 * Signals that a {@link CommentRedactor} could not guarantee that every comment
 * in a source text was blanked.
 *
 * <p>
 * The condition is checked, not recoverable: the orchestration layer treats it
 * as fail-closed and aborts the run rather than send possibly un-redacted
 * source to an AI provider.
 * </p>
 *
 * @see CommentRedactor
 * @since 4.0.0
 */
public class CommentRedactionException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message human-readable reason; should name the construct or offset
     *                that defeated redaction
     */
    public CommentRedactionException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message human-readable reason
     * @param cause   underlying failure; may be {@code null}
     */
    public CommentRedactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
