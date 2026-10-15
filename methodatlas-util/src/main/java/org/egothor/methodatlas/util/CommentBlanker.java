// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.util;

import org.egothor.methodatlas.api.CommentRedactionException;

/**
 * Overwrites ranges of a source text with U+0020 spaces while keeping line
 * terminators, so the result has exactly the length and line structure of the
 * input.
 *
 * <p>
 * Lexers built on ANTLR's {@code CharStreams.fromString} address the input in
 * <em>code points</em>, whereas Java strings are indexed in UTF-16 {@code char}
 * units. This class accepts code-point ranges (as reported by
 * {@code Token.getStartIndex()} / {@code Token.getStopIndex()}) and translates
 * them, so a supplementary character (an emoji in a string, for example) before
 * a comment cannot shift the redaction.
 * </p>
 *
 * <p>
 * Instances are single-use and not thread-safe.
 * </p>
 *
 * @since 4.1.0
 */
public final class CommentBlanker {

    private final char[] chars;
    /** Char index of every code point; {@code null} when the text has no surrogate pairs. */
    private final int[] charIndexOfCodePoint;
    private final int codePointCount;

    /**
     * Prepares to blank ranges of {@code source}.
     *
     * @param source text whose comments are to be blanked; must not be
     *               {@code null}
     */
    public CommentBlanker(String source) {
        this.chars = source.toCharArray();
        this.codePointCount = source.codePointCount(0, source.length());
        if (codePointCount == source.length()) {
            this.charIndexOfCodePoint = null;
        } else {
            this.charIndexOfCodePoint = new int[codePointCount + 1];
            int charIndex = 0;
            for (int cp = 0; cp < codePointCount; cp++) {
                charIndexOfCodePoint[cp] = charIndex;
                charIndex += Character.charCount(source.codePointAt(charIndex));
            }
            charIndexOfCodePoint[codePointCount] = source.length();
        }
    }

    /**
     * Translates a code-point index into the matching {@code char} index.
     *
     * @param codePointIndex index in code points, {@code 0..codePointCount}
     * @return index in {@code char} units
     * @throws CommentRedactionException if the index lies outside the text
     */
    public int charIndex(int codePointIndex) throws CommentRedactionException {
        if (codePointIndex < 0 || codePointIndex > codePointCount) {
            throw new CommentRedactionException("token position " + codePointIndex + " lies outside the text");
        }
        return charIndexOfCodePoint == null ? codePointIndex : charIndexOfCodePoint[codePointIndex];
    }

    /**
     * Replaces every character in the inclusive code-point range with a space,
     * except {@code '\n'} and {@code '\r'}, which are kept.
     *
     * @param startCodePoint first code point of the range
     * @param stopCodePoint  last code point of the range (inclusive)
     * @throws CommentRedactionException if the range is empty, reversed or
     *                                   outside the text
     */
    public void blank(int startCodePoint, int stopCodePoint) throws CommentRedactionException {
        if (stopCodePoint < startCodePoint || stopCodePoint >= codePointCount) {
            throw new CommentRedactionException(
                    "invalid comment range " + startCodePoint + ".." + stopCodePoint);
        }
        blankChars(charIndex(startCodePoint), charIndex(stopCodePoint + 1));
    }

    /**
     * Replaces every character in the half-open {@code char} range with a
     * space, except {@code '\n'} and {@code '\r'}, which are kept.
     *
     * @param fromChar first {@code char} index of the range
     * @param toChar   {@code char} index after the last character of the range
     * @throws CommentRedactionException if the range is empty, reversed or
     *                                   outside the text
     */
    public void blankChars(int fromChar, int toChar) throws CommentRedactionException {
        if (fromChar < 0 || toChar <= fromChar || toChar > chars.length) {
            throw new CommentRedactionException("invalid comment range " + fromChar + ".." + toChar);
        }
        for (int i = fromChar; i < toChar; i++) {
            if (chars[i] != '\n' && chars[i] != '\r') {
                chars[i] = ' ';
            }
        }
    }

    /**
     * Returns the text with all blanked ranges applied.
     *
     * @return text of the same length as the original source; never
     *         {@code null}
     */
    public String result() {
        return new String(chars);
    }
}
