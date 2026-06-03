package org.egothor.methodatlas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the caching contract of {@link SourceContent#ofFile(Path)}: the file
 * is read at most once and the outcome is memoised, giving every consumer a
 * stable, read-once view.
 */
class SourceContentTest {

    @Test
    void ofFile_readsFileContentOnFirstAccess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world");

        SourceContent content = SourceContent.ofFile(file);

        assertEquals(Optional.of("hello world"), content.get());
    }

    @Test
    void ofFile_cachesFirstReadAndIgnoresLaterChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "first");
        SourceContent content = SourceContent.ofFile(file);

        assertEquals(Optional.of("first"), content.get(), "first call reads the file");

        Files.writeString(file, "CHANGED ON DISK");
        assertEquals(Optional.of("first"), content.get(),
                "second call must return the cached value, not re-read the file");

        Files.delete(file);
        assertEquals(Optional.of("first"), content.get(),
                "value stays cached even after the file is deleted");
    }

    @Test
    void ofFile_cachesAnUnreadableFileAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("missing.txt");
        SourceContent content = SourceContent.ofFile(file);

        assertTrue(content.get().isEmpty(), "a missing file yields an empty result");

        Files.writeString(file, "now it exists");
        assertTrue(content.get().isEmpty(),
                "the empty result is cached and not re-evaluated once the file appears");
    }

    @Test
    void ofFile_rejectsNullPath() {
        assertThrows(NullPointerException.class, () -> SourceContent.ofFile(null));
    }
}
