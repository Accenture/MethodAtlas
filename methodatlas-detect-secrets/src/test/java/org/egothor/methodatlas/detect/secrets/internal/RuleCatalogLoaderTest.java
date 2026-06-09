package org.egothor.methodatlas.detect.secrets.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RuleCatalogLoaderTest {

    /**
     * Lower bound on the bundled catalog size. The catalog only ever grows; this
     * floor turns an accidental truncation or a botched merge into a test failure
     * rather than a silent coverage regression.
     */
    private static final int MIN_BUNDLED_RULES = 160;

    @Test
    void loadsBundledCatalogAndEveryPatternCompiles() {
        RuleCatalog catalog = RuleCatalogLoader.loadBundled();
        assertFalse(catalog.rules().isEmpty(), "bundled catalog must not be empty");
        for (CredentialRule rule : catalog.rules()) {
            assertNotNull(rule.id());
            assertNotNull(rule.category());
            try {
                Pattern.compile(rule.pattern());
            } catch (RuntimeException e) {
                fail("rule " + rule.id() + " has an uncompilable pattern: " + e.getMessage());
            }
        }
    }

    @Test
    void everyRuleIdIsUnique() {
        RuleCatalog catalog = RuleCatalogLoader.loadBundled();
        Set<String> seen = new HashSet<>();
        for (CredentialRule rule : catalog.rules()) {
            assertTrue(seen.add(rule.id()),
                    "duplicate rule id (collides in SARIF secret/<id> and the CSV rule_id): " + rule.id());
        }
    }

    @Test
    void everyRuleHasAtMostOneCapturingGroup() {
        // The value-extraction convention: a rule's single capturing group is the
        // secret value; a rule with no group treats the whole match as the value.
        // A second capturing group would silently extract the wrong span, so it is
        // a defect — alternations must use non-capturing groups (?:...).
        RuleCatalog catalog = RuleCatalogLoader.loadBundled();
        for (CredentialRule rule : catalog.rules()) {
            int groups = Pattern.compile(rule.pattern()).matcher("").groupCount();
            assertTrue(groups <= 1,
                    "rule " + rule.id() + " has " + groups
                            + " capturing groups; use (?:...) for alternations so only the value is captured");
        }
    }

    @Test
    void bundledCatalogMeetsMinimumSize() {
        RuleCatalog catalog = RuleCatalogLoader.loadBundled();
        assertTrue(catalog.rules().size() >= MIN_BUNDLED_RULES,
                "bundled catalog shrank below the expected floor of " + MIN_BUNDLED_RULES
                        + " rules (was " + catalog.rules().size() + ")");
    }

    @Test
    void bundledCatalogContainsAwsAndGenericPasswordRules() {
        RuleCatalog catalog = RuleCatalogLoader.loadBundled();
        assertTrue(catalog.rules().stream().anyMatch(r -> r.id().equals("aws-access-key-id")));
        assertTrue(catalog.rules().stream().anyMatch(r -> r.id().equals("generic-password-assignment")));
    }

    @Test
    void parsesFromString() {
        String yaml = """
                rules:
                  - id: test-rule
                    category: PROVIDER_TOKEN
                    anchors: ['XYZ']
                    pattern: 'XYZ[0-9]{4}'
                    description: 'test'
                """;
        RuleCatalog catalog = RuleCatalogLoader.loadFromString(yaml);
        assertEquals(1, catalog.rules().size());
        assertEquals("test-rule", catalog.rules().get(0).id());
        assertEquals(1, catalog.rules().get(0).anchors().size());
    }

    @Test
    void catalogExposesSha256OfSource() {
        RuleCatalog catalog = RuleCatalogLoader.loadFromString("rules: []");
        assertNotNull(catalog.sha256());
        assertEquals(64, catalog.sha256().length(), "SHA-256 hex is 64 chars");
    }
}
