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
 * The current implementation performs a simple structural search for the first
 * opening brace (<code>{</code>) and the last closing brace (<code>}</code>),
 * and returns the substring spanning those positions.
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
    /**
     * Prevents instantiation of this utility class.
     */
    private JsonText() {
    }

    /**
     * Extracts the first JSON object found within a text response.
     *
     * <p>
     * The method scans the supplied text for the first occurrence of an opening
     * brace (<code>{</code>) and the last occurrence of a closing brace
     * (<code>}</code>). The substring between these positions (inclusive) is
     * returned as the extracted JSON object.
     * </p>
     *
     * <p>
     * This approach allows the application to recover structured data even when the
     * model returns additional natural-language content or formatting around the
     * JSON payload.
     * </p>
     *
     * @param text text returned by the AI model
     * @return extracted JSON object as text
     *
     * @throws AiSuggestionException if the input text is empty or if no valid JSON
     *                               object boundaries can be located
     */
    public static String extractFirstJsonObject(String text) throws AiSuggestionException {
        if (text == null || text.isBlank()) {
            throw new AiSuggestionException("Model returned an empty response");
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start < 0 || end < 0 || end < start) {
            throw new AiSuggestionException("Model response does not contain a JSON object: " + text);
        }

        return text.substring(start, end + 1);
    }
}