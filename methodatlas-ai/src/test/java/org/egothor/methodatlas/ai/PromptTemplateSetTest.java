package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptTemplateSetTest {

    @Test
    void getReturnsTheMatchingMember() {
        PromptTemplateSet set = new PromptTemplateSet("c", "t", "d");
        assertEquals("c", set.get(PromptTemplateKind.CLASSIFICATION));
        assertEquals("t", set.get(PromptTemplateKind.TRIAGE_APPENDIX));
        assertEquals("d", set.get(PromptTemplateKind.DEDICATED_TRIAGE));
    }

    @Test
    void withReplacesOnlyTheNamedMember() {
        PromptTemplateSet base = new PromptTemplateSet("c", "t", "d");
        PromptTemplateSet next = base.with(PromptTemplateKind.TRIAGE_APPENDIX, "T2");
        assertEquals("c", next.classification());
        assertEquals("T2", next.triageAppendix());
        assertEquals("d", next.dedicatedTriage());
    }

    @Test
    void hashIs64CharHexAndStable() {
        PromptTemplateSet set = PromptTemplateSet.defaults();
        String first = set.hash(PromptTemplateKind.CLASSIFICATION);
        assertEquals(64, first.length(), "SHA-256 hex is 64 chars");
        assertTrue(first.matches("[0-9a-f]{64}"), "lowercase hex");
        assertEquals(first, set.hash(PromptTemplateKind.CLASSIFICATION), "hash must be stable");
    }

    @Test
    void hashDiffersWhenTemplateBodyDiffers() {
        PromptTemplateSet a = PromptTemplateSet.defaults();
        PromptTemplateSet b = a.with(PromptTemplateKind.CLASSIFICATION, a.classification() + " edit");
        assertNotEquals(a.hash(PromptTemplateKind.CLASSIFICATION), b.hash(PromptTemplateKind.CLASSIFICATION),
                "editing the template body must change its hash");
    }

    @Test
    void defaultsIsShared() {
        assertSame(PromptTemplateSet.defaults(), PromptTemplateSet.defaults(),
                "defaults() returns the shared immutable instance");
    }

    @Test
    void constructorRejectsNullMember() {
        assertThrows(NullPointerException.class, () -> new PromptTemplateSet(null, "t", "d"));
    }
}
