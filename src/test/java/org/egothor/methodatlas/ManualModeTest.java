package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ManualMode} sealed interface and its
 * {@link ManualMode.Prepare} and {@link ManualMode.Consume} record
 * implementations.
 */
@Tag("unit")
@Tag("manual-mode")
class ManualModeTest {

    private static final Path WORK_DIR = Path.of("/work");
    private static final Path RESPONSE_DIR = Path.of("/responses");

    // -------------------------------------------------------------------------
    // ManualMode.Prepare
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Prepare stores workDir and responseDir verbatim")
    @Tag("positive")
    void prepare_fieldsStoredVerbatim() {
        ManualMode.Prepare p = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);

        assertSame(WORK_DIR, p.workDir());
        assertSame(RESPONSE_DIR, p.responseDir());
    }

    @Test
    @DisplayName("Prepare implements ManualMode")
    @Tag("positive")
    void prepare_implementsManualMode() {
        ManualMode m = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);

        assertInstanceOf(ManualMode.class, m);
        assertInstanceOf(ManualMode.Prepare.class, m);
    }

    @Test
    @DisplayName("two Prepare records with same paths are equal")
    @Tag("positive")
    void prepare_equality_samePaths() {
        ManualMode.Prepare a = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);
        ManualMode.Prepare b = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("two Prepare records with different workDir are not equal")
    @Tag("positive")
    void prepare_equality_differentWorkDir_notEqual() {
        ManualMode.Prepare a = new ManualMode.Prepare(Path.of("/a"), RESPONSE_DIR);
        ManualMode.Prepare b = new ManualMode.Prepare(Path.of("/b"), RESPONSE_DIR);

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("two Prepare records with different responseDir are not equal")
    @Tag("positive")
    void prepare_equality_differentResponseDir_notEqual() {
        ManualMode.Prepare a = new ManualMode.Prepare(WORK_DIR, Path.of("/r1"));
        ManualMode.Prepare b = new ManualMode.Prepare(WORK_DIR, Path.of("/r2"));

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Prepare workDir and responseDir may be the same path")
    @Tag("positive")
    void prepare_sameDir_allowed() {
        ManualMode.Prepare p = new ManualMode.Prepare(WORK_DIR, WORK_DIR);

        assertEquals(p.workDir(), p.responseDir());
    }

    // -------------------------------------------------------------------------
    // ManualMode.Consume
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Consume stores workDir and responseDir verbatim")
    @Tag("positive")
    void consume_fieldsStoredVerbatim() {
        ManualMode.Consume c = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);

        assertSame(WORK_DIR, c.workDir());
        assertSame(RESPONSE_DIR, c.responseDir());
    }

    @Test
    @DisplayName("Consume implements ManualMode")
    @Tag("positive")
    void consume_implementsManualMode() {
        ManualMode m = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);

        assertInstanceOf(ManualMode.class, m);
        assertInstanceOf(ManualMode.Consume.class, m);
    }

    @Test
    @DisplayName("two Consume records with same paths are equal")
    @Tag("positive")
    void consume_equality_samePaths() {
        ManualMode.Consume a = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);
        ManualMode.Consume b = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("two Consume records with different responseDir are not equal")
    @Tag("positive")
    void consume_equality_differentResponseDir_notEqual() {
        ManualMode.Consume a = new ManualMode.Consume(WORK_DIR, Path.of("/r1"));
        ManualMode.Consume b = new ManualMode.Consume(WORK_DIR, Path.of("/r2"));

        assertNotEquals(a, b);
    }

    // -------------------------------------------------------------------------
    // Sealed interface exhaustiveness
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("pattern switch on ManualMode covers both permitted subtypes")
    @Tag("positive")
    void sealedInterface_switchCoversAllTypes() {
        ManualMode prepare = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);
        ManualMode consume = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);

        assertEquals("prepare", label(prepare));
        assertEquals("consume", label(consume));
    }

    @Test
    @DisplayName("Prepare and Consume with same paths are not equal to each other")
    @Tag("positive")
    void prepare_and_consume_notEqual() {
        ManualMode.Prepare p = new ManualMode.Prepare(WORK_DIR, RESPONSE_DIR);
        ManualMode.Consume c = new ManualMode.Consume(WORK_DIR, RESPONSE_DIR);

        assertNotEquals(p, c);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String label(ManualMode mode) {
        return switch (mode) {
            case ManualMode.Prepare ignored -> "prepare";
            case ManualMode.Consume ignored -> "consume";
        };
    }
}
