package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-platform determinism contract (roadmap RM-D, SABSA exception EX-005)
 * for the <em>structural</em> scan output.
 *
 * <p>
 * The structural layer (FQCN, method, LOC, source tags, content hash, source
 * root, SARIF locations) must be byte-identical for the same source revision
 * regardless of provider, model, time or machine. These tests scan the fixed
 * in-repo fixture {@code src/test/resources/determinism/fixture} with AI off
 * and assert:
 * </p>
 * <ul>
 * <li>two runs produce byte-identical output;</li>
 * <li>the output matches the committed golden files under
 * {@code src/test/resources/determinism/expected}, which are the same bytes on
 * every operating system;</li>
 * <li>the output contains no carriage return and no backslash path
 * separator;</li>
 * <li>a hostile default {@link Locale} and {@link TimeZone} do not change the
 * output;</li>
 * <li>the fixture's mixed-case directory names are emitted in a
 * platform-independent order.</li>
 * </ul>
 *
 * <p>
 * The scan root is passed as a working-directory-relative path, so the emitted
 * {@code source_root} and SARIF location prefix are the same on every machine.
 * Gradle runs unit tests with the project directory as working directory.
 * </p>
 *
 * <p>
 * Environment hooks: {@code METHODATLAS_DETERMINISM_OUT} names a directory into
 * which each leg writes its outputs (the CI matrix uploads and cross-compares
 * them); {@code METHODATLAS_UPDATE_GOLDEN=true} rewrites the golden files.
 * </p>
 */
class MethodAtlasAppDeterminismTest {

    private static final Path FIXTURE = Paths.get("src", "test", "resources", "determinism", "fixture");
    private static final Path EXPECTED = Paths.get("src", "test", "resources", "determinism", "expected");
    private static final String OUT_ENV = "METHODATLAS_DETERMINISM_OUT";
    private static final String UPDATE_ENV = "METHODATLAS_UPDATE_GOLDEN";
    private static final String FIXTURE_PREFIX = "src/test/resources/determinism/fixture";

    private Locale savedLocale;
    private TimeZone savedZone;

    @BeforeEach
    void saveDefaults() {
        savedLocale = Locale.getDefault();
        savedZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaults() {
        Locale.setDefault(savedLocale);
        TimeZone.setDefault(savedZone);
    }

    /** Output name to the CLI arguments that produce it. */
    private static Map<String, String[]> scenarios() {
        String root = FIXTURE.toString();
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("scan.csv", new String[] { "-content-hash", "-emit-source-root", root });
        m.put("scan.plain.txt", new String[] { "-plain", "-content-hash", "-emit-source-root", root });
        m.put("scan.json", new String[] { "-json", "-content-hash", root });
        m.put("scan.sarif.json", new String[] { "-sarif", "-include-non-security", "-content-hash", root });
        return m;
    }

    @Test
    void twoRuns_areByteIdentical() throws Exception {
        for (Map.Entry<String, String[]> e : scenarios().entrySet()) {
            assertEquals(run(e.getValue()), run(e.getValue()), e.getKey() + " differs between two runs");
        }
    }

    @Test
    void output_matchesGoldenFilesByteForByte() throws Exception {
        boolean update = "true".equalsIgnoreCase(System.getenv(UPDATE_ENV));
        String outDir = System.getenv(OUT_ENV);
        for (Map.Entry<String, String[]> e : scenarios().entrySet()) {
            String actual = run(e.getValue());
            if (outDir != null && !outDir.isBlank()) {
                writeBytes(Paths.get(outDir).resolve(e.getKey()), actual);
            }
            Path golden = EXPECTED.resolve(e.getKey());
            if (update) {
                writeBytes(golden, actual);
            }
            assertEquals(readBytes(golden), actual, e.getKey() + " diverges from golden " + golden);
        }
    }

    @Test
    void output_hasLfLineEndingsAndForwardSlashPathsOnly() throws Exception {
        for (Map.Entry<String, String[]> e : scenarios().entrySet()) {
            String out = run(e.getValue());
            assertFalse(out.contains("\r"), e.getKey() + " must use \n line endings only");
            assertFalse(out.contains("fixture\\"), e.getKey() + " must not contain backslash path separators");
        }
        assertTrue(run(scenarios().get("scan.csv")).contains("," + FIXTURE_PREFIX + "/,"),
                "source_root must be a forward-slashed, working-directory-relative path");
        String sarif = run(scenarios().get("scan.sarif.json"));
        assertTrue(sarif.contains("\"" + FIXTURE_PREFIX + "/alpha/AlphaAuthTest.java\""),
                "SARIF URIs must carry the forward-slashed prefix and the real relative source path");
        assertTrue(sarif.contains("\"" + FIXTURE_PREFIX + "/Beta/auth_test.go\""),
                "a Go test must be located by its real .go path, not a synthesised .java path");
        assertFalse(sarif.contains("auth_test.java"), "no .java URI may be synthesised for a Go file");
    }

    @Test
    void output_isIndependentOfDefaultLocaleAndTimeZone() throws Exception {
        Map<String, String> baseline = new LinkedHashMap<>();
        Locale.setDefault(Locale.ROOT);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        for (Map.Entry<String, String[]> e : scenarios().entrySet()) {
            baseline.put(e.getKey(), run(e.getValue()));
        }
        // Decimal comma, non-ASCII digits and an extreme UTC offset: the most
        // hostile defaults available on a developer or CI machine.
        for (Locale hostile : List.of(Locale.GERMANY, Locale.forLanguageTag("ar-SA-u-nu-arab"),
                Locale.forLanguageTag("tr-TR"))) {
            Locale.setDefault(hostile);
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            for (Map.Entry<String, String[]> e : scenarios().entrySet()) {
                assertEquals(baseline.get(e.getKey()), run(e.getValue()),
                        e.getKey() + " depends on default locale " + hostile);
            }
        }
    }

    @Test
    void files_areEmittedInPlatformIndependentOrder() throws Exception {
        List<String> classes = new ArrayList<>();
        for (String line : run(scenarios().get("scan.csv")).split("\n")) {
            if (line.startsWith("com.acme.")) {
                String fqcn = line.substring(0, line.indexOf(','));
                if (classes.isEmpty() || !classes.get(classes.size() - 1).equals(fqcn)) {
                    classes.add(fqcn);
                }
            }
        }
        // Code-point order of the forward-slashed relative path: "Beta/" sorts
        // before "alpha/" on every OS, whereas a case-insensitive file system
        // (NTFS, APFS) walks them the other way round.
        assertEquals(List.of("com.acme.beta.BetaCryptoTest", "com.acme.alpha.AlphaAuthTest",
                "com.acme.zeta.nested.ZetaInputTest"), classes);
    }

    private static String run(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), false)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void writeBytes(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBytes(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
