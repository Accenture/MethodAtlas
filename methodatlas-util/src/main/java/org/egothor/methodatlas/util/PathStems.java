package org.egothor.methodatlas.util;

import java.nio.file.Path;

/**
 * Computes the dot-separated "file stem" (and fully-qualified grouping name)
 * used by discovery plugins to identify a source file relative to the scan
 * root.
 *
 * <p>
 * Historically each plugin re-implemented the same three steps — relativise the
 * file against the root, join the path segments with {@code '.'}, and strip the
 * file-name suffix from the final segment — differing only in which suffix to
 * strip.  This helper is the single canonical implementation.
 * </p>
 *
 * <p>
 * Both paths are converted with {@link Path#toAbsolutePath()} then
 * {@link Path#normalize()} before relativising, so callers may pass either
 * absolute or relative paths as long as both are the same kind.  The suffix is
 * stripped from the last <em>segment</em> only, so intermediate directory names
 * that contain dots are never mistaken for an extension boundary.
 * </p>
 */
public final class PathStems {

    private PathStems() {
        // utility class — no instances
    }

    /**
     * Returns the dot-separated stem of {@code file} relative to {@code root}
     * with a trailing file-name suffix removed from the last segment.
     *
     * <p>
     * The <em>longest</em> matching suffix from {@code suffixesToStrip} is
     * removed (so {@code ".test.ts"} is preferred over {@code ".ts"}).  When no
     * supplied suffix matches the final segment — or none are supplied — the
     * final dot-extension of that segment is stripped as a fallback, preserving
     * the legacy "strip last extension only" behaviour.  Suffixes that contain
     * dots (e.g. {@code ".test.ts"}, {@code "_test.py"}) match naturally.
     * </p>
     *
     * @param root            the scan root
     * @param file            the source file (expected to reside beneath {@code root})
     * @param suffixesToStrip candidate file-name suffixes to remove; may be empty
     * @return dot-separated stem; never {@code null}
     */
    public static String buildFileStem(Path root, Path file, String... suffixesToStrip) {
        Path rel = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        int count = rel.getNameCount();
        StringBuilder sb = new StringBuilder(rel.toString().length());
        for (int i = 0; i < count; i++) {
            String segment = rel.getName(i).toString();
            if (i == count - 1) {
                segment = stripSuffix(segment, suffixesToStrip);
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    /**
     * Strips the longest matching suffix from {@code segment}, falling back to
     * removing the final dot-extension when no supplied suffix matches.
     */
    private static String stripSuffix(String segment, String... suffixesToStrip) {
        String best = null;
        for (String suffix : suffixesToStrip) {
            if (suffix != null && !suffix.isEmpty() && segment.endsWith(suffix)
                    && (best == null || suffix.length() > best.length())) {
                best = suffix;
            }
        }
        if (best != null) {
            return segment.substring(0, segment.length() - best.length());
        }
        int lastDot = segment.lastIndexOf('.');
        return lastDot > 0 ? segment.substring(0, lastDot) : segment;
    }
}
