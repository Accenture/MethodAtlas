package org.egothor.methodatlas.ai;

/**
 * Checked exception indicating failure during AI-based suggestion generation or
 * related AI subsystem operations.
 *
 * <p>
 * This exception is used throughout the AI integration layer to report provider
 * initialization failures, taxonomy loading errors, connectivity problems,
 * malformed provider responses, and other conditions that prevent successful
 * generation of AI-based classification results.
 * </p>
 *
 * <p>
 * The exception is declared as a checked exception because such failures are
 * part of the normal operational contract of the AI subsystem and callers are
 * expected to either handle them explicitly or convert them into higher-level
 * application failures when AI support is mandatory.
 * </p>
 *
 * @see AiSuggestionEngine
 * @see AiProviderClient
 * @see AiSuggestionEngineImpl
 */
public final class AiSuggestionException extends Exception {

    private static final long serialVersionUID = 6365662915183382629L;

    /**
     * Creates a new exception with the specified detail message.
     *
     * @param message detail message describing the failure
     */
    public AiSuggestionException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified detail message and cause.
     *
     * @param message detail message describing the failure
     * @param cause   underlying cause of the failure
     */
    public AiSuggestionException(String message, Throwable cause) {
        super(message, cause);
    }
}