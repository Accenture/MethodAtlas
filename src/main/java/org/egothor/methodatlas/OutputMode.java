package org.egothor.methodatlas;

/**
 * Output formats supported by the MethodAtlas application.
 *
 * <p>
 * The selected mode determines both the emitted header and the per-method
 * output representation.
 * </p>
 *
 * @see OutputEmitter
 */
enum OutputMode {

    /**
     * Emits output in comma-separated value format.
     *
     * <p>
     * Fields are escaped according to RFC 4180 rules implemented by
     * {@link OutputEmitter}.
     * </p>
     */
    CSV,

    /**
     * Emits output in a human-readable plain text format.
     */
    PLAIN
}
