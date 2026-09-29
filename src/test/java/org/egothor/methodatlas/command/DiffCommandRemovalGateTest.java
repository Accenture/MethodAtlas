// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DiffCommand} removal-justification gate ({@code -require-justification}).
 *
 * @since 3.0.0
 */
@Tag("unit")
@Tag("diff")
@Tag("security")
class DiffCommandRemovalGateTest {

    private static final String BEFORE_HEADER =
            "fqcn,method,loc,tags,display_name,content_hash,ai_security_relevant,ai_tags,ai_interaction_score\n";

    // -------------------------------------------------------------------------
    // No security removals — gate passes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gate passes with exit 0 when no methods are removed")
    @Tag("positive")
    void noRemovals_exits0(@TempDir Path tmp) throws IOException {
        String csv = BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n";
        Path before = write(tmp, "before.csv", csv);
        Path after = write(tmp, "after.csv", csv);

        DiffCommand cmd = new DiffCommand(before, after, true, null);
        assertEquals(0, execute(cmd));
    }

    @Test
    @DisplayName("gate passes with exit 0 when only non-security methods are removed")
    @Tag("positive")
    void onlyNonSecurityRemoved_exits0(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,false,,0.0\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);

        DiffCommand cmd = new DiffCommand(before, after, true, null);
        assertEquals(0, execute(cmd));
    }

    @Test
    @DisplayName("gate passes with exit 0 when removed method has no ai_security_relevant column")
    @Tag("positive")
    void removedMethodWithoutAiColumn_exits0(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.Foo,test_x,5,\n");
        Path after = write(tmp, "after.csv",
                "fqcn,method,loc,tags,display_name\n");

        DiffCommand cmd = new DiffCommand(before, after, true, null);
        assertEquals(0, execute(cmd));
    }

    // -------------------------------------------------------------------------
    // Security removal without justification — gate fails
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gate exits 1 when security-relevant method is removed without justification")
    @Tag("negative")
    @Tag("security")
    void securityRemoval_noJustification_exits1(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);

        DiffCommand cmd = new DiffCommand(before, after, true, null);
        assertEquals(1, execute(cmd));
    }

    @Test
    @DisplayName("gate output contains GATE FAILED message with the removed method details")
    @Tag("negative")
    @Tag("security")
    void securityRemoval_noJustification_outputContainsGateFailedMessage(@TempDir Path tmp)
            throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);

        StringWriter sw = new StringWriter();
        DiffCommand cmd = new DiffCommand(before, after, true, null);
        cmd.execute(new PrintWriter(sw));
        String output = sw.toString();

        assertTrue(output.contains("GATE FAILED"), "expected GATE FAILED in output");
        assertTrue(output.contains("com.acme.AuthTest"), "expected fqcn in output");
        assertTrue(output.contains("test_login"), "expected method name in output");
    }

    // -------------------------------------------------------------------------
    // Security removal with justification — gate passes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gate exits 0 when security-relevant removal has override entry as justification")
    @Tag("positive")
    @Tag("security")
    void securityRemoval_withJustification_exits0(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);
        Path overrideFile = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.AuthTest
                    method: test_login
                    securityRelevant: false
                    reason: "test was superseded by integration test suite"
                """);

        DiffCommand cmd = new DiffCommand(before, after, true, overrideFile);
        assertEquals(0, execute(cmd));
    }

    @Test
    @DisplayName("class-level override justifies all methods removed from that class")
    @Tag("positive")
    @Tag("security")
    void classLevelOverride_justifiesAllRemovals(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n"
                + "com.acme.AuthTest,test_logout,8,,,,true,,0.9\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);
        Path overrideFile = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.AuthTest
                    securityRelevant: false
                    reason: "class removed — replaced by BDD suite"
                """);

        DiffCommand cmd = new DiffCommand(before, after, true, overrideFile);
        assertEquals(0, execute(cmd));
    }

    // -------------------------------------------------------------------------
    // Mixed removal — one justified, one not — gate fails
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gate fails when one removal is justified but another is not")
    @Tag("negative")
    @Tag("security")
    void mixedRemovals_oneJustified_oneNot_exits1(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n"
                + "com.acme.AuthTest,test_token,8,,,,true,,0.9\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);
        Path overrideFile = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.AuthTest
                    method: test_login
                    securityRelevant: false
                    reason: "superseded"
                """);

        DiffCommand cmd = new DiffCommand(before, after, true, overrideFile);
        assertEquals(1, execute(cmd));
    }

    // -------------------------------------------------------------------------
    // requireJustification=false — gate is bypassed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("without requireJustification flag security removal exits 0")
    @Tag("positive")
    void noRequireJustification_securityRemoval_exits0(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", BEFORE_HEADER
                + "com.acme.AuthTest,test_login,10,,,,true,,1.0\n");
        Path after = write(tmp, "after.csv", BEFORE_HEADER);

        DiffCommand cmd = new DiffCommand(before, after, false, null);
        assertEquals(0, execute(cmd));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int execute(DiffCommand cmd) throws IOException {
        return cmd.execute(new PrintWriter(new StringWriter()));
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Path writeOverride(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("overrides.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }
}
