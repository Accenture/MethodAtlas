package org.egothor.methodatlas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link CommentBlanker}: spaces replace everything but line
 * terminators, code-point ranges map correctly across surrogate pairs and
 * invalid ranges are rejected.
 */
@Tag("unit")
class CommentBlankerTest {

    @Test
    void blank_replacesWithSpacesButKeepsLineTerminators() throws CommentRedactionException {
        CommentBlanker blanker = new CommentBlanker("ab\r\ncd\nef\rg");

        blanker.blank(1, 9);

        assertEquals("a \r\n  \n  \rg", blanker.result());
    }

    @Test
    void blank_translatesCodePointRangesAcrossSurrogatePairs() throws CommentRedactionException {
        String text = "😀/*x*/😀y";
        CommentBlanker blanker = new CommentBlanker(text);

        blanker.blank(1, 5);

        assertEquals("😀     😀y", blanker.result());
        assertEquals(text.length(), blanker.result().length());
        assertEquals(3, blanker.charIndex(2));
    }

    @Test
    void charIndex_isIdentityWithoutSurrogatesAndAllowsEndOfText() throws CommentRedactionException {
        CommentBlanker blanker = new CommentBlanker("abc");

        assertEquals(2, blanker.charIndex(2));
        assertEquals(3, blanker.charIndex(3));
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,3", "2,1", "5,6"})
    void blank_rejectsInvalidCodePointRanges(int start, int stop) {
        CommentBlanker blanker = new CommentBlanker("abc");

        assertThrows(CommentRedactionException.class, () -> blanker.blank(start, stop));
    }

    @ParameterizedTest
    @CsvSource({"-1,2", "1,1", "2,1", "0,4"})
    void blankChars_rejectsInvalidCharRanges(int from, int to) {
        CommentBlanker blanker = new CommentBlanker("abc");

        assertThrows(CommentRedactionException.class, () -> blanker.blankChars(from, to));
    }

    @Test
    void charIndex_rejectsPositionsOutsideTheText() {
        CommentBlanker blanker = new CommentBlanker("a😀b");

        assertThrows(CommentRedactionException.class, () -> blanker.charIndex(4));
        assertThrows(CommentRedactionException.class, () -> blanker.charIndex(-1));
    }
}
