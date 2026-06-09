package org.egothor.methodatlas.detect.secrets.internal;

import java.util.List;
import org.egothor.methodatlas.api.CredentialCategory;

/**
 * One rule from the credential-detection catalog.
 *
 * @param id          unique rule identifier; never {@code null}
 * @param category    candidate category assigned on a match; never {@code null}
 * @param anchors     literal markers fed to the Aho-Corasick prefilter; empty means
 *                    the rule is unanchored and runs in the entropy pass
 * @param pattern     confirm regex applied around an anchor hit (or over string
 *                    literals for unanchored rules); never {@code null}
 * @param entropyMin  Shannon-entropy floor for unanchored rules; {@code 0} means
 *                    use the detector's default threshold
 * @param description short human description; may be {@code null}
 * @param provenance  note recording the published source of the pattern; may be {@code null}
 * @since 4.1.0
 */
public record CredentialRule(
        String id, CredentialCategory category, List<String> anchors, String pattern,
        double entropyMin, String description, String provenance) {

    /**
     * Defensively copies {@code anchors} (treating {@code null} as empty).
     */
    public CredentialRule {
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }

    /**
     * Indicates whether this rule has no literal anchors.
     *
     * @return {@code true} when the rule runs in the entropy pass
     */
    public boolean unanchored() {
        return anchors.isEmpty();
    }
}
