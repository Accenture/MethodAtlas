package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.CredentialDetector;
import org.egothor.methodatlas.api.CredentialDetectorConfig;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.SarifEmitter;
import org.egothor.methodatlas.emit.CredentialCsvEmitter;
import org.egothor.methodatlas.emit.CredentialFinding;
import org.egothor.methodatlas.emit.CredentialMasker;

/**
 * Shared orchestration for the {@code -detect-secrets} feature.
 *
 * <p>
 * Two triage strategies are supported, both producing the same outputs (log,
 * secrets CSV, and — in SARIF mode — secret results embedded in the document):
 * </p>
 * <ul>
 *   <li><b>Folded</b> (default scope with AI enabled): the command runs detection
 *       up-front via {@link #detect(List)}, hands the resulting
 *       {@link CredentialTriageContext} to the scan so each per-class classification
 *       call <em>also</em> triages that class's candidates — the class source is
 *       sent to the provider once. The command then calls
 *       {@link #applyFoldedVerdicts(List, Map)} and {@link #emitFindings}.</li>
 *   <li><b>Separate</b> ({@link #run(List, SarifEmitter)}): used with no AI, or
 *       with a {@code -secrets-include} glob (which scans files outside the
 *       discovered classes). Detection is followed by an optional dedicated triage
 *       call per file.</li>
 * </ul>
 *
 * <p>
 * The deterministic detection itself never calls AI; a failed triage degrades to
 * unverified candidates.
 * </p>
 *
 * @since 4.1.0
 */
final class CredentialDetectionRunner {

    private static final Logger LOG = Logger.getLogger(CredentialDetectionRunner.class.getName());

    /** Default Shannon-entropy floor handed to the detectors. */
    private static final double DEFAULT_ENTROPY = 4.0;

    /** Extension appended when deriving a SARIF artifact URI from an FQCN. */
    private static final String JAVA_EXTENSION = ".java";

    /** Grouping key used for findings that carry no fully qualified class name. */
    private static final String NO_FQCN = "";

    private final CliConfig cfg;
    private final TestDiscoveryConfig discoveryConfig;
    private final PluginLoader pluginLoader;
    private final ScanOrchestrator orchestrator;
    private final AiSuggestionEngine aiEngine;

    /** Whether triage is folded into the scan's classification call (set by {@link #prepare}). */
    private boolean folded;
    /** Up-front detection result captured by {@link #prepare} for the folded path. */
    private DetectionResult preparedDetection;
    /** Triage context handed to the scan in the folded path. */
    private CredentialTriageContext preparedContext;

    /**
     * Creates a runner.
     *
     * @param cfg             parsed CLI configuration; never {@code null}
     * @param discoveryConfig discovery configuration used to enumerate test classes
     *                        and build the attribution index; never {@code null}
     * @param pluginLoader    loader used to resolve discovery providers and secret
     *                        detectors; never {@code null}
     * @param orchestrator    orchestrator used to group discovered methods by file;
     *                        never {@code null}
     * @param aiEngine        AI engine used for triage, or {@code null} when AI is
     *                        disabled (deterministic candidates are still emitted)
     */
    /* default */ CredentialDetectionRunner(CliConfig cfg, TestDiscoveryConfig discoveryConfig,
            PluginLoader pluginLoader, ScanOrchestrator orchestrator, AiSuggestionEngine aiEngine) {
        this.cfg = cfg;
        this.discoveryConfig = discoveryConfig;
        this.pluginLoader = pluginLoader;
        this.orchestrator = orchestrator;
        this.aiEngine = aiEngine;
    }

    /**
     * Deterministic detection result, plus the per-class candidate spans needed to
     * fold triage into the scan and the per-file source used for separate triage.
     *
     * @param findings         all deterministic findings (triage fields {@code null})
     * @param candidatesByFqcn candidate spans per class, in finding order
     * @param sourceByFile     file source text, for separate-call triage
     * @since 4.1.0
     */
    /* default */ record DetectionResult(List<CredentialFinding> findings,
            Map<String, List<PromptBuilder.CredentialCandidateRef>> candidatesByFqcn,
            Map<Path, String> sourceByFile) {
    }

    // ---------------------------------------------------------------------
    // Command-facing lifecycle (folded or separate, decided here)
    // ---------------------------------------------------------------------

    /**
     * Prepares credential detection before the scan. When triage can be folded
     * into the per-class classification call (AI enabled, default test-class scope,
     * and {@code -secrets-separate-llm} not set), this runs deterministic detection
     * up-front and returns the {@link CredentialTriageContext} the scan must thread
     * through so the class source is sent to the provider once. Otherwise returns
     * {@code null} and {@link #finish} performs detection (and any separate triage)
     * after the scan.
     *
     * @param roots scan roots; never {@code null}
     * @return the triage context to pass to the scan, or {@code null} when not folding
     * @throws IOException if up-front detection fails
     */
    /* default */ CredentialTriageContext prepare(List<Path> roots) throws IOException {
        this.folded = aiEngine != null && cfg.secretsInclude() == null && !cfg.secretsSeparateLlm();
        if (folded) {
            this.preparedDetection = detect(roots);
            this.preparedContext = toContext(preparedDetection);
            return preparedContext;
        }
        return null;
    }

    /**
     * Completes credential detection after the scan: in the folded path it merges
     * the verdicts the scan collected and emits; otherwise it runs detection plus
     * optional separate-call triage and emits.
     *
     * @param roots        scan roots; never {@code null}
     * @param sarifEmitter SARIF emitter to record findings into, or {@code null}
     * @throws IOException if collecting files or writing the CSV fails
     */
    /* default */ void finish(List<Path> roots, SarifEmitter sarifEmitter) throws IOException {
        if (folded) {
            emitFindings(applyFoldedVerdicts(preparedDetection.findings(),
                    preparedContext.verdictsByFqcn()), sarifEmitter);
        } else {
            run(roots, sarifEmitter);
        }
    }

    // ---------------------------------------------------------------------
    // Separate-call path
    // ---------------------------------------------------------------------

    /**
     * Runs detection, optional separate-call triage, and emission. Used when AI is
     * disabled or a {@code -secrets-include} glob is active.
     *
     * @param roots        scan roots; never {@code null}
     * @param sarifEmitter SARIF emitter to record findings into, or {@code null}
     * @throws IOException if collecting files or writing the CSV fails
     */
    /* default */ void run(List<Path> roots, SarifEmitter sarifEmitter) throws IOException {
        DetectionResult dr = detect(roots);
        List<CredentialFinding> triaged = aiEngine == null
                ? dr.findings()
                : triageSeparately(dr.findings(), dr.sourceByFile());
        emitFindings(triaged, sarifEmitter);
    }

    // ---------------------------------------------------------------------
    // Folded path (used by the command together with the scan)
    // ---------------------------------------------------------------------

    /**
     * Runs deterministic detection only, returning the findings plus the data the
     * folded path needs to triage during the scan.
     *
     * @param roots scan roots; never {@code null}
     * @return the detection result; never {@code null}
     * @throws IOException if collecting files fails
     */
    /* default */ DetectionResult detect(List<Path> roots) throws IOException {
        Map<Path, List<DiscoveredMethod>> byFile = discoverByFile(roots);
        Map<Path, List<DetectCredentialsStage.MethodRange>> attribution = toAttribution(byFile);
        List<CredentialScanUnit> units = selectUnits(roots, byFile);
        Map<Path, String> sourceByFile = units.stream()
                .collect(Collectors.toMap(CredentialScanUnit::filePath, CredentialScanUnit::source, (a, b) -> a));
        List<CredentialFinding> findings = runDetectors(units, attribution);
        return new DetectionResult(findings, candidatesByFqcn(findings), sourceByFile);
    }

    /**
     * Wraps a detection result in a triage context for the scan to fill.
     *
     * @param detection the detection result; never {@code null}
     * @return a context carrying the per-class candidates
     */
    /* default */ CredentialTriageContext toContext(DetectionResult detection) {
        return new CredentialTriageContext(detection.candidatesByFqcn());
    }

    /**
     * Merges the verdicts the scan collected (keyed by class) back into the
     * findings, by class and candidate index.
     *
     * @param findings         the deterministic findings; never {@code null}
     * @param verdictsByFqcn   verdicts collected during the folded scan; never {@code null}
     * @return findings with triage fields populated where a verdict exists
     */
    /* default */ List<CredentialFinding> applyFoldedVerdicts(List<CredentialFinding> findings,
            Map<String, List<CredentialTriageVerdict>> verdictsByFqcn) {
        List<CredentialFinding> out = new ArrayList<>(findings.size());
        groupByFqcn(findings).forEach((fqcn, group) -> {
            Map<Integer, CredentialTriageVerdict> byIndex = verdictsByFqcn.getOrDefault(fqcn, List.of()).stream()
                    .collect(Collectors.toMap(CredentialTriageVerdict::candidateIndex, v -> v, (a, b) -> a));
            out.addAll(DetectCredentialsStage.mergeVerdicts(group, byIndex));
        });
        return out;
    }

    /**
     * Applies the min-score filter and emits all outputs.
     *
     * @param findings     findings to emit; never {@code null}
     * @param sarifEmitter SARIF emitter to record findings into, or {@code null}
     * @throws IOException if writing the CSV fails
     */
    /* default */ void emitFindings(List<CredentialFinding> findings, SarifEmitter sarifEmitter) throws IOException {
        List<CredentialFinding> kept = filterByMinScore(findings);
        logSummary(kept);
        logFindings(kept);
        recordIntoSarif(kept, sarifEmitter);
        writeCsv(kept);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Map<Path, List<DiscoveredMethod>> discoverByFile(List<Path> roots) throws IOException {
        List<TestDiscovery> providers = pluginLoader.loadProviders(discoveryConfig);
        try {
            return orchestrator.collectMethodsByFile(roots, providers);
        } finally {
            pluginLoader.closeAll(providers);
        }
    }

    private static Map<Path, List<DetectCredentialsStage.MethodRange>> toAttribution(
            Map<Path, List<DiscoveredMethod>> byFile) {
        Map<Path, List<DetectCredentialsStage.MethodRange>> attribution = new LinkedHashMap<>();
        byFile.forEach((file, methods) -> attribution.put(file.toAbsolutePath(), methods.stream()
                .map(m -> new DetectCredentialsStage.MethodRange(m.method(), m.beginLine(), m.endLine()))
                .toList()));
        return attribution;
    }

    private List<CredentialScanUnit> selectUnits(List<Path> roots,
            Map<Path, List<DiscoveredMethod>> byFile) {
        if (cfg.secretsInclude() != null) {
            return new CredentialScanUnitSource(cfg.fileSuffixes(), cfg.secretsInclude()).collect(roots);
        }
        return byFile.entrySet().stream()
                .map(entry -> toUnit(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static CredentialScanUnit toUnit(Path file, List<DiscoveredMethod> methods) {
        if (methods.isEmpty()) {
            return null;
        }
        Path abs = file.toAbsolutePath();
        try {
            String text = Files.readString(abs, StandardCharsets.UTF_8);
            return new CredentialScanUnit(abs, methods.get(0).fqcn(), text,
                    CredentialScanUnitSource.languageOf(abs));
        } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "Skipping unreadable discovered file: " + abs);
            return null;
        }
    }

    private List<CredentialFinding> runDetectors(List<CredentialScanUnit> units,
            Map<Path, List<DetectCredentialsStage.MethodRange>> attribution) {
        CredentialDetectorConfig sdc = new CredentialDetectorConfig(
                DEFAULT_ENTROPY, Optional.ofNullable(cfg.secretsRules()), Map.of());
        List<CredentialDetector> detectors = pluginLoader.loadCredentialDetectors(sdc);
        try {
            return new DetectCredentialsStage(detectors, attribution).run(units);
        } finally {
            pluginLoader.closeAllCredentialDetectors(detectors);
        }
    }

    private static Map<String, List<CredentialFinding>> groupByFqcn(List<CredentialFinding> findings) {
        return findings.stream().collect(Collectors.groupingBy(
                f -> f.fqcn() == null ? NO_FQCN : f.fqcn(), LinkedHashMap::new, Collectors.toList()));
    }

    private static Map<String, List<PromptBuilder.CredentialCandidateRef>> candidatesByFqcn(
            List<CredentialFinding> findings) {
        Map<String, List<PromptBuilder.CredentialCandidateRef>> byFqcn = new LinkedHashMap<>();
        groupByFqcn(findings).forEach((fqcn, group) -> {
            if (!NO_FQCN.equals(fqcn)) {
                byFqcn.put(fqcn, IntStream.range(0, group.size())
                        .mapToObj(i -> new PromptBuilder.CredentialCandidateRef(i,
                                group.get(i).candidate().beginLine(), group.get(i).candidate().matchedValue()))
                        .toList());
            }
        });
        return byFqcn;
    }

    private List<CredentialFinding> triageSeparately(List<CredentialFinding> findings, Map<Path, String> sourceByFile) {
        if (findings.isEmpty()) {
            return findings;
        }
        Map<Path, List<CredentialFinding>> byFile = findings.stream()
                .collect(Collectors.groupingBy(CredentialFinding::filePath, LinkedHashMap::new, Collectors.toList()));
        List<CredentialFinding> result = new ArrayList<>(findings.size());
        byFile.forEach((file, group) ->
                result.addAll(triageGroup(file, group, sourceByFile.getOrDefault(file, ""))));
        return result;
    }

    private List<CredentialFinding> triageGroup(Path file, List<CredentialFinding> group, String source) {
        String fqcn = group.get(0).fqcn() != null
                ? group.get(0).fqcn()
                : file.toString().replace('\\', '/');
        List<PromptBuilder.CredentialCandidateRef> refs = IntStream.range(0, group.size())
                .mapToObj(i -> new PromptBuilder.CredentialCandidateRef(i,
                        group.get(i).candidate().beginLine(), group.get(i).candidate().matchedValue()))
                .toList();
        try {
            List<CredentialTriageVerdict> verdicts = aiEngine.triageSecrets(fqcn, source, refs);
            Map<Integer, CredentialTriageVerdict> byIndex = verdicts.stream()
                    .collect(Collectors.toMap(CredentialTriageVerdict::candidateIndex, v -> v, (a, b) -> a));
            return DetectCredentialsStage.mergeVerdicts(group, byIndex);
        } catch (AiSuggestionException e) {
            LOG.log(Level.WARNING, e,
                    () -> "Secret triage failed for " + fqcn + "; emitting unverified candidates");
            return group;
        }
    }

    private List<CredentialFinding> filterByMinScore(List<CredentialFinding> findings) {
        double minScore = cfg.secretsMinScore();
        List<CredentialFinding> kept = new ArrayList<>(findings.size());
        for (CredentialFinding f : findings) {
            if (f.credibilityScore() == null || f.credibilityScore() >= minScore) {
                kept.add(f);
            }
        }
        return kept;
    }

    private static void logSummary(List<CredentialFinding> kept) {
        if (LOG.isLoggable(Level.INFO)) {
            long files = kept.stream().map(CredentialFinding::filePath).distinct().count();
            LOG.log(Level.INFO, "Credential detection: {0} finding(s) across {1} file(s)",
                    new Object[] { kept.size(), files });
        }
    }

    private void logFindings(List<CredentialFinding> kept) {
        for (CredentialFinding f : kept) {
            String raw = f.candidate().matchedValue();
            String snippet = cfg.secretsShowValues() ? raw : CredentialMasker.mask(raw);
            // Supplier form is lazy: the message is only assembled when INFO is loggable.
            LOG.info(() -> "  " + f.filePath().toString().replace('\\', '/')
                    + ":" + f.candidate().beginLine()
                    + " [" + f.candidate().ruleId() + "] " + snippet);
        }
    }

    private void recordIntoSarif(List<CredentialFinding> kept, SarifEmitter sarifEmitter) {
        if (sarifEmitter == null) {
            return;
        }
        for (CredentialFinding f : kept) {
            sarifEmitter.recordSecret(fileUri(f), f);
        }
    }

    private static String fileUri(CredentialFinding f) {
        if (f.fqcn() != null) {
            return f.fqcn().replace('.', '/') + JAVA_EXTENSION;
        }
        return f.filePath().toString().replace('\\', '/');
    }

    private void writeCsv(List<CredentialFinding> kept) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(cfg.secretsOut()))) {
            new CredentialCsvEmitter(cfg.secretsShowValues()).flush(w, kept);
        }
    }
}
