package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.junit.jupiter.api.Test;

class SarifEmitterCredentialTest {

    private CredentialFinding finding(Double score) {
        CredentialCandidate c = new CredentialCandidate("builtin-catalog", "aws-access-key-id",
                CredentialCategory.PROVIDER_TOKEN, 12, 8, 12, 28, "AKIAIOSFODNN7EXAMPLE");
        return new CredentialFinding(c, Path.of("com/acme/Foo.java"), "com.acme.Foo", "shouldAuth",
                score, score == null ? null : "s3.amazonaws.com", score == null ? null : "looks live");
    }

    private String emit(CredentialFinding f) {
        SarifEmitter emitter = new SarifEmitter(false, false, "");
        emitter.recordSecret("com/acme/Foo.java", f);
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw);
        }
        return sw.toString();
    }

    @Test
    void highScoreBecomesErrorWithScoreInMessage() {
        String sarif = emit(finding(0.92));
        assertTrue(sarif.contains("\"level\" : \"error\""), "0.92 -> error");
        assertTrue(sarif.contains("credibility 0.92"), "score embedded in message text");
        assertTrue(sarif.contains("s3.amazonaws.com"), "endpoint in message");
        assertTrue(sarif.contains("•"), "snippet masked in SARIF");
    }

    @Test
    void midScoreBecomesWarning() {
        assertTrue(emit(finding(0.55)).contains("\"level\" : \"warning\""));
    }

    @Test
    void lowScoreBecomesNote() {
        assertTrue(emit(finding(0.20)).contains("\"level\" : \"note\""));
    }

    @Test
    void noScoreDefaultsToWarning() {
        assertTrue(emit(finding(null)).contains("\"level\" : \"warning\""));
    }
}
