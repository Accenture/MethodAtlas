// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.nio.file.Path;

import org.egothor.methodatlas.ClassificationOverride;

/**
 * Loads classification override files into {@link ClassificationOverride}
 * instances.
 *
 * <p>
 * Override files carry human-reviewed corrections to AI classification
 * results. They are persisted in YAML using the
 * {@code ClassificationOverride} schema and re-applied on every subsequent
 * MethodAtlas run so that reviewer decisions reproduce deterministically
 * across CI builds.
 * </p>
 *
 * <h2>Null handling</h2>
 *
 * <p>
 * Passing {@code null} as the override path is a legitimate signal that the
 * caller wants the empty no-op singleton — typical when no
 * {@code -override-file} flag was supplied. The loader returns
 * {@link ClassificationOverride#empty()} in that case rather than throwing,
 * because the absence of an override file is normal, not exceptional.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * This class is thread-safe. It carries no instance state and the underlying
 * {@link ClassificationOverride#load(Path)} call is itself stateless.
 * </p>
 *
 * @see ClassificationOverride
 * @since 1.0.0
 */
public final class OverrideLoader {

    /**
     * Creates a new override loader. The loader carries no instance state.
     */
    public OverrideLoader() {
        // Intentionally empty; OverrideLoader is stateless.
    }

    /**
     * Loads the classification override file at the given path, or returns the
     * empty no-op singleton when no override file was configured.
     *
     * <p>
     * The empty singleton is a sentinel: callers can apply it unconditionally
     * to every classification without checking for {@code null}, which
     * simplifies the orchestration loops in the {@link Command}
     * implementations.
     * </p>
     *
     * @param overrideFile path to the YAML override file, or {@code null} when
     *                     the caller wants the empty no-op singleton
     * @return loaded override set, or the empty singleton; never {@code null}
     * @throws IllegalArgumentException if the file exists but cannot be read
     *                                  or contains invalid YAML
     */
    public ClassificationOverride load(Path overrideFile) {
        if (overrideFile == null) {
            return ClassificationOverride.empty();
        }
        try {
            return ClassificationOverride.load(overrideFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load override file: " + overrideFile, e);
        }
    }
}
