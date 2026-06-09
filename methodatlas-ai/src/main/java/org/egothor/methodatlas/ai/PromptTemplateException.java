package org.egothor.methodatlas.ai;

/**
 * Thrown when a user-supplied prompt template is structurally invalid — it uses an
 * unknown placeholder, omits a required one, lacks the JSON structural anchor the
 * response parser depends on, or is empty.
 *
 * <p>
 * This is a programmer/configuration error surfaced at load time (fail-fast), so it
 * is unchecked.
 * </p>
 *
 * @since 4.1.0
 */
public class PromptTemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a human-readable description of the validation
     * failure.
     *
     * @param message the detail message; should name the template kind and the
     *                specific problem(s)
     */
    public PromptTemplateException(String message) {
        super(message);
    }
}
