package org.egothor.methodatlas.emit;

/**
 * Masks credential values for safe inclusion in reports.
 *
 * <p>Values longer than {@code 2 * CLEAR_EDGE} characters keep their first and
 * last {@link #CLEAR_EDGE} characters in cleartext; the middle portion is
 * replaced with bullet characters ({@code •}). Shorter values are fully masked.
 * The returned string always has the same length as the input.</p>
 *
 * @since 4.1.0
 */
public final class CredentialMasker {

    /** Number of leading/trailing clear characters retained for long values. */
    private static final int CLEAR_EDGE = 4;

    /** Bullet used to replace masked characters. */
    private static final char BULLET = '•';

    private CredentialMasker() {
        // utility class
    }

    /**
     * Masks {@code value}, preserving its length. Values longer than
     * {@code 2 * CLEAR_EDGE} keep their first and last {@value #CLEAR_EDGE}
     * characters; shorter values are fully masked.
     *
     * @param value raw value; never {@code null}
     * @return masked value of equal length
     */
    public static String mask(final String value) {
        final int len = value.length();
        if (len <= CLEAR_EDGE * 2) {
            return String.valueOf(BULLET).repeat(len);
        }
        final int middleLen = len - CLEAR_EDGE * 2;
        final StringBuilder sb = new StringBuilder(len);
        sb.append(value, 0, CLEAR_EDGE)
          .append(String.valueOf(BULLET).repeat(middleLen))
          .append(value, len - CLEAR_EDGE, len);
        return sb.toString();
    }
}
