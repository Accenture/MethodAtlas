package org.egothor.methodatlas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for {@link PortablePathOrder}. */
class PortablePathOrderTest {

    @Test
    void sortsByCodePoint_notCaseInsensitively() {
        List<Path> paths = new ArrayList<>(List.of(
                Paths.get("root", "alpha", "A.java"),
                Paths.get("root", "Beta", "B.java"),
                Paths.get("root", "zeta", "Z.java")));

        paths.sort(PortablePathOrder.INSTANCE);

        // 'B' (0x42) sorts before 'a' (0x61): the order a case-insensitive
        // file system such as NTFS would give is deliberately not used.
        assertEquals(List.of(
                Paths.get("root", "Beta", "B.java"),
                Paths.get("root", "alpha", "A.java"),
                Paths.get("root", "zeta", "Z.java")), paths);
    }

    @Test
    void result_isIndependentOfInputOrder() {
        List<Path> paths = new ArrayList<>(List.of(
                Paths.get("r", "b", "x.go"), Paths.get("r", "a", "y.go"),
                Paths.get("r", "a.go"), Paths.get("r", "a", "b", "z.go")));
        List<Path> reference = new ArrayList<>(paths);
        reference.sort(PortablePathOrder.INSTANCE);

        for (int seed = 0; seed < 8; seed++) {
            List<Path> shuffled = new ArrayList<>(paths);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            shuffled.sort(PortablePathOrder.INSTANCE);
            assertEquals(reference, shuffled);
        }
    }

    @Test
    void key_joinsNameElementsWithForwardSlash() {
        assertEquals("a/b/c.txt", PortablePathOrder.key(Paths.get("a", "b", "c.txt")));
    }

    @Test
    void key_ofRootOnlyPath_isEmpty() {
        Path root = Paths.get("").toAbsolutePath().getRoot();
        assertTrue(PortablePathOrder.key(root).isEmpty());
    }

    @Test
    void equalPaths_compareAsZero() {
        assertEquals(0, PortablePathOrder.INSTANCE.compare(Paths.get("a", "b"), Paths.get("a", "b")));
    }
}
