package org.egothor.methodatlas.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps MethodAtlas security taxonomy tags to CWE (Common Weakness Enumeration) identifiers.
 *
 * <p>
 * The mapping is deterministic and static — it is never derived from AI output. CWE IDs are
 * appended to scan records after classification when the {@code -ai-cwe} flag is enabled,
 * enabling auditors to trace test coverage to weakness categories required by PCI-DSS,
 * NIST SP 800-53, and ISO 27001 Annex A.
 * </p>
 *
 * <p>
 * Tags that have no CWE mapping (e.g. {@code owasp}) or are unrecognised return an empty list.
 * Multiple tags in a method's suggestion may map to overlapping CWEs; duplicates are removed
 * while preserving encounter order.
 * </p>
 *
 * @since 3.0.0
 */
public final class CweMapping {

    private CweMapping() {
    }

    /**
     * Returns the union of CWE identifiers for all supplied tags, deduplicated in encounter order.
     *
     * @param tags AI taxonomy tags assigned to a method; may be {@code null} or empty
     * @return unmodifiable list of CWE identifiers; empty when no mapping exists
     * @since 3.0.0
     */
    public static List<String> forTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            result.addAll(forTag(tag));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the CWE identifiers for a single taxonomy tag.
     *
     * @param tag a single AI taxonomy tag; must not be {@code null}
     * @return unmodifiable list of CWE identifiers; empty when the tag has no mapping
     */
    /* default */ static List<String> forTag(String tag) {
        return switch (tag) {
            case "auth" -> List.of("CWE-287");
            case "access-control" -> List.of("CWE-285");
            case "crypto" -> List.of("CWE-327");
            case "input-validation" -> List.of("CWE-20");
            case "injection" -> List.of("CWE-74");
            case "data-protection" -> List.of("CWE-311");
            case "logging" -> List.of("CWE-778");
            case "error-handling" -> List.of("CWE-209");
            default -> List.of();
        };
    }
}
