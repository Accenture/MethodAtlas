package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@code -content-hash} flag.
 *
 * <p>
 * These tests verify that:
 * </p>
 * <ul>
 * <li>the {@code content_hash} column is absent from CSV when the flag is not
 * supplied</li>
 * <li>the {@code content_hash} column is present in CSV and plain output when
 * the flag is supplied</li>
 * <li>the hash value is a 64-character lowercase hexadecimal string (SHA-256)</li>
 * <li>all test methods belonging to the same class share the same hash value</li>
 * <li>two distinct source classes produce distinct hash values</li>
 * <li>the SARIF {@code properties.contentHash} field follows the same
 * presence/absence rules</li>
 * <li>the YAML {@code contentHash} configuration key enables the feature</li>
 * </ul>
 */
public class MethodAtlasAppContentHashTest {

    // -------------------------------------------------------------------------
    // CSV mode — header
    // -------------------------------------------------------------------------

    @Test
    void csvMode_headerExcludesContentHashColumn_byDefault(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        assertEquals("fqcn,method,loc,tags", lines.get(0),
                "Default CSV header must not include content_hash");
    }

    @Test
    void csvMode_headerIncludesContentHashColumn_whenEnabled(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-content-hash", tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        assertEquals("fqcn,method,loc,tags,content_hash", lines.get(0),
                "CSV header must include content_hash when flag is present");
    }

    // -------------------------------------------------------------------------
    // CSV mode — hash value shape
    // -------------------------------------------------------------------------

    @Test
    void csvMode_contentHashIs64CharLowercaseHex_whenEnabled(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-content-hash", tempDir.toString() });
        List<String> dataLines = nonEmptyLines(output).subList(1, nonEmptyLines(output).size());

        assertFalse(dataLines.isEmpty(), "Expected at least one data row");
        for (String line : dataLines) {
            List<String> fields = parseCsvFields(line);
            // fqcn, method, loc, tags, content_hash
            assertEquals(5, fields.size(), "Expected 5 CSV fields with -content-hash: " + line);
            String hash = fields.get(4);
            assertEquals(64, hash.length(), "SHA-256 hex must be 64 characters: " + hash);
            assertTrue(hash.matches("[0-9a-f]{64}"), "Hash must be lowercase hex: " + hash);
        }
    }

    // -------------------------------------------------------------------------
    // CSV mode — same class → same hash; different classes → different hashes
    // -------------------------------------------------------------------------

    @Test
    void csvMode_methodsInSameClass_shareIdenticalHash(@TempDir Path tempDir) throws Exception {
        // SampleOneTest.java contains multiple test methods in one class
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-content-hash", tempDir.toString() });
        List<String> dataLines = nonEmptyLines(output).subList(1, nonEmptyLines(output).size());

        List<String> sampleOneHashes = new ArrayList<>();
        for (String line : dataLines) {
            List<String> fields = parseCsvFields(line);
            if (fields.get(0).contains("SampleOneTest")) {
                sampleOneHashes.add(fields.get(4));
            }
        }

        assertTrue(sampleOneHashes.size() >= 2,
                "SampleOneTest should have at least 2 test methods");
        String first = sampleOneHashes.get(0);
        for (String hash : sampleOneHashes) {
            assertEquals(first, hash,
                    "All methods in SampleOneTest must share the same content hash");
        }
    }

    @Test
    void csvMode_distinctClasses_produceDifferentHashes(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");
        copyFixture(tempDir, "AnotherTest.java");

        String output = runApp(new String[] { "-content-hash", tempDir.toString() });
        List<String> dataLines = nonEmptyLines(output).subList(1, nonEmptyLines(output).size());

        String hashSampleOne = null;
        String hashAnother = null;

        for (String line : dataLines) {
            List<String> fields = parseCsvFields(line);
            if (fields.get(0).contains("SampleOneTest") && hashSampleOne == null) {
                hashSampleOne = fields.get(4);
            } else if (fields.get(0).contains("AnotherTest") && hashAnother == null) {
                hashAnother = fields.get(4);
            }
        }

        assertNotNull(hashSampleOne, "Expected a hash for SampleOneTest");
        assertNotNull(hashAnother, "Expected a hash for AnotherTest");
        assertFalse(hashSampleOne.equals(hashAnother),
                "Different source classes must produce different content hashes");
    }

    // -------------------------------------------------------------------------
    // Plain mode
    // -------------------------------------------------------------------------

    @Test
    void plainMode_noHashToken_byDefault(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-plain", tempDir.toString() });

        assertFalse(output.contains("HASH="), "Plain mode without -content-hash must not emit HASH= token");
    }

    @Test
    void plainMode_hashTokenPresent_whenEnabled(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-plain", "-content-hash", tempDir.toString() });

        assertTrue(output.contains("HASH="), "Plain mode with -content-hash must emit HASH= token");
        // Every data line must contain a 64-char hex value after HASH=
        for (String line : nonEmptyLines(output)) {
            int hashIdx = line.indexOf("HASH=");
            assertTrue(hashIdx >= 0, "Expected HASH= in: " + line);
            String hashValue = line.substring(hashIdx + 5).split(",")[0].trim();
            assertEquals(64, hashValue.length(), "HASH value must be 64 hex chars: " + hashValue);
            assertTrue(hashValue.matches("[0-9a-f]{64}"), "HASH must be lowercase hex: " + hashValue);
        }
    }

    // -------------------------------------------------------------------------
    // SARIF mode
    // -------------------------------------------------------------------------

    @Test
    void sarifMode_contentHashAbsent_byDefault(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-sarif", tempDir.toString() });
        JsonNode results = new ObjectMapper().readTree(output).path("runs").get(0).path("results");

        for (JsonNode result : results) {
            assertTrue(result.path("properties").path("contentHash").isMissingNode(),
                    "contentHash must be absent in SARIF properties when -content-hash is not passed");
        }
    }

    @Test
    void sarifMode_contentHashPresentAndValid_whenEnabled(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-sarif", "-content-hash", tempDir.toString() });
        JsonNode results = new ObjectMapper().readTree(output).path("runs").get(0).path("results");

        assertTrue(results.size() > 0, "Expected at least one result");
        for (JsonNode result : results) {
            JsonNode hashNode = result.path("properties").path("contentHash");
            assertFalse(hashNode.isMissingNode(),
                    "contentHash must be present in SARIF properties when -content-hash is passed");
            String hash = hashNode.asText();
            assertEquals(64, hash.length(), "SHA-256 hex must be 64 characters");
            assertTrue(hash.matches("[0-9a-f]{64}"), "Hash must be lowercase hex");
        }
    }

    @Test
    void sarifMode_methodsInSameClass_shareIdenticalHashInSarif(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp(new String[] { "-sarif", "-content-hash", tempDir.toString() });
        JsonNode results = new ObjectMapper().readTree(output).path("runs").get(0).path("results");

        List<String> sampleOneHashes = new ArrayList<>();
        for (JsonNode result : results) {
            String fqn = result.path("locations").get(0)
                    .path("logicalLocations").get(0)
                    .path("fullyQualifiedName").asText();
            if (fqn.contains("SampleOneTest")) {
                sampleOneHashes.add(result.path("properties").path("contentHash").asText());
            }
        }

        assertTrue(sampleOneHashes.size() >= 2,
                "SampleOneTest should contribute at least 2 SARIF results");
        String first = sampleOneHashes.get(0);
        for (String hash : sampleOneHashes) {
            assertEquals(first, hash,
                    "All SARIF results from the same class must share the same content hash");
        }
    }

    // -------------------------------------------------------------------------
    // YAML config
    // -------------------------------------------------------------------------

    @Test
    void yamlConfig_contentHashTrue_enablesHashColumn(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "contentHash: true\n", StandardCharsets.UTF_8);

        String output = runApp(new String[] { "-config", configFile.toString(), tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        assertEquals("fqcn,method,loc,tags,content_hash", lines.get(0),
                "YAML contentHash:true must add content_hash column to CSV header");
    }

    @Test
    void yamlConfig_contentHashFalse_noHashColumn(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "contentHash: false\n", StandardCharsets.UTF_8);

        String output = runApp(new String[] { "-config", configFile.toString(), tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        assertEquals("fqcn,method,loc,tags", lines.get(0),
                "YAML contentHash:false must not add content_hash column");
    }

    @Test
    void cliFlagOverridesYamlConfig_enablesHash(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        // YAML does not set contentHash (defaults to false)
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "outputMode: csv\n", StandardCharsets.UTF_8);

        // CLI -content-hash must enable despite YAML not setting it
        String output = runApp(new String[] {
                "-config", configFile.toString(), "-content-hash", tempDir.toString()
        });
        List<String> lines = nonEmptyLines(output);

        assertEquals("fqcn,method,loc,tags,content_hash", lines.get(0),
                "CLI -content-hash must enable hash column even when YAML does not set it");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppContentHashTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static String runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static List<String> nonEmptyLines(String text) {
        String[] parts = text.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static List<String> parseCsvFields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                current.append(ch);
                i++;
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
                i++;
                continue;
            }
            if (ch == ',') {
                out.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(ch);
            i++;
        }
        out.add(current.toString());
        return out;
    }
}
