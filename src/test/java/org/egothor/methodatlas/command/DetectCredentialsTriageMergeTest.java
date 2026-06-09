package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.egothor.methodatlas.emit.CredentialFinding;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DetectCredentialsStage#mergeVerdicts(List, Map)}.
 */
class DetectCredentialsTriageMergeTest {

    private static CredentialFinding finding(String fqcn) {
        CredentialCandidate c = new CredentialCandidate("stub", "aws-access-key-id",
                CredentialCategory.PROVIDER_TOKEN, 3, 1, 3, 10, "AKIAIOSFODNN7EXAMPLE");
        return new CredentialFinding(c, Path.of("Foo.java"), fqcn, "shouldAuth", null, null, null);
    }

    @Test
    void mergesVerdictIntoFindingByIndex() {
        List<CredentialFinding> merged = DetectCredentialsStage.mergeVerdicts(
                List.of(finding("com.acme.Foo")),
                Map.of(0, new CredentialTriageVerdict(0, 0.93, "s3.amazonaws.com", "live")));
        assertEquals(0.93, merged.get(0).credibilityScore(), 1e-9);
        assertEquals("s3.amazonaws.com", merged.get(0).endpoint());
        assertEquals("live", merged.get(0).rationale());
        assertEquals("com.acme.Foo", merged.get(0).fqcn(), "non-triage fields preserved");
    }

    @Test
    void findingWithoutMatchingVerdictIsUnchanged() {
        List<CredentialFinding> merged = DetectCredentialsStage.mergeVerdicts(
                List.of(finding("com.acme.Foo")), Map.of());
        assertNull(merged.get(0).credibilityScore());
        assertNull(merged.get(0).endpoint());
    }
}
