package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialDetector;
import org.egothor.methodatlas.api.CredentialDetectorConfig;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.junit.jupiter.api.Test;

class PluginLoaderCredentialDetectorTest {

    private static CredentialDetector stub(String id) {
        return new CredentialDetector() {
            public String detectorId() {
                return id;
            }
            public List<CredentialCandidate> detect(CredentialScanUnit unit) {
                return List.of();
            }
            public boolean hadErrors() {
                return false;
            }
        };
    }

    @Test
    void rejectsDuplicateDetectorIds() {
        assertThrows(IllegalStateException.class,
                () -> PluginLoader.requireUniqueCredentialDetectorIds(List.of(stub("a"), stub("a"))));
    }

    @Test
    void acceptsUniqueDetectorIds() {
        PluginLoader.requireUniqueCredentialDetectorIds(List.of(stub("a"), stub("b")));
    }

    @Test
    void loadsBuiltInDetectorFromClasspath() {
        List<CredentialDetector> detectors = new PluginLoader()
                .loadCredentialDetectors(new CredentialDetectorConfig(4.0, java.util.Optional.empty(), java.util.Map.of()));
        assertTrue(detectors.stream().anyMatch(d -> d.detectorId().equals("builtin-catalog")));
        assertEquals(detectors.size(), detectors.stream().map(CredentialDetector::detectorId).distinct().count());
    }
}
