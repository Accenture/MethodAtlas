package org.egothor.methodatlas.detect.secrets.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal clean-room Aho-Corasick multi-pattern string matcher.
 *
 * <p>Built once from a set of literal keywords, then reused to scan many inputs.
 * A single {@link #search(String)} pass reports every occurrence of every keyword
 * in {@code O(n + matches)} time. The implementation operates on UTF-16 {@code char}
 * units, which is sufficient for anchoring on ASCII credential markers.</p>
 *
 * @since 4.1.0
 */
public final class AhoCorasick {

    /** Initial transition-map capacity hint per node; most nodes have few children. */
    private static final int CHILD_HINT = 4;

    private final int[] fail;
    private final List<Map<Character, Integer>> transitions;
    private final List<List<String>> output;

    private AhoCorasick(
            int[] fail,
            List<Map<Character, Integer>> transitions,
            List<List<String>> output) {
        this.fail = fail;
        this.transitions = transitions;
        this.output = output;
    }

    /**
     * Builds an automaton for the given keywords.
     *
     * @param keywords non-null literal anchors; null or empty entries are ignored
     * @return a ready-to-search automaton
     * @since 4.1.0
     */
    public static AhoCorasick build(List<String> keywords) {
        List<Map<Character, Integer>> transitions = new ArrayList<>();
        List<List<String>> output = new ArrayList<>();
        transitions.add(new HashMap<>(CHILD_HINT));
        output.add(new ArrayList<>(1));

        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) {
                continue;
            }
            int node = 0;
            for (int i = 0; i < kw.length(); i++) {
                char c = kw.charAt(i);
                Integer next = transitions.get(node).get(c);
                if (next == null) {
                    next = transitions.size();
                    transitions.add(new HashMap<>(CHILD_HINT));
                    output.add(new ArrayList<>(1));
                    transitions.get(node).put(c, next);
                }
                node = next;
            }
            output.get(node).add(kw);
        }

        int[] fail = new int[transitions.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int child : transitions.get(0).values()) {
            fail[child] = 0;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Map.Entry<Character, Integer> e : transitions.get(node).entrySet()) {
                char c = e.getKey();
                int child = e.getValue();
                queue.add(child);
                int f = fail[node];
                while (f != 0 && !transitions.get(f).containsKey(c)) {
                    f = fail[f];
                }
                Integer target = transitions.get(f).get(c);
                fail[child] = (target != null && target != child) ? target : 0;
                output.get(child).addAll(output.get(fail[child]));
            }
        }
        return new AhoCorasick(fail, transitions, output);
    }

    /**
     * Scans {@code text} and returns every keyword occurrence.
     *
     * @param text input to scan; never {@code null}
     * @return hits in ascending start order; never {@code null}
     * @since 4.1.0
     */
    public List<Hit> search(String text) {
        List<Hit> hits = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (node != 0 && !transitions.get(node).containsKey(c)) {
                node = fail[node];
            }
            Integer next = transitions.get(node).get(c);
            node = next != null ? next : 0;
            for (String kw : output.get(node)) {
                hits.add(new Hit(kw, i - kw.length() + 1));
            }
        }
        return hits;
    }

    /**
     * One keyword occurrence found by {@link #search(String)}.
     *
     * @param keyword the matched keyword
     * @param start   zero-based index of the first matched character in the scanned text
     * @since 4.1.0
     */
    public record Hit(String keyword, int start) {
    }
}
