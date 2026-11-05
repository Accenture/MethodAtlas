// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zeroecho.core.storage.KeyringPassword;

/**
 * Tests for {@link KeyringPasswordSource}: source precedence, file parsing,
 * confirmation, and the minimum length for new keyrings.
 */
class KeyringPasswordSourceTest {

    private static final String LONG = "a-long-enough-password";

    @TempDir
    Path tempDir;

    @Test
    void passwordFileWinsAndDropsTrailingLineBreak() throws Exception {
        Path file = tempDir.resolve("pw");
        Files.writeString(file, LONG + "\r\nignored second line\n");
        KeyringPasswordSource source = new KeyringPasswordSource(file, "NAMED",
                Map.of("NAMED", "other-password", KeyringPasswordSource.DEFAULT_ENV, "default-pw")::get, null);

        assertPassword(LONG, source.resolve(true));
    }

    @Test
    void emptyPasswordFileIsAnError() throws Exception {
        Path file = tempDir.resolve("empty");
        Files.writeString(file, "\n");
        KeyringPasswordSource source = new KeyringPasswordSource(file, null, name -> null, null);

        assertThrows(IOException.class, () -> source.resolve(false));
    }

    @Test
    void namedEnvironmentVariableMustBeSet() {
        KeyringPasswordSource source = new KeyringPasswordSource(null, "UNSET_VAR",
                Map.of(KeyringPasswordSource.DEFAULT_ENV, LONG)::get, null);

        IOException ex = assertThrows(IOException.class, () -> source.resolve(false));
        assertTrue(ex.getMessage().contains("UNSET_VAR"), "the missing variable must be named: " + ex.getMessage());
    }

    @Test
    void namedEnvironmentVariableIsUsed() throws Exception {
        KeyringPasswordSource source = new KeyringPasswordSource(null, "PW", Map.of("PW", LONG)::get, null);

        assertPassword(LONG, source.resolve(false));
    }

    @Test
    void defaultEnvironmentVariableIsTheFallback() throws Exception {
        KeyringPasswordSource source = new KeyringPasswordSource(null, null,
                Map.of(KeyringPasswordSource.DEFAULT_ENV, LONG)::get, prompt -> {
                    throw new AssertionError("the prompt must not be used when the default variable is set");
                });

        assertPassword(LONG, source.resolve(false));
    }

    @Test
    void promptIsUsedLastAndConfirmedForNewKeyrings() throws Exception {
        Deque<String> answers = new ArrayDeque<>(java.util.List.of(LONG, LONG));
        KeyringPasswordSource source = new KeyringPasswordSource(null, null, name -> null,
                prompt -> answers.pop().toCharArray());

        assertPassword(LONG, source.resolve(true));
        assertTrue(answers.isEmpty(), "a new keyring password must be asked for twice");
    }

    @Test
    void mismatchedConfirmationIsRejected() {
        Deque<String> answers = new ArrayDeque<>(java.util.List.of(LONG, LONG + "x"));
        KeyringPasswordSource source = new KeyringPasswordSource(null, null, name -> null,
                prompt -> answers.pop().toCharArray());

        IOException ex = assertThrows(IOException.class, () -> source.resolve(true));
        assertTrue(ex.getMessage().contains("do not match"), ex.getMessage());
    }

    @Test
    void missingSourceRaisesDedicatedException() {
        KeyringPasswordSource source = new KeyringPasswordSource(null, null, name -> null, prompt -> null);

        assertThrows(KeyringPasswordSource.MissingPasswordException.class, () -> source.resolve(false));
    }

    @Test
    void shortPasswordIsAcceptedForExistingButNotForNewKeyrings() throws Exception {
        KeyringPasswordSource source = new KeyringPasswordSource(null, "PW", Map.of("PW", "short")::get, null);

        assertPassword("short", source.resolve(false));
        assertThrows(IOException.class, () -> source.resolve(true));
    }

    @Test
    void guidanceNamesEverySource() {
        String hint = KeyringPasswordSource.howToSupply("-f", "-e");
        assertTrue(hint.contains("-f") && hint.contains("-e") && hint.contains(KeyringPasswordSource.DEFAULT_ENV),
                hint);
    }

    private static void assertPassword(String expected, KeyringPassword actual) {
        try (actual) {
            char[] copy = actual.copy();
            assertArrayEquals(expected.toCharArray(), copy);
            java.util.Arrays.fill(copy, '\0');
        }
    }
}
