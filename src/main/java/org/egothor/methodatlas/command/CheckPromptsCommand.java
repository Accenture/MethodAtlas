package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.PromptTemplateKind;
import org.egothor.methodatlas.ai.PromptTemplateSet;
import org.egothor.methodatlas.ai.PromptTemplateValidator;

/**
 * Utility CLI mode ({@code -check-prompts}) that validates prompt templates and
 * prints their SHA-256 checksums without running a scan.
 *
 * <p>
 * For each {@link PromptTemplateKind} it reports whether the effective template
 * (the operator's override, if supplied via the matching flag, otherwise the
 * built-in default) is structurally valid, lists any problems, and prints the
 * checksum that a reproducibility receipt would record. It exits with status
 * {@code 1} if any template is invalid, making it suitable as a CI pre-flight
 * gate; otherwise {@code 0}.
 * </p>
 *
 * <p>
 * Validation covers placeholder correctness and the survival of the JSON
 * structural anchor the response parser needs; it cannot verify that a model will
 * follow the instructions (see {@link PromptTemplateValidator}).
 * </p>
 *
 * @since 4.1.0
 */
public final class CheckPromptsCommand implements Command {

    /** Flag that selects this utility mode. */
    public static final String FLAG_CHECK_PROMPTS = "-check-prompts";

    /** Flag supplying a custom method-classification template file. */
    public static final String FLAG_CLASSIFICATION_PROMPT = "-classification-prompt";

    /** Flag supplying a custom folded credential-triage appendix template file. */
    public static final String FLAG_TRIAGE_PROMPT = "-triage-prompt";

    /** Flag supplying a custom standalone credential-triage template file. */
    public static final String FLAG_DEDICATED_TRIAGE_PROMPT = "-dedicated-triage-prompt";

    private final Map<PromptTemplateKind, Path> overrides;

    /**
     * Creates a command for the supplied template overrides.
     *
     * @param overrides map of kind to override file; kinds absent from the map use
     *                  the built-in default; must not be {@code null}
     */
    /* default */ CheckPromptsCommand(Map<PromptTemplateKind, Path> overrides) {
        // Build via putAll rather than the EnumMap copy constructor, which rejects an
        // empty source map (it cannot infer the enum type from no entries).
        Map<PromptTemplateKind, Path> copy = new EnumMap<>(PromptTemplateKind.class);
        copy.putAll(overrides);
        this.overrides = copy;
    }

    /**
     * Builds a command by scanning raw command-line arguments for the three
     * prompt-override flags.
     *
     * @param args raw command-line arguments; must not be {@code null}
     * @return a configured command; never {@code null}
     */
    public static CheckPromptsCommand fromArgs(String... args) {
        Map<PromptTemplateKind, Path> overrides = new EnumMap<>(PromptTemplateKind.class);
        putIfPresent(overrides, args, FLAG_CLASSIFICATION_PROMPT, PromptTemplateKind.CLASSIFICATION);
        putIfPresent(overrides, args, FLAG_TRIAGE_PROMPT, PromptTemplateKind.TRIAGE_APPENDIX);
        putIfPresent(overrides, args, FLAG_DEDICATED_TRIAGE_PROMPT, PromptTemplateKind.DEDICATED_TRIAGE);
        return new CheckPromptsCommand(overrides);
    }

    private static void putIfPresent(Map<PromptTemplateKind, Path> map, String[] args, String flag,
            PromptTemplateKind kind) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                map.put(kind, Path.of(args[i + 1]));
                return;
            }
        }
    }

    /**
     * Validates each template kind and prints a report.
     *
     * @param out writer receiving the report; never {@code null}
     * @return {@code 0} when every template is valid; {@code 1} otherwise
     * @throws IOException never thrown directly; per-file read failures are reported
     *                     inline as validation failures
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        boolean allValid = true;
        for (PromptTemplateKind kind : PromptTemplateKind.values()) {
            allValid &= reportKind(out, kind);
        }
        out.println();
        out.println(allValid
                ? "All prompt templates are valid."
                : "One or more prompt templates are INVALID.");
        return allValid ? 0 : 1;
    }

    private boolean reportKind(PrintWriter out, PromptTemplateKind kind) {
        Path file = overrides.get(kind);
        String source = file == null ? "built-in default" : file.toString();
        String body;
        try {
            body = file == null ? PromptTemplateSet.defaults().get(kind) : Files.readString(file);
        } catch (IOException | UncheckedIOException e) {
            out.println(kind + " [" + source + "]: FAIL");
            out.println("  - cannot read file: " + e.getMessage());
            return false;
        }

        List<String> problems = PromptTemplateValidator.validate(kind, body);
        boolean ok = problems.isEmpty();
        out.println(kind + " [" + source + "]: " + (ok ? "PASS" : "FAIL"));
        out.println("  sha256: " + PromptTemplateSet.defaults().with(kind, body).hash(kind));
        for (String problem : problems) {
            out.println("  - " + problem);
        }
        return ok;
    }
}
