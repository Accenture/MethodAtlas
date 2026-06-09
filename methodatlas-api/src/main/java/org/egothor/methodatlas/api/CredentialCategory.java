package org.egothor.methodatlas.api;

/**
 * Coarse classification of a detected credential candidate.
 *
 * @since 4.1.0
 */
public enum CredentialCategory {
    /** A provider-specific token with a recognised fixed format (e.g. an AWS access key id). */
    PROVIDER_TOKEN,
    /** A PEM-style private key block. */
    PRIVATE_KEY,
    /** A credential supplied through a keyword assignment such as {@code password = "..."}. */
    PASSWORD_ASSIGNMENT,
    /** A credential embedded inside a connection string or URL. */
    CONNECTION_STRING,
    /** A high-entropy literal with no provider-specific or keyword anchor. */
    HIGH_ENTROPY,
    /** Anything not covered by a more specific category. */
    OTHER
}
