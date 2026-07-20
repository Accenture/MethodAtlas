package org.egothor.methodatlas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PathStems}.
 */
class PathStemsTest {

    private static final Path ROOT = Path.of("proj", "tests");

    @Test
    void buildFileStem_joinsSegmentsWithDots() {
        Path file = ROOT.resolve(Path.of("auth", "authService.ts"));
        assertEquals("auth.authService", PathStems.buildFileStem(ROOT, file, ".ts"));
    }

    @Test
    void buildFileStem_stripsLongestMatchingSuffix() {
        Path file = ROOT.resolve(Path.of("auth", "authService.test.ts"));
        assertEquals("auth.authService",
                PathStems.buildFileStem(ROOT, file, ".ts", ".test.ts"));
    }

    @Test
    void buildFileStem_stripsUnderscoreSuffix() {
        Path file = ROOT.resolve(Path.of("pkg", "security_test.py"));
        assertEquals("pkg.security",
                PathStems.buildFileStem(ROOT, file, "_test.py"));
    }

    @Test
    void buildFileStem_noMatchingSuffix_fallsBackToLastExtension() {
        Path file = ROOT.resolve(Path.of("pkg", "Widget.cls"));
        assertEquals("pkg.Widget",
                PathStems.buildFileStem(ROOT, file, ".ts", ".py"));
    }

    @Test
    void buildFileStem_noSuffixesSupplied_stripsLastExtension() {
        Path file = ROOT.resolve(Path.of("pkg", "Widget.go"));
        assertEquals("pkg.Widget", PathStems.buildFileStem(ROOT, file));
    }

    @Test
    void buildFileStem_noExtension_returnedUnchanged() {
        Path file = ROOT.resolve(Path.of("pkg", "Makefile"));
        assertEquals("pkg.Makefile", PathStems.buildFileStem(ROOT, file));
    }
}
