package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliArgsCredentialsTest {

    @Test
    void detectSecretsDefaultsOff() {
        CliConfig cfg = CliArgs.parse(".");
        assertFalse(cfg.detectSecrets());
    }

    @Test
    void parsesDetectSecretsAndOptions() {
        CliConfig cfg = CliArgs.parse("-detect-secrets", "-secrets-out", "out.csv",
                "-secrets-include", "**/*.java", "-secrets-show-values", ".");
        assertTrue(cfg.detectSecrets());
        assertEquals("out.csv", cfg.secretsOut().getFileName().toString());
        assertEquals("**/*.java", cfg.secretsInclude());
        assertTrue(cfg.secretsShowValues());
    }

    @Test
    void parsesThresholds() {
        CliConfig cfg = CliArgs.parse("-detect-secrets",
                "-secrets-error-threshold", "0.9", "-secrets-warning-threshold", "0.3",
                "-secrets-min-score", "0.1", ".");
        assertEquals(0.9, cfg.secretsErrorThreshold(), 0.0001);
        assertEquals(0.3, cfg.secretsWarningThreshold(), 0.0001);
        assertEquals(0.1, cfg.secretsMinScore(), 0.0001);
    }

    @Test
    void defaultsAreSensible() {
        CliConfig cfg = CliArgs.parse(".");
        assertEquals("methodatlas-credentials.csv", cfg.secretsOut().getFileName().toString());
        assertEquals(0.8, cfg.secretsErrorThreshold(), 0.0001);
        assertEquals(0.4, cfg.secretsWarningThreshold(), 0.0001);
        assertEquals(0.0, cfg.secretsMinScore(), 0.0001);
        assertFalse(cfg.secretsSeparateLlm());
        assertFalse(cfg.secretsShowValues());
    }
}
