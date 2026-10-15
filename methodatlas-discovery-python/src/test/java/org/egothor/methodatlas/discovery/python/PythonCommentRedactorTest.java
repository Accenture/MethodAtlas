package org.egothor.methodatlas.discovery.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PythonCommentRedactor}. The redaction tests drive the real
 * Python worker and are skipped when Python 3.8+ is absent; the availability
 * tests use an injected environment and always run.
 */
@Tag("unit")
class PythonCommentRedactorTest {

    /** Held strongly so handlers attached in a test are not lost to logger garbage collection. */
    private static final java.util.logging.Logger REDACTOR_LOG =
            java.util.logging.Logger.getLogger(PythonCommentRedactor.class.getName());

    private static final Path PY = Path.of("test_auth.py");
    private static final PythonCommentRedactor redactor = new PythonCommentRedactor();
    /** Detected once; the redaction tests are skipped when Python is absent. */
    private static final boolean pythonUsable = redactor.supports(PY);

    private static void requirePython() {
        Assumptions.assumeTrue(pythonUsable, "Python 3.8+ is required");
    }

    private static final String PII_SOURCE = String.join("\n",
            "#!/usr/bin/env python3 # shebang secret",
            "# -*- coding: utf-8 -*-",
            "# Copyright header: Jane Doe, jane.doe@acme.com",
            "import pytest  # owner 4111-1111-1111-1111",
            "",
            "\"\"\"Module docstring # keep me\"\"\"",
            "",
            "class TestLogin:",
            "    def test_ok(self):  # inline note +44 7700 900123",
            "        \"\"\"Docstring: check login # kept\"\"\"",
            "        url = 'http://x # not a comment'",
            "        raw = \"\"\"multi # line",
            "        # still a string\"\"\"",
            "        f = f\"{url} # in f-string\"",
            "        assert url  # trailing",
            "# last line comment",
            "");

    @Test
    void supports_matchesPyOnly() {
        requirePython();
        assertTrue(redactor.supports(Path.of("x/test_auth.py")));
        assertTrue(redactor.supports(Path.of("auth_test.PY")));
        for (String name : List.of("A.java", "a.ts", "a.pyc", "a.pyi", "py", "a.txt")) {
            assertFalse(redactor.supports(Path.of(name)), name);
        }
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("").toAbsolutePath().getRoot()));
        assertEquals("python", redactor.pluginId());
    }

    @Test
    void redact_blanksHashComments() throws CommentRedactionException {
        requirePython();
        String out = redactor.redact(PY, "x = 1  # c1\n# c2\ny = 2\n");

        assertEquals("x = 1" + " ".repeat(6) + "\n" + " ".repeat(4) + "\ny = 2\n", out);
    }

    @Test
    void redact_keepsHashInsideStringsAndFStrings() throws CommentRedactionException {
        requirePython();
        String src = "a = '# no'\nb = \"# no\"\nc = f\"{a} # no\"\nd = '''x\n# no\n'''\ne = rb'# no'\n";

        assertEquals(src, redactor.redact(PY, src));
    }

    @Test
    void redact_keepsDocstringsEvenWhenTheyLookLikeCommentsOrPii() throws CommentRedactionException {
        requirePython();
        String src = "\"\"\"Module doc: Jane Doe\"\"\"\n\ndef f():\n    '''Doc # with hash, 4111-1111-1111-1111'''\n"
                + "    return 1  # gone\n";

        String out = redactor.redact(PY, src);

        assertTrue(out.contains("\"\"\"Module doc: Jane Doe\"\"\""));
        assertTrue(out.contains("'''Doc # with hash, 4111-1111-1111-1111'''"));
        assertFalse(out.contains("gone"));
        assertInvariants(src, out);
    }

    @Test
    void redact_blanksPiiBearingCommentsCompletely() throws CommentRedactionException {
        requirePython();
        String out = redactor.redact(PY, PII_SOURCE);

        for (String pii : List.of("shebang secret", "coding", "Jane Doe", "jane.doe@acme.com",
                "4111-1111-1111-1111", "7700 900123", "inline note", "trailing", "last line comment")) {
            assertFalse(out.contains(pii), "must be blanked: " + pii);
        }
        assertTrue(out.contains("Module docstring # keep me"));
        assertTrue(out.contains("Docstring: check login # kept"));
        assertTrue(out.contains("'http://x # not a comment'"));
        assertTrue(out.contains("# still a string"));
        assertTrue(out.contains("# in f-string"));
        assertInvariants(PII_SOURCE, out);
    }

    @Test
    void redact_preservesLengthLinesAndOffsets() throws CommentRedactionException {
        requirePython();
        String out = redactor.redact(PY, PII_SOURCE);

        assertInvariants(PII_SOURCE, out);
        assertEquals(PII_SOURCE.indexOf("class TestLogin"), out.indexOf("class TestLogin"));
        assertEquals(PII_SOURCE.indexOf("def test_ok"), out.indexOf("def test_ok"));
    }

    @Test
    void redact_keepsOffsetsAfterNonAsciiAndSupplementaryCharacters() throws CommentRedactionException {
        requirePython();
        String src = "s = '\u00e9\ud83d\ude00'  # c \ud83d\ude00 tail\r\nt = '\u4e2d'  # more\r\nfoo = 1\r\n";

        String out = redactor.redact(PY, src);

        assertInvariants(src, out);
        assertEquals(src.indexOf("foo"), out.indexOf("foo"));
        assertTrue(out.startsWith("s = '\u00e9\ud83d\ude00'  "));
        assertFalse(out.contains("tail"));
        assertFalse(out.contains("more"));
    }

    @Test
    void redact_isIdempotent() throws CommentRedactionException {
        requirePython();
        String once = redactor.redact(PY, PII_SOURCE);

        assertEquals(once, redactor.redact(PY, once));
    }

    @Test
    void redact_returnsInputWhenThereIsNoComment() throws CommentRedactionException {
        requirePython();
        assertEquals("x = 1\n", redactor.redact(PY, "x = 1\n"));
        assertEquals("", redactor.redact(PY, ""));
    }

    @Test
    void redact_handlesCommentAtEndOfFileWithoutNewline() throws CommentRedactionException {
        requirePython();
        assertEquals("x = 1  " + " ".repeat(5), redactor.redact(PY, "x = 1  # eof"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x = '''unterminated # secret\n",
            "x = (1,\n# secret\n",
            "if x:\n        a = 1\n    b = 2  # secret\n"})
    void redact_failsClosedOnSourceThatCannotBeTokenized(String src) {
        requirePython();
        assertThrows(CommentRedactionException.class, () -> redactor.redact(PY, src));
    }

    @Test
    void redact_survivesAFailureAndKeepsServing() throws CommentRedactionException {
        requirePython();
        assertThrows(CommentRedactionException.class, () -> redactor.redact(PY, "x = '''open\n"));

        assertEquals("a = 1  " + " ".repeat(7), redactor.redact(PY, "a = 1  # zzzzz"));
    }

    @Test
    void supports_isFalseWhenPythonIsUnavailable() {
        PythonEnvironment absent = mock(PythonEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        AtomicInteger detections = new AtomicInteger();
        PythonCommentRedactor noPython = new PythonCommentRedactor(() -> {
            detections.incrementAndGet();
            return absent;
        });

        assertFalse(noPython.supports(Path.of("a.py")));
        assertFalse(noPython.supports(Path.of("b.py")));
        assertEquals(1, detections.get(), "Python is detected once");
    }

    @Test
    void supports_warnsNamingTheMissingRuntime() {
        PythonEnvironment absent = mock(PythonEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        List<String> warnings = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        REDACTOR_LOG.addHandler(handler);
        try {
            assertFalse(new PythonCommentRedactor(() -> absent).supports(Path.of("a.py")));
        } finally {
            REDACTOR_LOG.removeHandler(handler);
        }

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Python"), warnings.get(0));
    }

    @Test
    void redact_failsClosedWhenPythonIsUnavailable() {
        PythonEnvironment absent = mock(PythonEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        PythonCommentRedactor noPython = new PythonCommentRedactor(() -> absent);

        assertThrows(CommentRedactionException.class, () -> noPython.redact(PY, "x = 1  # secret\n"));
    }

    @Test
    void supports_doesNotDetectPythonForOtherLanguages() {
        AtomicInteger detections = new AtomicInteger();
        PythonCommentRedactor lazy = new PythonCommentRedactor(() -> {
            detections.incrementAndGet();
            return mock(PythonEnvironment.class);
        });

        assertFalse(lazy.supports(Path.of("A.java")));
        assertFalse(lazy.supports(Path.of("a.ts")));
        assertEquals(0, detections.get());
    }

    @Test
    void redactor_isRegisteredAsService() {
        List<CommentRedactor> found = StreamSupport
                .stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .filter(r -> r instanceof PythonCommentRedactor).toList();

        assertEquals(1, found.size());
    }

    private static void assertInvariants(String src, String out) {
        assertEquals(src.length(), out.length());
        for (int i = 0; i < src.length(); i++) {
            char s = src.charAt(i);
            if (s == '\n' || s == '\r') {
                assertEquals(s, out.charAt(i), "line terminator moved at " + i);
            } else {
                assertTrue(out.charAt(i) == s || out.charAt(i) == ' ', "unexpected change at " + i);
            }
        }
    }
}
