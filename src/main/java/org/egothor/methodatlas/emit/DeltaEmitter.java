package org.egothor.methodatlas.emit;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.DeltaEntry;
import org.egothor.methodatlas.DeltaReport;
import org.egothor.methodatlas.api.ScanRecord;

/**
 * Formats and writes a MethodAtlas delta report to a {@link PrintWriter}.
 *
 * <h2>Output format</h2>
 *
 * <p>
 * The emitted text is designed to be both human-readable in a terminal and
 * parseable by simple shell scripts (e.g. {@code grep "^+"} to list added
 * methods). The format is:
 * </p>
 *
 * <pre>
 * MethodAtlas delta report
 *   before: before.csv  (scanned: 2026-04-10T09:00:00Z · 45 methods · 5 security-relevant)
 *   after:  after.csv   (scanned: 2026-04-24T14:30:00Z · 47 methods · 7 security-relevant)
 *
 * + com.acme.auth.Oauth2FlowTest  test_authCode
 * + com.acme.auth.Oauth2FlowTest  test_tokenRefresh
 * - com.acme.auth.LegacyAuthTest  test_basicAuth
 * ~ com.acme.crypto.AesGcmTest    roundTrip_encryptDecrypt  [source; security: false → true]
 *
 * 2 added  ·  1 removed  ·  1 modified  ·  42 unchanged
 * security-relevant: 5 → 7  (+2)
 * </pre>
 *
 * @see DeltaReport
 * @see DeltaEntry
 */
public final class DeltaEmitter {

    private DeltaEmitter() {
    }

    /**
     * Emits a full delta report to {@code out}.
     *
     * @param result the delta result to format
     * @param out    writer that receives all output; flushed but not closed
     */
    public static void emit(DeltaReport.DeltaResult result, PrintWriter out) {
        emitHeader(result, out);
        out.println();
        emitEntries(result, out);
        out.println();
        emitSummary(result, out);
        out.flush();
    }

    private static void emitHeader(DeltaReport.DeltaResult result, PrintWriter out) {
        out.println("MethodAtlas delta report");
        out.println("  before: " + result.beforePath().getFileName()
                + fileSummary(result.beforeTimestamp(), result.totalBefore(),
                        result.securityRelevantBefore()));
        out.println("  after:  " + result.afterPath().getFileName()
                + fileSummary(result.afterTimestamp(), result.totalAfter(),
                        result.securityRelevantAfter()));
    }

    private static String fileSummary(String timestamp, int total, int security) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("  (");
        if (timestamp != null) {
            sb.append("scanned: ").append(timestamp).append(" · ");
        }
        sb.append(total).append(" method").append(total == 1 ? "" : "s")
          .append(" · ").append(security).append(" security-relevant)");
        return sb.toString();
    }

    private static void emitEntries(DeltaReport.DeltaResult result, PrintWriter out) {
        if (result.entries().isEmpty()) {
            out.println("No changes detected.");
            return;
        }
        for (DeltaEntry entry : result.entries()) {
            emitEntry(entry, out);
        }
    }

    private static void emitEntry(DeltaEntry entry, PrintWriter out) {
        String symbol = switch (entry.changeType()) {
            case ADDED -> "+";
            case REMOVED -> "-";
            case MODIFIED -> "~";
        };

        ScanRecord rec = entry.record();
        out.print(symbol + " " + rec.fqcn() + "  " + rec.method());

        if (entry.changeType() == DeltaEntry.ChangeType.MODIFIED
                && !entry.changedFields().isEmpty()) {
            out.print("  [" + formatChangedFields(entry) + "]");
        }

        out.println();
    }

    private static String formatChangedFields(DeltaEntry entry) {
        List<String> parts = new ArrayList<>();
        for (String field : entry.changedFields()) {
            switch (field) {
                case "source" -> parts.add("source");
                case "loc" -> parts.add("loc: " + entry.before().loc() + " → " + entry.after().loc());
                case "tags" -> parts.add("tags");
                case "display_name" -> parts.add("display_name");
                case "ai_security_relevant" ->
                    parts.add("security: " + entry.before().aiSecurityRelevant()
                            + " → " + entry.after().aiSecurityRelevant());
                case "ai_tags" -> parts.add("ai_tags");
                default -> parts.add(field);
            }
        }
        return String.join("; ", parts);
    }

    private static void emitSummary(DeltaReport.DeltaResult result, PrintWriter out) {
        out.println(result.addedCount() + " added  ·  "
                + result.removedCount() + " removed  ·  "
                + result.modifiedCount() + " modified  ·  "
                + result.unchangedCount() + " unchanged");

        int secBefore = result.securityRelevantBefore();
        int secAfter = result.securityRelevantAfter();
        int delta = secAfter - secBefore;
        String deltaStr = delta > 0 ? "(+" + delta + ")"
                : delta < 0 ? "(" + delta + ")"
                : "(no change)";
        out.println("security-relevant: " + secBefore + " → " + secAfter + "  " + deltaStr);
    }
}
