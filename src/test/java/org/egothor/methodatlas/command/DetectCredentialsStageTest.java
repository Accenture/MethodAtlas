package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.egothor.methodatlas.api.CredentialDetector;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.egothor.methodatlas.emit.CredentialFinding;
import org.junit.jupiter.api.Test;

class DetectCredentialsStageTest {

    private static CredentialDetector fixed(CredentialCandidate... candidates) {
        return new CredentialDetector() {
            public String detectorId() {
                return "stub";
            }
            public List<CredentialCandidate> detect(CredentialScanUnit unit) {
                return List.of(candidates);
            }
            public boolean hadErrors() {
                return false;
            }
        };
    }

    @Test
    void buildsFindingsFromCandidatesWithoutAi() {
        CredentialCandidate c = new CredentialCandidate("stub", "r", CredentialCategory.PROVIDER_TOKEN,
                3, 1, 3, 10, "AKIAIOSFODNN7EXAMPLE");
        DetectCredentialsStage stage = new DetectCredentialsStage(List.of(fixed(c)), Map.of());
        List<CredentialFinding> findings = stage.run(List.of(
                new CredentialScanUnit(Path.of("Foo.java"), "com.acme.Foo", "x", "java")));
        assertEquals(1, findings.size());
        assertEquals(3, findings.get(0).candidate().beginLine());
        assertEquals("com.acme.Foo", findings.get(0).fqcn());
        assertTrue(findings.get(0).credibilityScore() == null, "no AI -> blank score");
    }

    @Test
    void attributesEnclosingMethodWhenLineInRange() {
        CredentialCandidate c = new CredentialCandidate("stub", "r", CredentialCategory.PASSWORD_ASSIGNMENT,
                5, 1, 5, 10, "P@ssw0rd-long");
        Map<Path, List<DetectCredentialsStage.MethodRange>> index = Map.of(
                Path.of("Foo.java"), List.of(new DetectCredentialsStage.MethodRange("shouldLogin", 4, 8)));
        DetectCredentialsStage stage = new DetectCredentialsStage(List.of(fixed(c)), index);
        List<CredentialFinding> findings = stage.run(List.of(
                new CredentialScanUnit(Path.of("Foo.java"), "com.acme.Foo", "x", "java")));
        assertEquals("shouldLogin", findings.get(0).method());
    }
}
