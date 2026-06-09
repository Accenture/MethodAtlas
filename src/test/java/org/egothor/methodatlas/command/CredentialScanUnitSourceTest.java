package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialScanUnitSourceTest {

    @Test
    void selectsFilesMatchingSuffixes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("FooTest.java"), "class FooTest {}");
        Files.writeString(dir.resolve("Helper.txt"), "ignore me");
        CredentialScanUnitSource source = new CredentialScanUnitSource(List.of("Test.java"), null);
        List<CredentialScanUnit> units = source.collect(List.of(dir));
        assertEquals(1, units.size());
        assertTrue(units.get(0).filePath().getFileName().toString().equals("FooTest.java"));
        assertEquals("java", units.get(0).languageId());
    }

    @Test
    void globOverrideWidensScope(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Prod.java"), "class Prod {}");
        CredentialScanUnitSource source = new CredentialScanUnitSource(List.of("Test.java"), "**/*.java");
        List<CredentialScanUnit> units = source.collect(List.of(dir));
        assertEquals(1, units.size());
    }
}
