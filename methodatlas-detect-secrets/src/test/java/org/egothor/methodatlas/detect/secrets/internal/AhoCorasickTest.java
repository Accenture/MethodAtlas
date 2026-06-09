package org.egothor.methodatlas.detect.secrets.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AhoCorasickTest {

    @Test
    void findsAllAnchorsInOnePass() {
        AhoCorasick ac = AhoCorasick.build(List.of("AKIA", "password", "pwd"));
        List<AhoCorasick.Hit> hits = ac.search("x AKIA y password z");
        assertEquals(2, hits.size());
        assertEquals("AKIA", hits.get(0).keyword());
        assertEquals(2, hits.get(0).start());
        assertEquals("password", hits.get(1).keyword());
        assertEquals(9, hits.get(1).start());
    }

    @Test
    void handlesOverlappingKeywords() {
        AhoCorasick ac = AhoCorasick.build(List.of("ab", "abc", "bc"));
        List<AhoCorasick.Hit> hits = ac.search("xabcx");
        assertEquals(3, hits.size());
        assertTrue(hits.stream().anyMatch(h -> h.keyword().equals("abc") && h.start() == 1));
        assertTrue(hits.stream().anyMatch(h -> h.keyword().equals("bc") && h.start() == 2));
    }

    @Test
    void matchesAnchorAtEndOfInput() {
        AhoCorasick ac = AhoCorasick.build(List.of("END"));
        List<AhoCorasick.Hit> hits = ac.search("the END");
        assertEquals(1, hits.size());
        assertEquals(4, hits.get(0).start());
    }

    @Test
    void returnsEmptyWhenNoMatch() {
        AhoCorasick ac = AhoCorasick.build(List.of("AKIA"));
        assertTrue(ac.search("no secrets here").isEmpty());
    }

    @Test
    void handlesMultiByteText() {
        AhoCorasick ac = AhoCorasick.build(List.of("token"));
        List<AhoCorasick.Hit> hits = ac.search("☕ token");
        assertEquals(1, hits.size());
        assertEquals("token", hits.get(0).keyword());
    }
}
