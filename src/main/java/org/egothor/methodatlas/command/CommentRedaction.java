// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;

/**
 * Applies the loaded {@link CommentRedactor}s to source text on its way to an
 * AI provider, failing closed.
 *
 * <p>
 * The first redactor whose {@link CommentRedactor#supports(Path)} accepts the
 * file performs the redaction. If none does, or the redactor reports it cannot
 * guarantee redaction, {@link RedactionAbortedException} is thrown so that no
 * un-redacted source can leave the process.
 * </p>
 *
 * <p>
 * Instances are immutable; thread safety is that of the wrapped redactors.
 * </p>
 *
 * @see CommentRedactor
 * @see PluginLoader#loadRedactors
 * @since 4.1.0
 */
public final class CommentRedaction {

    private static final char LF = '\n';
    private static final char CR = '\r';
    private static final char BLANK = ' ';

    private final List<CommentRedactor> redactors;

    /**
     * Creates a service over the given redactors.
     *
     * @param redactors redactors in priority order; must not be {@code null}
     */
    public CommentRedaction(List<CommentRedactor> redactors) {
        this.redactors = List.copyOf(redactors);
    }

    /**
     * Returns a service with no redactors, which rejects every file.
     *
     * @return a new empty instance; never {@code null}
     */
    public static CommentRedaction none() {
        return new CommentRedaction(List.of());
    }

    /**
     * Returns {@code source} with comments blanked by the redactor responsible
     * for {@code file}.
     *
     * @param file   the file the text came from; {@code null} is treated as an
     *               unknown language and rejected
     * @param source full source text; must not be {@code null}
     * @return redacted text, same length and line structure as {@code source}
     * @throws RedactionAbortedException if no redactor supports the file or the
     *                                   redactor fails
     */
    public String redactOrThrow(Path file, String source) {
        if (file == null) {
            throw new RedactionAbortedException("-redact-comments cannot determine the language of a source "
                    + "without a file path; refusing to send it to the AI provider.");
        }
        for (CommentRedactor redactor : redactors) {
            if (redactor.supports(file)) {
                return redactWith(redactor, file, source);
            }
        }
        throw new RedactionAbortedException("-redact-comments is set but no comment redactor supports "
                + file + " (language: " + languageOf(file) + "). -redact-comments requires a redactor "
                + "for every scanned language; the run is aborted so no un-redacted source reaches the "
                + "AI provider.");
    }

    /**
     * Runs one redactor defensively and verifies its output, so a faulty current or
     * future redactor can never let un-redacted text through.
     *
     * @throws RedactionAbortedException if the redactor throws anything other than an
     *         unrecoverable VM error, or returns text violating the redaction contract
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static String redactWith(CommentRedactor redactor, Path file, String source) {
        String redacted;
        try {
            redacted = redactor.redact(file, source);
        } catch (CommentRedactionException e) {
            throw new RedactionAbortedException("-redact-comments could not redact " + file
                    + " (" + languageOf(file) + "): " + e.getMessage(), e);
        } catch (RuntimeException | StackOverflowError e) {
            throw new RedactionAbortedException("-redact-comments redactor '" + redactor.pluginId()
                    + "' failed on " + file + " (" + languageOf(file) + "): " + e, e);
        }
        String violation = contractViolation(source, redacted);
        if (violation != null) {
            throw new RedactionAbortedException("-redact-comments redactor '" + redactor.pluginId()
                    + "' violated the redaction contract on " + file + " (" + languageOf(file) + "): "
                    + violation + "; refusing to send the text to the AI provider.");
        }
        return redacted;
    }

    /**
     * Checks the post-conditions of the redaction contract.
     *
     * @return a description of the first violation, or {@code null} when the output is valid:
     *         non-null, same length, line terminators untouched, and differing from the input
     *         only where a non-terminator character became a space
     */
    private static String contractViolation(String source, String redacted) {
        if (redacted == null) {
            return "returned null";
        }
        if (redacted.length() != source.length()) {
            return "changed the length from " + source.length() + " to " + redacted.length();
        }
        for (int i = 0; i < source.length(); i++) {
            char in = source.charAt(i);
            char out = redacted.charAt(i);
            if (in == out) {
                continue;
            }
            if (in == LF || in == CR) {
                return "altered a line terminator at offset " + i;
            }
            if (out != BLANK) {
                return "replaced a character with something other than a space at offset " + i;
            }
        }
        return null;
    }

    private static String languageOf(Path file) {
        Path name = file.getFileName();
        String text = name == null ? file.toString() : name.toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0 && dot < text.length() - 1 ? "*" + text.substring(dot) : text;
    }
}
