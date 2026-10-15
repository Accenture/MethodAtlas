package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommentRedaction} and the redactor loading in
 * {@link PluginLoader}.
 */
class CommentRedactionTest {

    @Test
    void firstSupportingRedactorWins() {
        CommentRedaction redaction = new CommentRedaction(List.of(redactor("a", ".x", "s c"), redactor("b", ".x", "s  ")));

        assertEquals("s c", redaction.redactOrThrow(Path.of("f.x"), "s#c"));
    }

    @Test
    void unsupportedFile_namesFileAndLanguage() {
        CommentRedaction redaction = new CommentRedaction(List.of(redactor("a", ".x", "A")));

        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> redaction.redactOrThrow(Path.of("dir/Foo.cbl"), "src"));

        assertTrue(ex.getMessage().contains("Foo.cbl") && ex.getMessage().contains("*.cbl"), ex.getMessage());
    }

    @Test
    void noneRejectsEverything() {
        assertThrows(RedactionAbortedException.class,
                () -> CommentRedaction.none().redactOrThrow(Path.of("f.x"), "src"));
    }

    @Test
    void fileWithoutExtension_isReportedByName() {
        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> CommentRedaction.none().redactOrThrow(Path.of("Makefile"), "src"));

        assertTrue(ex.getMessage().contains("language: Makefile"), ex.getMessage());
    }

    @Test
    void redactorFailureIsWrappedWithCause() {
        CommentRedactor failing = new CommentRedactor() {
            @Override
            public String pluginId() {
                return "f";
            }

            @Override
            public boolean supports(Path sourceFile) {
                return true;
            }

            @Override
            public String redact(Path sourceFile, String source) throws CommentRedactionException {
                throw new CommentRedactionException("bad");
            }
        };

        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> new CommentRedaction(List.of(failing)).redactOrThrow(Path.of("f.x"), "src"));

        assertTrue(ex.getCause() instanceof CommentRedactionException);
    }

    @Test
    void runtimeExceptionFromRedactor_failsClosedAsRedactionAborted() {
        CommentRedactor faulty = stub("faulty", (file, source) -> {
            throw new IllegalStateException("boom");
        });

        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> new CommentRedaction(List.of(faulty)).redactOrThrow(Path.of("f.x"), "src"));

        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(ex.getMessage().contains("faulty") && ex.getMessage().contains("f.x"), ex.getMessage());
    }

    @Test
    void stackOverflowFromRedactor_failsClosedAsRedactionAborted() {
        CommentRedactor deep = stub("deep", (file, source) -> {
            throw new StackOverflowError();
        });

        assertThrows(RedactionAbortedException.class,
                () -> new CommentRedaction(List.of(deep)).redactOrThrow(Path.of("f.x"), "src"));
    }

    @Test
    void contractViolations_failClosed() {
        String src = "ab\ncd\r\n";
        Map<String, UnaryOperator<String>> bad = new LinkedHashMap<>();
        bad.put("null result", s -> null);
        bad.put("shorter", s -> s.substring(1));
        bad.put("longer", s -> s + " ");
        bad.put("newline blanked", s -> s.replace('\n', ' '));
        bad.put("newline moved", s -> "a\nb" + s.substring(3));
        bad.put("carriage return altered", s -> s.replace('\r', '\n'));
        bad.put("non-space replacement", s -> "xb" + s.substring(2));

        bad.forEach((name, behaviour) -> {
            CommentRedactor r = stub("bad", (file, source) -> behaviour.apply(source));
            assertThrows(RedactionAbortedException.class,
                    () -> new CommentRedaction(List.of(r)).redactOrThrow(Path.of("f.x"), src), name);
        });
    }

    @Test
    void validBlanking_isAccepted() {
        String src = "a#b\nc#d\r\n";
        CommentRedactor blanker = stub("ok", (file, source) -> source.replace('#', ' '));

        assertEquals("a b\nc d\r\n", new CommentRedaction(List.of(blanker)).redactOrThrow(Path.of("f.x"), src));
    }

    @Test
    void duplicateRedactorIds_areRejected() {
        List<CommentRedactor> dup = List.of(redactor("same", ".x", "A"), redactor("same", ".y", "B"));

        assertThrows(IllegalStateException.class, () -> PluginLoader.requireUniqueRedactorIds(dup));
    }

    @Test
    void loadRedactors_discoversRegisteredLanguageRedactors() {
        List<CommentRedactor> loaded = new PluginLoader()
                .loadRedactors(new TestDiscoveryConfig(List.of(), java.util.Set.of(), java.util.Map.of()));

        assertEquals(java.util.Set.of("java", "dotnet", "go", "powershell", "typescript", "python"),
                loaded.stream().map(CommentRedactor::pluginId).collect(java.util.stream.Collectors.toSet()),
                "every language with a parser ships a redactor; ABAP and COBOL do not");
        assertTrue(loaded.stream().noneMatch(r -> r.supports(Path.of("Z.abap")) || r.supports(Path.of("Z.cbl"))),
                "ABAP and COBOL have no redactor by design");
    }

    /** Functional form of {@link CommentRedactor#redact} that may throw unchecked exceptions or errors. */
    @FunctionalInterface
    private interface RedactBody {
        String apply(Path file, String source) throws CommentRedactionException;
    }

    private static CommentRedactor stub(String id, RedactBody body) {
        return new CommentRedactor() {
            @Override
            public String pluginId() {
                return id;
            }

            @Override
            public boolean supports(Path sourceFile) {
                return true;
            }

            @Override
            public String redact(Path sourceFile, String source) throws CommentRedactionException {
                return body.apply(sourceFile, source);
            }
        };
    }

    private static CommentRedactor redactor(String id, String suffix, String result) {
        return new CommentRedactor() {
            @Override
            public String pluginId() {
                return id;
            }

            @Override
            public boolean supports(Path sourceFile) {
                return sourceFile.toString().endsWith(suffix);
            }

            @Override
            public String redact(Path sourceFile, String source) {
                return result;
            }
        };
    }
}
