package org.egothor.methodatlas.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialDetector;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.egothor.methodatlas.emit.CredentialFinding;

/**
 * Runs the deterministic detectors over the scan units, attaches best-effort
 * enclosing-method attribution, and produces {@link CredentialFinding}s. AI triage
 * (added later) enriches the findings afterwards; this stage leaves the triage
 * fields {@code null}.
 *
 * @since 4.1.0
 */
public final class DetectCredentialsStage {

    private final List<CredentialDetector> detectors;
    private final Map<Path, List<MethodRange>> attribution;

    /**
     * Creates a stage.
     *
     * @param detectors   loaded detectors; never {@code null}
     * @param attribution map of absolute file path to method ranges for
     *                    enclosing-method attribution; never {@code null}
     */
    public DetectCredentialsStage(List<CredentialDetector> detectors, Map<Path, List<MethodRange>> attribution) {
        this.detectors = List.copyOf(detectors);
        this.attribution = Map.copyOf(attribution);
    }

    /**
     * Detects and builds findings for the supplied units, in unit-then-source order.
     *
     * @param units units to scan; never {@code null}
     * @return findings; never {@code null}
     */
    @SuppressWarnings("PMD.CloseResource") // detectors are owned and closed by the caller, not by this stage
    public List<CredentialFinding> run(List<CredentialScanUnit> units) {
        List<CredentialFinding> findings = new ArrayList<>();
        for (CredentialScanUnit unit : units) {
            for (CredentialDetector detector : detectors) {
                for (CredentialCandidate c : detector.detect(unit)) {
                    findings.add(new CredentialFinding(c, unit.filePath(), unit.fqcn(),
                            methodFor(unit.filePath(), c.beginLine()), null, null, null));
                }
            }
        }
        return findings;
    }

    private String methodFor(Path file, int line) {
        List<MethodRange> ranges = attribution.get(file);
        if (ranges == null) {
            return null;
        }
        for (MethodRange r : ranges) {
            if (line >= r.beginLine() && line <= r.endLine()) {
                return r.method();
            }
        }
        return null;
    }

    /**
     * Merges LLM triage verdicts into a per-class finding list by candidate index.
     * Findings without a matching verdict are returned unchanged (their triage
     * fields stay {@code null}).
     *
     * @param findings deterministic findings for one class, in candidate-index order;
     *                 never {@code null}
     * @param verdicts verdicts keyed by candidate index; never {@code null}
     * @return findings with triage fields populated where a verdict exists; never {@code null}
     */
    public static List<CredentialFinding> mergeVerdicts(List<CredentialFinding> findings,
            Map<Integer, CredentialTriageVerdict> verdicts) {
        return IntStream.range(0, findings.size())
                .mapToObj(i -> applyVerdict(findings.get(i), verdicts.get(i)))
                .toList();
    }

    private static CredentialFinding applyVerdict(CredentialFinding finding, CredentialTriageVerdict verdict) {
        if (verdict == null) {
            return finding;
        }
        return new CredentialFinding(finding.candidate(), finding.filePath(), finding.fqcn(), finding.method(),
                verdict.credibilityScore(), verdict.endpoint(), verdict.rationale());
    }

    /**
     * A method's line span, used for attribution.
     *
     * @param method    simple method name
     * @param beginLine one-based first line
     * @param endLine   one-based last line
     * @since 4.1.0
     */
    public record MethodRange(String method, int beginLine, int endLine) {
    }
}
