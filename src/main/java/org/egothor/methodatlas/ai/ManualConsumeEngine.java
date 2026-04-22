package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Handles the consume phase of the manual AI workflow.
 *
 * <p>
 * For each test class this engine looks for a response file
 * {@code <fqcn>.response.txt} in the configured response directory. If the file
 * is present its content is parsed as an AI classification result and the
 * extracted suggestions are returned normally. If the file is absent an empty
 * suggestion is returned, which results in blank AI columns for that class in
 * the final CSV.
 * </p>
 *
 * <p>
 * This engine implements {@link AiSuggestionEngine} so it can be used as a
 * drop-in replacement for network-based engines in the standard scan loop. The
 * {@code classSource} parameter passed to {@link #suggestForClass} is ignored
 * because the AI has already processed the source during the prepare phase.
 * </p>
 *
 * <h2>Response file format</h2>
 *
 * <p>
 * The response file may contain free-form text (for example the operator may
 * have copied the AI response verbatim from the chat window). The engine
 * extracts the first JSON object found in the file using
 * {@link JsonText#extractFirstJsonObject} and deserializes it into an
 * {@link AiClassSuggestion}. Any surrounding prose or formatting is silently
 * discarded.
 * </p>
 *
 * @see ManualPrepareEngine
 * @see AiSuggestionEngine
 * @see JsonText
 */
public final class ManualConsumeEngine implements AiSuggestionEngine {

    private final Path responseDir;
    private final ObjectMapper mapper;

    /**
     * Creates a new consume engine that reads response files from the given
     * directory.
     *
     * @param responseDir path to the directory containing operator-saved response
     *                    files
     */
    public ManualConsumeEngine(Path responseDir) {
        this.responseDir = responseDir;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Returns AI classification results for the specified class by reading the
     * corresponding response file.
     *
     * <p>
     * The response file is looked up as {@code <fileStem>.response.txt} in the
     * configured response directory, where {@code fileStem} is the dot-separated
     * path identifier computed from the source file's location relative to the scan
     * root (e.g. {@code module-a.src.test.java.com.acme.FooTest}). If the file does
     * not exist an empty {@link AiClassSuggestion} is returned so the caller emits
     * blank AI columns rather than failing.
     * </p>
     *
     * @param fileStem      dot-separated path stem used to locate the response file
     *                      ({@code <fileStem>.response.txt})
     * @param fqcn          fully qualified class name; included in the returned
     *                      suggestion for identification
     * @param classSource   ignored — the AI already saw the source during the
     *                      prepare phase
     * @param targetMethods ignored — method classification is read from the
     *                      response file
     * @return parsed and normalized suggestion, or an empty suggestion when no
     *         response file exists
     * @throws AiSuggestionException if the response file exists but cannot be read
     *                               or does not contain a valid JSON object
     */
    @Override
    public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        Path responseFile = responseDir.resolve(fileStem + ".response.txt");

        if (!Files.exists(responseFile)) {
            return new AiClassSuggestion(fqcn, null, List.of(), null, List.of()); // fqcn used for className
        }

        try {
            String responseText = Files.readString(responseFile, StandardCharsets.UTF_8);
            String json = JsonText.extractFirstJsonObject(responseText);
            AiClassSuggestion raw = mapper.readValue(json, AiClassSuggestion.class);
            return normalize(raw);
        } catch (IOException e) {
            throw new AiSuggestionException("Failed to read response file: " + responseFile, e);
        }
    }

    /**
     * Normalizes a raw provider response into the application's internal result
     * invariants.
     *
     * <p>
     * Ensures that collection-valued fields are never {@code null} and removes
     * malformed method entries that do not contain a valid method name.
     * </p>
     *
     * @param input raw suggestion deserialized from the operator-saved response file
     * @return normalized suggestion instance
     */
    private static AiClassSuggestion normalize(AiClassSuggestion input) {
        List<AiMethodSuggestion> methods = input.methods() == null ? List.of() : input.methods();
        List<String> classTags = input.classTags() == null ? List.of() : input.classTags();

        List<AiMethodSuggestion> normalizedMethods = methods.stream()
                .filter(m -> m != null && m.methodName() != null && !m.methodName().isBlank())
                .map(m -> new AiMethodSuggestion(m.methodName(), m.securityRelevant(),
                        m.displayName(), m.tags() == null ? List.of() : m.tags(), m.reason(), m.confidence()))
                .toList();

        return new AiClassSuggestion(input.className(), input.classSecurityRelevant(), classTags,
                input.classReason(), normalizedMethods);
    }
}
