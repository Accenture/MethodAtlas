package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.egothor.methodatlas.api.CredentialScanUnit;

/**
 * Selects files to scan for credentials by mask and reads each into a
 * {@link CredentialScanUnit}. The default mask is the test-file suffix list; an
 * optional glob override widens the scan to arbitrary source.
 *
 * @since 4.1.0
 */
public final class CredentialScanUnitSource {

    private static final Logger LOG = Logger.getLogger(CredentialScanUnitSource.class.getName());

    private final List<String> suffixes;
    private final PathMatcher globMatcher;

    /**
     * Creates a source.
     *
     * @param suffixes test-file suffixes used when no glob override is given; never {@code null}
     * @param glob     optional glob (e.g. {@code "**}{@code /*.java"}); {@code null} uses {@code suffixes}
     */
    public CredentialScanUnitSource(List<String> suffixes, String glob) {
        this.suffixes = List.copyOf(suffixes);
        this.globMatcher = glob == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    /**
     * Walks {@code roots} and returns one unit per matching, readable file, in
     * deterministic sorted order.
     *
     * @param roots scan roots; never {@code null}
     * @return scan units; never {@code null}
     */
    public List<CredentialScanUnit> collect(List<Path> roots) {
        List<CredentialScanUnit> units = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(this::matches)
                    .sorted()
                    .forEach(p -> readInto(p, units));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + root, e);
            }
        }
        return units;
    }

    private boolean matches(Path p) {
        if (globMatcher != null) {
            return globMatcher.matches(p);
        }
        String name = p.getFileName().toString();
        return suffixes.stream().anyMatch(name::endsWith);
    }

    private void readInto(Path p, List<CredentialScanUnit> units) {
        try {
            String source = Files.readString(p, StandardCharsets.UTF_8);
            units.add(new CredentialScanUnit(p.toAbsolutePath(), null, source, languageOf(p)));
        } catch (IOException e) {
            // Non-fatal: skip unreadable / binary files.
            LOG.log(Level.FINE, e, () -> "Skipping unreadable file: " + p);
        }
    }

    /**
     * Derives a short language identifier from a file's extension.
     *
     * @param p the file path; never {@code null}
     * @return the lowercase extension (without the dot), or {@code null} when the
     *         path has no file name or no extension
     */
    /* default */ static String languageOf(Path p) {
        Path fileName = p.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : null;
    }
}
