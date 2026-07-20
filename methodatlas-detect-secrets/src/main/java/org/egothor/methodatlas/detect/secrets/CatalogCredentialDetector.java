package org.egothor.methodatlas.detect.secrets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialDetector;
import org.egothor.methodatlas.api.CredentialDetectorConfig;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.egothor.methodatlas.detect.secrets.internal.AhoCorasick;
import org.egothor.methodatlas.detect.secrets.internal.Entropy;
import org.egothor.methodatlas.detect.secrets.internal.RuleCatalog;
import org.egothor.methodatlas.detect.secrets.internal.RuleCatalogLoader;
import org.egothor.methodatlas.detect.secrets.internal.CredentialRule;

/**
 * Built-in {@link CredentialDetector} backed by the curated YAML catalog and a
 * clean-room Aho-Corasick prefilter. Anchored rules run via the automaton;
 * unanchored rules run a regex-plus-entropy pass over string literals.
 *
 * @since 4.1.0
 */
public final class CatalogCredentialDetector implements CredentialDetector {

    private static final String DETECTOR_ID = "builtin-catalog";
    /** Characters either side of an anchor hit to feed the confirm regex. */
    private static final int CONFIRM_WINDOW = 200;
    /** Default entropy floor when neither config nor rule specifies one. */
    private static final double DEFAULT_ENTROPY = 4.0;
    /** Newline character used when building the line-start index. */
    private static final char NEWLINE = '\n';

    private RuleCatalog catalog;
    private AhoCorasick anchorAutomaton;
    private List<CredentialRule> anchoredRules;
    private List<CredentialRule> unanchoredRules;
    /** Regex compiled once per rule at catalog-load time, keyed by rule. */
    private Map<CredentialRule, Pattern> patternsByRule;
    private double defaultEntropy = DEFAULT_ENTROPY;
    private boolean hadErrors;

    /**
     * Creates a detector with the bundled catalog; {@link #configure} may replace it.
     */
    public CatalogCredentialDetector() {
        loadCatalog(RuleCatalogLoader.loadBundled());
    }

    @Override
    public String detectorId() {
        return DETECTOR_ID;
    }

    @Override
    public void configure(CredentialDetectorConfig config) {
        this.defaultEntropy = config.entropyThreshold();
        config.customCatalog().ifPresent(path -> loadCatalog(RuleCatalogLoader.loadFile(path)));
    }

    /* default */ void loadCatalog(RuleCatalog loaded) {
        this.catalog = loaded;
        this.anchoredRules = new ArrayList<>();
        this.unanchoredRules = new ArrayList<>();
        this.patternsByRule = new HashMap<>();
        // Build the automaton from lowercased anchors so that the prefilter pass
        // can be run on a lowercased copy of the source, enabling case-insensitive
        // anchor matching without modifying the Aho-Corasick implementation.
        List<String> allAnchorsLower = new ArrayList<>();
        for (CredentialRule rule : loaded.rules()) {
            // Compile each rule's regex once here rather than on every detect() call.
            patternsByRule.put(rule, Pattern.compile(rule.pattern()));
            if (rule.unanchored()) {
                unanchoredRules.add(rule);
            } else {
                anchoredRules.add(rule);
                for (String anchor : rule.anchors()) {
                    allAnchorsLower.add(anchor.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.anchorAutomaton = AhoCorasick.build(allAnchorsLower);
    }

    /**
     * Returns the loaded catalog (exposed for reproducibility-receipt hashing).
     *
     * @return the active catalog; never {@code null} after construction
     */
    public RuleCatalog catalog() {
        return catalog;
    }

    @Override
    public List<CredentialCandidate> detect(CredentialScanUnit unit) {
        String source = unit.source();
        List<CredentialCandidate> out = new ArrayList<>();
        LineIndex lines = new LineIndex(source);

        // The prefilter automaton is built from lowercased anchors and run on a
        // lowercased copy of the source, enabling case-insensitive anchor matching
        // without modifying the Aho-Corasick implementation.
        String sourceLower = source.toLowerCase(Locale.ROOT);
        for (AhoCorasick.Hit hit : anchorAutomaton.search(sourceLower)) {
            for (CredentialRule rule : anchoredRules) {
                boolean anchorMatches = rule.anchors().stream()
                        .anyMatch(a -> a.toLowerCase(Locale.ROOT).equals(hit.keyword()));
                if (anchorMatches) {
                    confirmAnchored(source, hit.start(), rule, lines, out);
                }
            }
        }

        for (CredentialRule rule : unanchoredRules) {
            double floor = rule.entropyMin() > 0 ? rule.entropyMin() : defaultEntropy;
            Matcher m = patternsByRule.get(rule).matcher(source);
            while (m.find()) {
                String value = m.groupCount() >= 1 ? m.group(1) : m.group();
                if (Entropy.shannonBitsPerChar(value) >= floor) {
                    addCandidate(rule, m.start(), m.end(), value, lines, out);
                }
            }
        }
        // De-duplicate identical candidates that arise when several of a rule's
        // anchors fall within the confirm window of the same match.
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private void confirmAnchored(String source, int anchorStart, CredentialRule rule,
            LineIndex lines, List<CredentialCandidate> out) {
        int from = Math.max(0, anchorStart - CONFIRM_WINDOW);
        int to = Math.min(source.length(), anchorStart + CONFIRM_WINDOW);
        Matcher m = patternsByRule.get(rule).matcher(source.substring(from, to));
        while (m.find()) {
            int absStart = from + m.start();
            int absEnd = from + m.end();
            if (absStart <= anchorStart && anchorStart < absEnd) {
                // Convention: a rule has at most one capturing group, which is the
                // secret value; a rule with no group treats the whole match as the value.
                String value = m.groupCount() >= 1 ? m.group(1) : m.group();
                addCandidate(rule, absStart, absEnd, value, lines, out);
            }
        }
    }

    private void addCandidate(CredentialRule rule, int start, int end,
            String value, LineIndex lines, List<CredentialCandidate> out) {
        int beginLine = lines.lineOf(start);
        int beginCol = lines.columnOf(start);
        int endLine = lines.lineOf(end > 0 ? end - 1 : 0);
        int endCol = lines.columnOf(end);
        out.add(new CredentialCandidate(DETECTOR_ID, rule.id(), rule.category(),
                beginLine, beginCol, endLine, endCol, value));
    }

    @Override
    public boolean hadErrors() {
        return hadErrors;
    }

    /** Maps absolute character offsets to one-based line/column. */
    private static final class LineIndex {
        private final int[] lineStarts;

        /* default */ LineIndex(String source) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < source.length(); i++) {
                if (source.charAt(i) == NEWLINE) {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        /* default */ int lineOf(int offset) {
            int lo = 0;
            int hi = lineStarts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStarts[mid] <= offset) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo + 1;
        }

        /* default */ int columnOf(int offset) {
            int line = lineOf(offset);
            return offset - lineStarts[line - 1] + 1;
        }
    }
}
