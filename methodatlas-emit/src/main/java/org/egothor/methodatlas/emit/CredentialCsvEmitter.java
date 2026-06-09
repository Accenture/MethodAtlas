package org.egothor.methodatlas.emit;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Writes credential findings as a dedicated CSV document, separate from the
 * per-method scan CSV because the columns differ.
 *
 * <p>Schema version 1. Columns: {@code file, fqcn, method, begin_line,
 * begin_column, end_line, rule_id, category, detector_id, snippet_masked,
 * credibility_score, endpoint, rationale}. Renaming/reordering columns is a
 * breaking change — bump {@link #SCHEMA_VERSION} and update the docs.</p>
 *
 * @since 4.1.0
 */
public final class CredentialCsvEmitter {

    /** CSV schema version for the secrets report. */
    public static final int SCHEMA_VERSION = 1;

    private static final String HEADER = "file,fqcn,method,begin_line,begin_column,end_line,"
            + "rule_id,category,detector_id,snippet_masked,credibility_score,endpoint,rationale";

    /** Estimated row length for the StringBuilder capacity hint. */
    private static final int ROW_HINT = 192;

    private final boolean showValues;

    /**
     * Creates an emitter.
     *
     * @param showValues when {@code true}, emit raw values instead of masked snippets
     */
    public CredentialCsvEmitter(final boolean showValues) {
        this.showValues = showValues;
    }

    /**
     * Writes the header followed by one row per finding.
     *
     * @param out      destination; never {@code null}
     * @param findings findings to write; never {@code null}
     */
    public void flush(final PrintWriter out, final List<CredentialFinding> findings) {
        out.println(HEADER);
        for (CredentialFinding f : findings) {
            out.println(toRow(f));
        }
    }

    private String toRow(final CredentialFinding f) {
        final String snippet = showValues ? f.candidate().matchedValue()
                : CredentialMasker.mask(f.candidate().matchedValue());
        final String score = f.credibilityScore() == null ? ""
                : String.format(Locale.ROOT, "%.2f", f.credibilityScore());
        final String file = f.filePath().toString().replace('\\', '/');
        final StringBuilder sb = new StringBuilder(ROW_HINT);
        sb.append(esc(file)).append(',')
          .append(esc(nullToEmpty(f.fqcn()))).append(',')
          .append(esc(nullToEmpty(f.method()))).append(',')
          .append(f.candidate().beginLine()).append(',')
          .append(f.candidate().beginColumn()).append(',')
          .append(f.candidate().endLine()).append(',')
          .append(esc(f.candidate().ruleId())).append(',')
          .append(esc(f.candidate().category().name())).append(',')
          .append(esc(f.candidate().detectorId())).append(',')
          .append(esc(snippet)).append(',')
          .append(score).append(',')
          .append(esc(nullToEmpty(f.endpoint()))).append(',')
          .append(esc(nullToEmpty(f.rationale())));
        return sb.toString();
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }

    private static String esc(final String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
