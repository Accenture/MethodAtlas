package org.egothor.methodatlas.command;

import java.nio.file.Path;

/**
 * Path helpers for reporting source locations relative to the scan root.
 */
/* default */ final class SourcePaths {

    private SourcePaths() {
    }

    /**
     * Derives the forward-slash path of {@code file} relative to the scan {@code root}.
     *
     * @param root scan root; must not be {@code null}
     * @param file source file of a discovered method; may be {@code null}
     * @return root-relative path with {@code /} separators, or {@code null} when {@code file}
     *         is {@code null} or lies outside {@code root} (callers then fall back to a
     *         synthesised path)
     */
    /* default */ static String relativeSourcePath(Path root, Path file) {
        if (file == null) {
            return null;
        }
        try {
            Path rel = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
            if (rel.startsWith("..")) {
                return null;
            }
            return rel.toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            // Different drive/root on Windows: not relativisable.
            return null;
        }
    }
}
