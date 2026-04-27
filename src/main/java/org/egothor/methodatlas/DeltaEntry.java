package org.egothor.methodatlas;

import java.util.Set;

import org.egothor.methodatlas.api.ScanRecord;

/**
 * A single change entry in a MethodAtlas delta report.
 *
 * <p>
 * Each entry describes one test method that was added, removed, or modified
 * between two scan outputs. Entries are produced by {@link DeltaReport#compute}
 * and consumed by {@link DeltaEmitter} to produce human-readable output.
 * </p>
 *
 * <h2>Change types</h2>
 *
 * <ul>
 * <li>{@link ChangeType#ADDED} — method is present in the <em>after</em> scan
 *     but absent from the <em>before</em> scan. {@link #before()} is
 *     {@code null}.</li>
 * <li>{@link ChangeType#REMOVED} — method is present in the <em>before</em>
 *     scan but absent from the <em>after</em> scan. {@link #after()} is
 *     {@code null}.</li>
 * <li>{@link ChangeType#MODIFIED} — method is present in both scans but one
 *     or more comparable fields differ. Both {@link #before()} and
 *     {@link #after()} are non-{@code null}. The {@link #changedFields()} set
 *     names each field that differs.</li>
 * </ul>
 *
 * <h2>Changed field names</h2>
 *
 * <p>
 * The {@link #changedFields()} set uses the following identifiers, which
 * correspond to CSV column names or human-readable labels:
 * </p>
 *
 * <ul>
 * <li>{@code "loc"} — lines of code changed</li>
 * <li>{@code "tags"} — JUnit {@code @Tag} set changed</li>
 * <li>{@code "source"} — {@code content_hash} differs (class source was
 *     edited); only present when both records have a non-{@code null}
 *     {@code contentHash}</li>
 * <li>{@code "ai_security_relevant"} — security-relevance classification
 *     flipped; only present when both records have a non-{@code null}
 *     {@code aiSecurityRelevant}</li>
 * <li>{@code "ai_tags"} — AI taxonomy tag set changed; only present when
 *     both records have non-{@code null} {@code aiTags}</li>
 * </ul>
 *
 * @param changeType    the type of change
 * @param before        the record from the <em>before</em> scan; {@code null}
 *                      for {@link ChangeType#ADDED} entries
 * @param after         the record from the <em>after</em> scan; {@code null}
 *                      for {@link ChangeType#REMOVED} entries
 * @param changedFields names of fields that differ between {@code before} and
 *                      {@code after}; empty for {@link ChangeType#ADDED} and
 *                      {@link ChangeType#REMOVED} entries
 *
 * @see DeltaReport
 * @see DeltaEmitter
 */
public record DeltaEntry(ChangeType changeType, ScanRecord before, ScanRecord after, Set<String> changedFields) {

    /**
     * The type of change represented by a {@link DeltaEntry}.
     */
    public enum ChangeType {

        /** Method is present in the <em>after</em> scan but not the <em>before</em> scan. */
        ADDED,

        /** Method is present in the <em>before</em> scan but not the <em>after</em> scan. */
        REMOVED,

        /** Method is present in both scans but at least one comparable field differs. */
        MODIFIED
    }

    /**
     * Creates an {@code ADDED} entry for a method discovered in the <em>after</em> scan.
     *
     * @param after the record from the <em>after</em> scan
     * @return new ADDED entry
     */
    /* default */ static DeltaEntry added(ScanRecord after) {
        return new DeltaEntry(ChangeType.ADDED, null, after, Set.of());
    }

    /**
     * Creates a {@code REMOVED} entry for a method absent from the <em>after</em> scan.
     *
     * @param before the record from the <em>before</em> scan
     * @return new REMOVED entry
     */
    /* default */ static DeltaEntry removed(ScanRecord before) {
        return new DeltaEntry(ChangeType.REMOVED, before, null, Set.of());
    }

    /**
     * Creates a {@code MODIFIED} entry for a method present in both scans but with
     * differing field values.
     *
     * @param before        the record from the <em>before</em> scan
     * @param after         the record from the <em>after</em> scan
     * @param changedFields names of the fields that differ
     * @return new MODIFIED entry
     */
    /* default */ static DeltaEntry modified(ScanRecord before, ScanRecord after, Set<String> changedFields) {
        return new DeltaEntry(ChangeType.MODIFIED, before, after, Set.copyOf(changedFields));
    }

    /**
     * Returns the primary record for this entry.
     *
     * <p>
     * For {@link ChangeType#ADDED} and {@link ChangeType#MODIFIED} entries this is
     * the {@link #after()} record. For {@link ChangeType#REMOVED} entries this is
     * the {@link #before()} record. Never returns {@code null}.
     * </p>
     *
     * @return the representative record for this entry
     */
    public ScanRecord record() {
        return after != null ? after : before;
    }
}
