// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ai.AiOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AiRuntimeBuilder}.
 *
 * <p>
 * The builder has three responsibilities tested independently here:
 * {@link AiRuntimeBuilder#buildEngine} respects the AI-disabled sentinel,
 * {@link AiRuntimeBuilder#buildCache} returns the empty no-op cache when no
 * file is supplied, and {@link AiRuntimeBuilder#resolveTaxonomyInfo}
 * formats the three documented descriptor shapes.
 * </p>
 *
 * @since 1.0.0
 */
class AiRuntimeBuilderTest {

    @Test
    void buildEngine_whenAiDisabled_returnsNullSentinel() {
        AiOptions disabled = AiOptions.builder().enabled(false).build();
        AiRuntimeBuilder builder = new AiRuntimeBuilder();

        assertNull(builder.buildEngine(disabled),
                "Null is the documented sentinel for 'AI disabled' "
                        + "and lets the scan loop short-circuit AI work");
    }

    @Test
    void buildCache_whenNullPath_returnsInactiveEmptyCache() {
        AiRuntimeBuilder builder = new AiRuntimeBuilder();

        AiResultCache cache = builder.buildCache(null);

        assertNotNull(cache, "buildCache must never return null");
        assertFalse(cache.isActive(),
                "An empty cache must report isActive()=false; the scan loop "
                        + "uses this to short-circuit cache work entirely");
    }

    @Test
    void buildCache_missingFile_throwsIllegalArgumentExceptionNamingPath(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("not-a-real-cache.csv");
        AiRuntimeBuilder builder = new AiRuntimeBuilder();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder.buildCache(missing));

        assertTrue(ex.getMessage().contains(missing.toString()),
                "Exception message should name the offending cache path so "
                        + "users can diagnose it from the CLI error");
    }

    @Test
    void resolveTaxonomyInfo_whenAiInactive_returnsDisabledMarker() {
        AiOptions options = AiOptions.builder().enabled(false).build();
        AiRuntimeBuilder builder = new AiRuntimeBuilder();

        String descriptor = builder.resolveTaxonomyInfo(options, false);

        assertEquals("n/a (AI disabled)", descriptor);
    }

    @Test
    void resolveTaxonomyInfo_builtInDefault_returnsBuiltInWithMode() {
        AiOptions options = AiOptions.builder().enabled(false).build();
        AiRuntimeBuilder builder = new AiRuntimeBuilder();

        String descriptor = builder.resolveTaxonomyInfo(options, true);

        assertNotNull(descriptor);
        assertTrue(descriptor.startsWith("built-in/"),
                "Built-in taxonomy descriptor must begin with 'built-in/'");
    }
}
