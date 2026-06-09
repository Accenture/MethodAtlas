package org.egothor.methodatlas.detect.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.egothor.methodatlas.api.CredentialDetectorConfig;
import org.egothor.methodatlas.api.CredentialScanUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogCredentialDetectorTest {

    private CatalogCredentialDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CatalogCredentialDetector();
        detector.configure(new CredentialDetectorConfig(4.0, java.util.Optional.empty(), java.util.Map.of()));
    }

    private CredentialScanUnit unit(String src) {
        return new CredentialScanUnit(Path.of("Foo.java"), "com.acme.Foo", src, "java");
    }

    @Test
    void detectsAwsKeyWithCorrectLine() {
        String src = "line1\nString k = \"AKIAIOSFODNN7EXAMPLE\";\n";
        List<CredentialCandidate> hits = detector.detect(unit(src));
        assertTrue(hits.stream().anyMatch(h ->
                h.ruleId().equals("aws-access-key-id") && h.beginLine() == 2));
    }

    @Test
    void detectsCredentialSeparatedFromUrl() {
        String src = "String url = \"jdbc:postgresql://db.internal/app\";\n"
                + "ds.setPassword(\"sup3rS3cretValue\");\n";
        List<CredentialCandidate> hits = detector.detect(unit(src));
        assertTrue(hits.stream().anyMatch(h -> h.category() == CredentialCategory.PASSWORD_ASSIGNMENT),
                "must flag the separated password assignment");
    }

    @Test
    void capturesFullProviderTokenValueNotJustAGroup() {
        // Regression: the AWS pattern has an incidental alternation; the matched
        // value must be the whole token, never a sub-group such as "KIA".
        String src = "String k = \"AKIAIOSFODNN7EXAMPLE\";\n";
        CredentialCandidate hit = detector.detect(unit(src)).stream()
                .filter(h -> h.ruleId().equals("aws-access-key-id"))
                .findFirst()
                .orElseThrow();
        assertEquals("AKIAIOSFODNN7EXAMPLE", hit.matchedValue());
    }

    @Test
    void capturesQuotedValueForPasswordAssignment() {
        // The single capturing group (the quoted literal) is the value, not the keyword.
        String src = "password = \"hunter2longvalue\";\n";
        CredentialCandidate hit = detector.detect(unit(src)).stream()
                .filter(h -> h.ruleId().equals("generic-password-assignment"))
                .findFirst()
                .orElseThrow();
        assertEquals("hunter2longvalue", hit.matchedValue());
    }

    @Test
    void deduplicatesCandidateMatchedViaMultipleAnchors() {
        // "secret" (keyword) and "token" (inside the value) are both anchors of the
        // same rule, and both confirm the identical span; it must be reported once.
        String src = "String secret = \"mytokenvalue\";\n";
        long count = detector.detect(unit(src)).stream()
                .filter(h -> h.ruleId().equals("generic-password-assignment"))
                .count();
        assertEquals(1, count);
    }

    @Test
    void detectsPasswordPassedToLoginMethod() {
        String src = "client.login(\"admin\", \"P@ssw0rd-Long-Enough\");\n"
                + "String password = \"P@ssw0rd-Long-Enough\";\n";
        List<CredentialCandidate> hits = detector.detect(unit(src));
        assertTrue(hits.stream().anyMatch(h -> h.ruleId().equals("generic-password-assignment")));
    }

    @Test
    void entropyPassFlagsHighEntropyLiteral() {
        String src = "String t = \"aGVsbG8x9Zk3Qp7Lm2Rt8Yw4Xs6Nv\";\n";
        List<CredentialCandidate> hits = detector.detect(unit(src));
        assertTrue(hits.stream().anyMatch(h -> h.category() == CredentialCategory.HIGH_ENTROPY));
    }

    @Test
    void lowEntropyLiteralIsNotFlaggedAsHighEntropy() {
        String src = "String t = \"aaaaaaaaaaaaaaaaaaaaaaaa\";\n";
        List<CredentialCandidate> hits = detector.detect(unit(src));
        assertTrue(hits.stream().noneMatch(h -> h.category() == CredentialCategory.HIGH_ENTROPY));
    }

    @Test
    void identicalInputYieldsIdenticalCandidates() {
        String src = "String k = \"AKIAIOSFODNN7EXAMPLE\";\n";
        assertEquals(detector.detect(unit(src)), detector.detect(unit(src)));
    }

    @Test
    void detectorIdIsStable() {
        assertEquals("builtin-catalog", detector.detectorId());
    }
}
