package org.egothor.methodatlas.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import org.egothor.methodatlas.eval.AccuracyMetrics.Counts;
import org.egothor.methodatlas.eval.AccuracyMetrics.Result;
import org.egothor.methodatlas.eval.AccuracyMetrics.Sample;
import org.egothor.methodatlas.eval.AccuracyMetrics.TagResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Offline, provider-free tests of the pure metric math.
 */
class AccuracyMetricsTest {

    private static final double EPS = 1e-9;

    private static Sample rel(boolean predicted, boolean truth) {
        return new Sample("m", predicted, truth, List.of(), null);
    }

    private static Sample tags(List<String> predicted, List<String> truth) {
        return new Sample("m", false, null, predicted, truth);
    }

    private static TagResult tag(Result r, String name) {
        return r.tags().stream().filter(t -> t.tag().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void relevanceConfusionMatrixOnKnownPairs() {
        Result r = AccuracyMetrics.compute(List.of(
                rel(true, true), rel(true, true), rel(true, true),
                rel(true, false), rel(false, true), rel(false, false)));

        assertEquals(new Counts(3, 1, 1, 1), r.relevance());
        assertEquals(6, r.relevanceSamples());
        assertEquals(0.75, r.relevance().precision().getAsDouble(), EPS);
        assertEquals(0.75, r.relevance().recall().getAsDouble(), EPS);
        assertEquals(0.75, r.relevance().f1().getAsDouble(), EPS);
        assertEquals(4.0 / 6.0, r.relevance().accuracy().getAsDouble(), EPS);
        assertEquals(4, r.relevance().support());
    }

    @Test
    void allCorrectGivesPerfectScores() {
        Result r = AccuracyMetrics.compute(List.of(rel(true, true), rel(false, false)));

        assertEquals(1.0, r.relevance().precision().getAsDouble(), EPS);
        assertEquals(1.0, r.relevance().recall().getAsDouble(), EPS);
        assertEquals(1.0, r.relevance().f1().getAsDouble(), EPS);
        assertEquals(1.0, r.relevance().accuracy().getAsDouble(), EPS);
    }

    @Test
    void allWrongGivesZeroScoresNotUndefined() {
        Result r = AccuracyMetrics.compute(List.of(rel(true, false), rel(false, true)));

        assertEquals(0.0, r.relevance().precision().getAsDouble(), EPS);
        assertEquals(0.0, r.relevance().recall().getAsDouble(), EPS);
        assertEquals(0.0, r.relevance().f1().getAsDouble(), EPS);
        assertEquals(0.0, r.relevance().accuracy().getAsDouble(), EPS);
    }

    @Test
    void emptyInputIsUndefinedNotZero() {
        Result r = AccuracyMetrics.compute(List.of());

        assertEquals(new Counts(0, 0, 0, 0), r.relevance());
        assertEquals(OptionalDouble.empty(), r.relevance().precision());
        assertEquals(OptionalDouble.empty(), r.relevance().recall());
        assertEquals(OptionalDouble.empty(), r.relevance().f1());
        assertEquals(OptionalDouble.empty(), r.relevance().accuracy());
        assertTrue(r.tags().isEmpty());
        assertEquals(OptionalDouble.empty(), r.tagsMacroF1());
    }

    @Test
    void zeroSupportAndNoPredictedPositivesGuardDivisionByZero() {
        Counts onlyNegatives = AccuracyMetrics.compute(List.of(rel(false, false))).relevance();

        assertEquals(OptionalDouble.empty(), onlyNegatives.precision());
        assertEquals(OptionalDouble.empty(), onlyNegatives.recall());
        assertEquals(OptionalDouble.empty(), onlyNegatives.f1());
        assertEquals(1.0, onlyNegatives.accuracy().getAsDouble(), EPS);

        Counts onlyFalsePositive = AccuracyMetrics.compute(List.of(rel(true, false))).relevance();
        assertEquals(0.0, onlyFalsePositive.precision().getAsDouble(), EPS);
        assertEquals(OptionalDouble.empty(), onlyFalsePositive.recall());
        assertEquals(0.0, onlyFalsePositive.f1().getAsDouble(), EPS);
    }

    @ParameterizedTest
    @CsvSource({
            "1,0,0,1.0,1.0,1.0",
            "1,1,0,0.5,1.0,0.6666666666666666",
            "1,0,1,1.0,0.5,0.6666666666666666",
            "0,1,1,0.0,0.0,0.0",
    })
    void f1MatchesHarmonicMeanOfPrecisionAndRecall(int tp, int fp, int fn, double p, double r, double f1) {
        Counts c = new Counts(tp, fp, fn, 0);

        assertEquals(p, c.precision().getAsDouble(), EPS);
        assertEquals(r, c.recall().orElse(0.0), EPS);
        assertEquals(f1, c.f1().getAsDouble(), EPS);
    }

    @Test
    void samplesWithoutRelevanceLabelAreExcludedFromRelevance() {
        Result r = AccuracyMetrics.compute(List.of(
                new Sample("a", true, null, List.of(), List.of()), rel(true, true)));

        assertEquals(1, r.relevanceSamples());
        assertEquals(new Counts(1, 0, 0, 0), r.relevance());
    }

    @Test
    void perTagMetricsOnKnownPairs() {
        Result r = AccuracyMetrics.compute(List.of(
                tags(List.of("security", "auth"), List.of("security", "auth")),
                tags(List.of("security", "logging"), List.of("security", "auth")),
                tags(List.of(), List.of("security", "crypto")),
                tags(List.of("security", "input-validation"), List.of())));

        assertEquals(4, r.tagSamples());
        Counts security = tag(r, "security").counts();
        assertEquals(new Counts(2, 1, 1, 0), security);
        assertEquals(3, security.support());
        Counts auth = tag(r, "auth").counts();
        assertEquals(new Counts(1, 0, 1, 2), auth);
        assertEquals(1.0, auth.precision().getAsDouble(), EPS);
        assertEquals(0.5, auth.recall().getAsDouble(), EPS);
        Counts logging = tag(r, "logging").counts();
        assertEquals(0, logging.support());
        assertEquals(OptionalDouble.empty(), logging.recall());
        assertEquals(0.0, logging.f1().getAsDouble(), EPS);
        assertEquals(List.of("auth", "crypto", "input-validation", "logging", "security"),
                r.tags().stream().map(TagResult::tag).toList());
    }

    @Test
    void microAndMacroAveragesOnKnownPairs() {
        Result r = AccuracyMetrics.compute(List.of(
                tags(List.of("security", "auth"), List.of("security", "auth")),
                tags(List.of("security", "logging"), List.of("security", "auth")),
                tags(List.of(), List.of("security", "crypto")),
                tags(List.of("security", "input-validation"), List.of())));

        assertEquals(3, r.tagsMicro().tp());
        assertEquals(3, r.tagsMicro().fp());
        assertEquals(3, r.tagsMicro().fn());
        assertEquals(0.5, r.tagsMicro().f1().getAsDouble(), EPS);
        // security, auth, crypto, plus the two hallucinated zero-support tags (logging,
        // input-validation), which score F1 0 and must pull the macro average down.
        assertEquals((4.0 / 6.0 + 2.0 / 3.0 + 0.0 + 0.0 + 0.0) / 5.0, r.tagsMacroF1().getAsDouble(), EPS);
    }

    @Test
    void hallucinatedTagLowersMacroF1() {
        Result honest = AccuracyMetrics.compute(List.of(tags(List.of("auth"), List.of("auth"))));
        Result inventing = AccuracyMetrics.compute(List.of(tags(List.of("auth", "xss"), List.of("auth"))));

        assertEquals(1.0, honest.tagsMacroF1().getAsDouble(), EPS);
        assertEquals(0.5, inventing.tagsMacroF1().getAsDouble(), EPS,
                "an invented zero-support tag counts as F1 0 in the macro average");
    }

    @Test
    void emptyTruthTagSetIsALabelMeaningNoTags() {
        Result r = AccuracyMetrics.compute(List.of(tags(List.of("auth"), List.of())));

        assertEquals(1, r.tagSamples());
        assertEquals(new Counts(0, 1, 0, 0), tag(r, "auth").counts());
    }

    @Test
    void samplesWithoutTagLabelAreExcludedFromTags() {
        Result r = AccuracyMetrics.compute(List.of(new Sample("a", true, true, List.of("auth"), null)));

        assertEquals(0, r.tagSamples());
        assertTrue(r.tags().isEmpty());
        assertEquals(1, r.relevanceSamples());
    }

    @Test
    void tagsAreNormalisedAndNullPredictionTreatedAsEmpty() {
        assertEquals(Set.of("crypto", "auth"),
                AccuracyMetrics.normalise(Arrays.asList(" Crypto ", "AUTH", "crypto", "", "  ", null)));
        assertTrue(AccuracyMetrics.normalise(null).isEmpty());

        Result r = AccuracyMetrics.compute(List.of(
                new Sample("a", false, null, null, List.of("Crypto")),
                tags(List.of("CRYPTO "), List.of("crypto"))));
        assertEquals(new Counts(1, 0, 1, 0), tag(r, "crypto").counts());
    }

    @Test
    void perTagAllCorrectGivesPerfectMicroAndMacro() {
        Result r = AccuracyMetrics.compute(List.of(
                tags(List.of("auth", "security"), List.of("auth", "security"))));

        assertEquals(1.0, r.tagsMicro().f1().getAsDouble(), EPS);
        assertEquals(1.0, r.tagsMacroF1().getAsDouble(), EPS);
    }
}
