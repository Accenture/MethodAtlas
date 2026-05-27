// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScanRun}.
 *
 * <p>
 * Covers the compact-constructor validation, the factory's id-shape
 * invariants, and the configuration-fingerprint determinism that lets two
 * runs of the same configuration correlate.
 * </p>
 *
 * @since 1.0.0
 */
class ScanRunTest {

    @Test
    void compactConstructor_rejectsNullRunId() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun(null, Instant.now(), "dev", "abcd"));
    }

    @Test
    void compactConstructor_rejectsBlankRunId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanRun("   ", Instant.now(), "dev", "abcd"));
    }

    @Test
    void compactConstructor_rejectsNullFingerprint() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun("id", Instant.now(), "dev", null));
    }

    @Test
    void compactConstructor_rejectsBlankFingerprint() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanRun("id", Instant.now(), "dev", " "));
    }

    @Test
    void create_runIdIsSixteenHexCharacters() {
        ScanRun run = ScanRun.create("1.2.3", "config-text");
        assertEquals(16, run.runId().length(),
                "Compact id keeps log lines and CSV preamble readable");
        assertTrue(run.runId().matches("[0-9a-f]{16}"),
                "Run id must be lowercase hex");
    }

    @Test
    void create_consecutiveCalls_produceDistinctIds() {
        ScanRun a = ScanRun.create("dev", "config");
        ScanRun b = ScanRun.create("dev", "config");
        assertNotEquals(a.runId(), b.runId(),
                "Each invocation must get its own id even with the same config");
    }

    @Test
    void create_sameConfigText_producesSameFingerprint() {
        ScanRun a = ScanRun.create("dev", "-sarif -ai");
        ScanRun b = ScanRun.create("dev", "-sarif -ai");
        assertEquals(a.configFingerprint(), b.configFingerprint(),
                "Byte-identical configs must produce identical fingerprints");
    }

    @Test
    void create_differentConfigText_producesDifferentFingerprint() {
        ScanRun a = ScanRun.create("dev", "-sarif -ai");
        ScanRun b = ScanRun.create("dev", "-json -ai");
        assertNotEquals(a.configFingerprint(), b.configFingerprint(),
                "Different configs must produce different fingerprints");
    }

    @Test
    void create_nullToolVersion_fallsBackToDev() {
        ScanRun run = ScanRun.create(null, "config");
        assertEquals("dev", run.toolVersion(),
                "A null tool version means the JAR has no manifest entry; "
                        + "we fall back to 'dev' so log lines remain readable");
    }

    @Test
    void create_blankToolVersion_fallsBackToDev() {
        ScanRun run = ScanRun.create("  ", "config");
        assertEquals("dev", run.toolVersion());
    }

    @Test
    void create_startedAtIsRecent() {
        Instant before = Instant.now();
        ScanRun run = ScanRun.create("dev", "config");
        Instant after = Instant.now();
        assertNotNull(run.startedAt());
        assertTrue(!run.startedAt().isBefore(before) && !run.startedAt().isAfter(after),
                "startedAt should reflect wall-clock time at construction");
    }

    @Test
    void create_fingerprintIsHex64Characters() {
        ScanRun run = ScanRun.create("dev", "any config text");
        assertEquals(64, run.configFingerprint().length(),
                "SHA-256 hex digest must be 64 characters");
        assertTrue(run.configFingerprint().matches("[0-9a-f]{64}"),
                "Fingerprint must be lowercase hex");
    }
}
