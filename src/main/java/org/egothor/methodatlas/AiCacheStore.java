package org.egothor.methodatlas;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the unified AI result cache as a JSON Lines file: one
 * {@link AiCacheEntry} per line.
 *
 * <p>
 * JSON Lines is chosen over a single JSON array so the file streams, appends
 * cleanly, and a single corrupt line degrades to one lost cache entry rather than
 * an unreadable file. The format is independent of the stable per-method scan CSV
 * (which cannot carry credential verdicts or a prompt signature) and of the
 * separate credential CSV; it is the sole carrier of cache state.
 * </p>
 *
 * <p>
 * This class is a stateless, thread-safe utility holder.
 * </p>
 *
 * @since 4.1.0
 */
public final class AiCacheStore {

    private static final Logger LOG = Logger.getLogger(AiCacheStore.class.getName());

    /** Shared, thread-safe JSON mapper (immutable after build). */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AiCacheStore() {
        // utility class
    }

    /**
     * Returns {@code true} if {@code file} appears to be a unified JSON-Lines cache
     * rather than a legacy scan CSV, by inspecting the first non-whitespace byte.
     *
     * @param file path to inspect; never {@code null}
     * @return {@code true} when the first non-blank character is an opening brace
     * @throws IOException if the file cannot be read
     */
    public static boolean looksLikeJsonLines(Path file) throws IOException {
        return looksLikeJsonLines(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code true} if the already-read {@code lines} appear to be a unified
     * JSON-Lines cache, by inspecting the first non-blank line.
     *
     * <p>
     * This line-based overload lets a caller read the file once and drive both the
     * format decision and {@link #read(List)} from the same in-memory copy.
     * </p>
     *
     * @param lines file lines, already read; never {@code null}
     * @return {@code true} when the first non-blank character is an opening brace
     */
    /* default */ static boolean looksLikeJsonLines(List<String> lines) {
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.charAt(0) == '{';
            }
        }
        return false;
    }

    /**
     * Reads all cache entries from a JSON-Lines file. Blank lines are ignored and a
     * single unparseable line is skipped (logged at {@code FINE}) so a partially
     * corrupt cache never aborts a scan.
     *
     * @param file path to the cache file; never {@code null}
     * @return the parsed entries in file order; never {@code null}; may be empty
     * @throws IOException if the file cannot be read
     */
    public static List<AiCacheEntry> read(Path file) throws IOException {
        return read(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses cache entries from already-read JSON-Lines {@code lines}. Blank lines are
     * ignored and a single unparseable line is skipped (logged at {@code FINE}) so a
     * partially corrupt cache never aborts a scan.
     *
     * @param lines file lines, already read; never {@code null}
     * @return the parsed entries in line order; never {@code null}; may be empty
     */
    /* default */ static List<AiCacheEntry> read(List<String> lines) {
        List<AiCacheEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                entries.add(MAPPER.readValue(trimmed, AiCacheEntry.class));
            } catch (JacksonException e) {
                LOG.log(Level.FINE, e, () -> "Skipping unparseable AI cache line");
            }
        }
        return entries;
    }

    /**
     * Writes cache entries to a JSON-Lines file, one entry per line, overwriting any
     * existing file. Parent directories are created if absent.
     *
     * @param file    destination path; never {@code null}
     * @param entries entries to write; never {@code null}
     * @throws IOException if the file cannot be written
     */
    public static void write(Path file, Collection<AiCacheEntry> entries) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (AiCacheEntry entry : entries) {
                writer.write(MAPPER.writeValueAsString(entry));
                writer.write('\n');
            }
        }
    }
}
