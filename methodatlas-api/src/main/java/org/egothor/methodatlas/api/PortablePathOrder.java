package org.egothor.methodatlas.api;

import java.nio.file.Path;
import java.util.Comparator;

/**
 * A total order on {@link Path} values that is identical on every operating
 * system and file system.
 *
 * <p>
 * {@link java.nio.file.Files#walk(Path, java.nio.file.FileVisitOption...)}
 * returns entries in whatever order the file system yields them (hash order on
 * ext4, alphabetical on NTFS, and so on), and {@link Path#compareTo(Path)} is
 * case-insensitive on Windows but case-sensitive on Unix. Discovery plugins
 * must emit methods in a machine-independent order so that scan output is
 * byte-identical across machines, so they sort the files they find with this
 * comparator.
 * </p>
 *
 * <p>
 * Paths are compared by their name elements joined with {@code '/'}, using
 * {@link String#compareTo(String)} (UTF-16 code-unit order, no locale, no case
 * folding). The comparator is meaningful for paths that share a common root,
 * which is always the case for the files found under one scan root.
 * </p>
 *
 * <p>
 * Stateless and thread-safe.
 * </p>
 */
public final class PortablePathOrder {

    /** The shared comparator instance; never {@code null}. */
    public static final Comparator<Path> INSTANCE = Comparator.comparing(PortablePathOrder::key);

    private PortablePathOrder() {
        // static holder; no instances
    }

    /**
     * Builds the platform-independent sort key of a path.
     *
     * @param path the path; must not be {@code null}
     * @return the name elements of {@code path} joined with {@code '/'}; the
     *         empty string for a path with no name elements
     */
    /* default */ static String key(final Path path) {
        final int count = path.getNameCount();
        final StringBuilder sb = new StringBuilder(path.toString().length());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(path.getName(i));
        }
        return sb.toString();
    }
}
