package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles the prepare phase of the manual AI workflow.
 *
 * <p>
 * This engine supports operators who cannot use an automated AI API but can
 * interact with an AI through a standard chat window. For each test class it
 * writes a <em>work file</em> to the configured work directory. Each work file
 * contains:
 * </p>
 *
 * <ul>
 * <li>human-readable operator instructions</li>
 * <li>the complete AI prompt (taxonomy, method list, class source) that the
 * operator should paste into their AI chat window</li>
 * </ul>
 *
 * <p>
 * After the AI responds the operator pastes the response text into the
 * pre-created {@code <fqcn>.response.txt} stub and then runs the consume phase
 * (via {@link ManualConsumeEngine}) to produce the final enriched CSV.
 * </p>
 *
 * <h2>File naming</h2>
 *
 * <p>
 * Work files are named {@code <fqcn>.txt} and written to the work directory.
 * Empty response stubs are named {@code <fqcn>.response.txt} and written to
 * the response directory. Both directories are flat (no sub-directory
 * structure). The two directories may be the same path.
 * </p>
 *
 * @see ManualConsumeEngine
 * @see PromptBuilder
 */
public final class ManualPrepareEngine {

    private static final String SEPARATOR = "=".repeat(80);

    private final Path workDir;
    private final Path responseDir;
    private final String taxonomyText;

    /**
     * Creates a new prepare engine that writes work files and response stubs to
     * separate directories.
     *
     * <p>
     * Both directories are created if they do not already exist. The two paths may
     * point to the same directory.
     * </p>
     *
     * @param workDir     path to the directory where work files ({@code <fqcn>.txt})
     *                    will be written
     * @param responseDir path to the directory where empty response stubs
     *                    ({@code <fqcn>.response.txt}) will be pre-created
     * @param options     AI options used to load the taxonomy text; only taxonomy
     *                    settings are relevant here — provider settings are ignored
     * @throws AiSuggestionException if either directory cannot be created or the
     *                               configured taxonomy file cannot be read
     */
    public ManualPrepareEngine(Path workDir, Path responseDir, AiOptions options) throws AiSuggestionException {
        this.workDir = workDir;
        this.responseDir = responseDir;
        this.taxonomyText = loadTaxonomy(options);

        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new AiSuggestionException("Cannot create work directory: " + workDir, e);
        }
        if (!responseDir.equals(workDir)) {
            try {
                Files.createDirectories(responseDir);
            } catch (IOException e) {
                throw new AiSuggestionException("Cannot create response directory: " + responseDir, e);
            }
        }
    }

    /**
     * Builds and writes the work file for the specified test class, and
     * pre-creates an empty response file alongside it.
     *
     * <p>
     * The work file contains operator instructions at the top followed by the full
     * AI prompt. The prompt is built using {@link PromptBuilder#build} and embeds
     * the complete class source so the operator can paste the entire block into
     * their AI chat window without attaching any files separately.
     * </p>
     *
     * <p>
     * An empty {@code <fqcn>.response.txt} file is also written to the same work
     * directory so the operator only needs to paste the AI response into the
     * pre-existing file rather than creating it manually. If the response file
     * already contains content (e.g. from a previous run) it is left untouched.
     * </p>
     *
     * @param fqcn          fully qualified class name of the test class
     * @param classSource   complete source code of the test class
     * @param targetMethods deterministically discovered JUnit test methods to
     *                      classify
     * @return path of the written work file
     * @throws AiSuggestionException if the work file or the empty response file
     *                               cannot be written
     */
    public Path prepare(String fqcn, String classSource, List<PromptBuilder.TargetMethod> targetMethods)
            throws AiSuggestionException {
        String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods);
        Path outputFile = workDir.resolve(fqcn + ".txt");
        String content = buildFileContent(fqcn, prompt);

        try {
            Files.writeString(outputFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AiSuggestionException("Failed to write work file: " + outputFile, e);
        }

        Path responseFile = responseDir.resolve(fqcn + ".response.txt");
        if (!Files.exists(responseFile)) {
            try {
                Files.writeString(responseFile, "", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AiSuggestionException("Failed to create response file: " + responseFile, e);
            }
        }

        return outputFile;
    }

    private static String buildFileContent(String fqcn, String prompt) {
        String responseFileName = fqcn + ".response.txt";
        return SEPARATOR + "\n"
                + "OPERATOR INSTRUCTIONS\n"
                + SEPARATOR + "\n"
                + "Class      : " + fqcn + "\n"
                + "Work file  : " + fqcn + ".txt\n"
                + "Response   : " + responseFileName + "\n"
                + "\n"
                + "Steps:\n"
                + "  1. Copy the AI PROMPT block below (between the BEGIN/END markers)\n"
                + "     into your AI chat window.\n"
                + "  2. Wait for the AI to respond.\n"
                + "  3. Paste the complete AI response into the pre-created stub file:\n"
                + "       " + responseFileName + "\n"
                + "     (created empty in the response directory — do not rename it).\n"
                + "  4. Repeat for all other work files.\n"
                + "  5. After all responses are saved, run the consume phase:\n"
                + "       java -jar methodatlas.jar -manual-consume <workdir> <responsedir> <source-roots...>\n"
                + SEPARATOR + "\n"
                + "\n"
                + "--- BEGIN AI PROMPT ---\n"
                + prompt
                + "--- END AI PROMPT ---\n";
    }

    private static String loadTaxonomy(AiOptions options) throws AiSuggestionException {
        if (options.taxonomyFile() != null) {
            try {
                return Files.readString(options.taxonomyFile());
            } catch (IOException e) {
                throw new AiSuggestionException("Failed to read taxonomy file: " + options.taxonomyFile(), e);
            }
        }
        return switch (options.taxonomyMode()) {
            case DEFAULT -> DefaultSecurityTaxonomy.text();
            case OPTIMIZED -> OptimizedSecurityTaxonomy.text();
        };
    }
}
