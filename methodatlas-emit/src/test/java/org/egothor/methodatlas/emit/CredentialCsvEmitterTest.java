package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.junit.jupiter.api.Test;

class CredentialCsvEmitterTest {

    private CredentialFinding finding(Double score) {
        CredentialCandidate c = new CredentialCandidate("builtin-catalog", "aws-access-key-id",
                CredentialCategory.PROVIDER_TOKEN, 12, 8, 12, 28, "AKIAIOSFODNN7EXAMPLE");
        return new CredentialFinding(c, Path.of("src/test/java/com/acme/FooTest.java"), "com.acme.FooTest",
                "shouldAuthenticate", score, score == null ? null : "s3.amazonaws.com",
                score == null ? null : "looks live");
    }

    @Test
    void writesHeaderAndMaskedRow() {
        StringWriter sw = new StringWriter();
        CredentialCsvEmitter emitter = new CredentialCsvEmitter(false);
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw, List.of(finding(0.92)));
        }
        String out = sw.toString();
        assertTrue(out.startsWith("file,fqcn,method,begin_line,begin_column,end_line,"
                + "rule_id,category,detector_id,snippet_masked,credibility_score,endpoint,rationale"),
                "header must match schema v1");
        assertTrue(out.contains("src/test/java/com/acme/FooTest.java"), "forward-slash file path");
        assertTrue(out.contains("com.acme.FooTest"));
        assertTrue(out.contains("shouldAuthenticate"));
        assertTrue(out.contains("AKIA"), "masked edges visible");
        assertTrue(out.contains("•"), "middle masked");
        assertTrue(out.contains("0.92"));
        assertTrue(out.contains("s3.amazonaws.com"));
    }

    @Test
    void noAiRunLeavesTriageColumnsBlank() {
        StringWriter sw = new StringWriter();
        CredentialCsvEmitter emitter = new CredentialCsvEmitter(false);
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw, List.of(finding(null)));
        }
        String dataLine = sw.toString().split("\\R")[1];
        assertTrue(dataLine.endsWith(",,"), "credibility_score, endpoint, rationale all blank → trailing empties");
    }

    @Test
    void showValuesEmitsRawSecret() {
        StringWriter sw = new StringWriter();
        CredentialCsvEmitter emitter = new CredentialCsvEmitter(true);
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw, List.of(finding(0.5)));
        }
        assertTrue(sw.toString().contains("AKIAIOSFODNN7EXAMPLE"), "raw value when showValues=true");
    }

    @Test
    void escapesFieldsContainingCommas() {
        StringWriter sw = new StringWriter();
        CredentialCandidate c = new CredentialCandidate("builtin-catalog", "generic-password-assignment",
                CredentialCategory.PASSWORD_ASSIGNMENT, 1, 1, 1, 5, "a,b,c");
        CredentialFinding f = new CredentialFinding(c, Path.of("Foo.java"), "Foo", null, 0.5,
                "host, with comma", "why, indeed");
        CredentialCsvEmitter emitter = new CredentialCsvEmitter(true);
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw, List.of(f));
        }
        String out = sw.toString();
        assertTrue(out.contains("\"a,b,c\""), "comma value quoted");
        assertTrue(out.contains("\"host, with comma\""));
    }
}
