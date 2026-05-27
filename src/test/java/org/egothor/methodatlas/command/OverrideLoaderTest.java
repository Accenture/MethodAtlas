// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.egothor.methodatlas.emit.ClassificationOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OverrideLoader}.
 *
 * <p>
 * The loader has three branches: {@code null} path returns the empty
 * singleton, a parseable file returns a populated override, and an
 * unreadable or malformed file is reported as an
 * {@link IllegalArgumentException} that names the offending path. All three
 * branches are exercised.
 * </p>
 *
 * @since 1.0.0
 */
class OverrideLoaderTest {

    @Test
    void load_nullPath_returnsEmptySingleton() {
        ClassificationOverride empty = ClassificationOverride.empty();
        OverrideLoader loader = new OverrideLoader();

        ClassificationOverride loaded = loader.load(null);

        assertSame(empty, loaded,
                "Null path should return the empty singleton without I/O");
    }

    @Test
    void load_validYaml_returnsNonNullOverride(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("overrides.yaml");
        Files.writeString(file,
                "overrides:\n"
              + "  - fqcn: com.acme.Test\n"
              + "    method: example\n"
              + "    securityRelevant: true\n"
              + "    tags: [security]\n");
        OverrideLoader loader = new OverrideLoader();

        ClassificationOverride loaded = loader.load(file);

        assertNotNull(loaded, "Loaded override must not be null");
    }

    @Test
    void load_missingFile_throwsIllegalArgumentExceptionNamingPath(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.yaml");
        OverrideLoader loader = new OverrideLoader();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.load(missing));

        assertTrue(ex.getMessage().contains(missing.toString()),
                "Exception message should name the offending path so users can diagnose it");
    }
}
