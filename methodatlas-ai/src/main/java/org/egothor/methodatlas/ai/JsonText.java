package org.egothor.methodatlas.ai;

/**
 * Utility methods for extracting JSON fragments from free-form text produced by
 * AI model responses.
 *
 * <p>
 * Some AI providers may return textual responses that contain additional
 * commentary or formatting around the actual JSON payload requested by the
 * application. This helper provides a minimal extraction mechanism that
 * isolates the first JSON object found within such responses so that it can be
 * deserialized safely.
 * </p>
 *
 * <p>
 * The implementation performs a depth-counted scan from the first opening brace
 * (<code>{</code>) and returns the first <em>balanced</em> object, tracking
 * string literals and backslash escapes so that braces appearing inside string
 * values are not mistaken for structural delimiters.
 * </p>
 *
 * <p>
 * The method is intentionally tolerant of provider-specific output formats and
 * is primarily used as a defensive measure to recover valid JSON payloads from
 * otherwise well-formed responses.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see AiSuggestionException
 * @see AiClassSuggestion
 */
public final class JsonText {

    /** Opening object delimiter. */
    private static final char OPEN_BRACE = '{';
    /** Closing object delimiter. */
    private static final char CLOSE_BRACE = '}';
    /** String-literal delimiter. */
    private static final char QUOTE = '"';
    /** Escape character within a string literal. */
    private static final char BACKSLASH = '\\';

    /**
     * Prevents instantiation of this utility class.
     */
    private JsonText() {
    }

    /**
     * Extracts the first complete JSON object found within a text response.
     *
     * <p>
     * The method locates the first opening brace (<code>{</code>) and scans
     * forward, counting brace depth until the matching closing brace is reached,
     * returning that balanced substring (inclusive). Braces inside string
     * literals are ignored, and backslash escapes within strings are honoured, so
     * a value such as <code>"a }"</code> does not prematurely terminate the
     * object. When the response contains additional objects or trailing
     * commentary after the first, only the first complete object is returned.
     * </p>
     *
     * @param text text returned by the AI model
     * @return the first balanced JSON object as text
     *
     * @throws AiSuggestionException if the input text is empty, contains no
     *                               opening brace, or the first object is never
     *                               closed (unbalanced)
     */
    public static String extractFirstJsonObject(String text) throws AiSuggestionException {
        if (text == null || text.isBlank()) {
            throw new AiSuggestionException("Model returned an empty response");
        }

        int start = text.indexOf(OPEN_BRACE);
        if (start < 0) {
            throw new AiSuggestionException("Model response does not contain a JSON object: " + text);
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == BACKSLASH) {
                    escaped = true;
                } else if (c == QUOTE) {
                    inString = false;
                }
                continue;
            }
            if (c == QUOTE) {
                inString = true;
            } else if (c == OPEN_BRACE) {
                depth++;
            } else if (c == CLOSE_BRACE) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        throw new AiSuggestionException("Model response does not contain a JSON object: " + text);
    }
}