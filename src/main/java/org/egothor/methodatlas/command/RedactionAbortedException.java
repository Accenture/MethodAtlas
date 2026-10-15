// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

/**
 * Thrown when {@code -redact-comments} is active and comment redaction cannot
 * be guaranteed for a source file, so the run must abort before any source is
 * sent to an AI provider (fail-closed).
 *
 * <p>
 * Typical causes are a scanned language with no registered
 * {@link org.egothor.methodatlas.api.CommentRedactor}, or a redactor that
 * reports it cannot redact the given text.
 * </p>
 *
 * @see CommentRedaction
 * @since 4.1.0
 */
public final class RedactionAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message operator-facing reason, naming the file and language
     */
    public RedactionAbortedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message operator-facing reason, naming the file and language
     * @param cause   the underlying redaction failure; may be {@code null}
     */
    public RedactionAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
