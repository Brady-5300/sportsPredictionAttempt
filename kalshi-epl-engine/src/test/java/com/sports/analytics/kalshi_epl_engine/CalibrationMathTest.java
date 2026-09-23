package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationMathTest {

    @Test
    void brierScoreIsZeroForPerfectPredictions() {
        // Two perfectly confident and correct predictions -> Brier score 0.
        List<PredictionRecord> predictions = List.of(
            new PredictionRecord("d", "t", "HOME", 1.0, true),
            new PredictionRecord("d", "t", "HOME", 0.0, false)
        );
        assertEquals(0.0, CalibrationMath.brierScore(predictions), 0.0001);
    }

    @Test
    void brierScoreIsWorstForConfidentWrongPredictions() {
        // Fully confident but always wrong -> Brier score 1 (the worst possible).
        List<PredictionRecord> predictions = List.of(
            new PredictionRecord("d", "t", "HOME", 1.0, false),
            new PredictionRecord("d", "t", "HOME", 0.0, true)
        );
        assertEquals(1.0, CalibrationMath.brierScore(predictions), 0.0001);
    }

    @Test
    void logLossIsLowForConfidentCorrectPredictions() {
        List<PredictionRecord> confidentCorrect = List.of(new PredictionRecord("d", "t", "HOME", 0.95, true));
        List<PredictionRecord> confidentWrong = List.of(new PredictionRecord("d", "t", "HOME", 0.95, false));

        assertTrue(CalibrationMath.logLoss(confidentCorrect) < CalibrationMath.logLoss(confidentWrong));
    }

    @Test
    void calibrationBucketsGroupPredictionsByPredictedProbabilityRange() {
        List<PredictionRecord> predictions = List.of(
            new PredictionRecord("d", "t", "HOME", 0.15, true),
            new PredictionRecord("d", "t", "HOME", 0.18, false),
            new PredictionRecord("d", "t", "HOME", 0.85, true)
        );

        List<CalibrationBucket> buckets = CalibrationMath.buildCalibrationBuckets(predictions);

        CalibrationBucket lowBucket = buckets.stream().filter(b -> b.rangeLabel().equals("10-20%")).findFirst().orElseThrow();
        assertEquals(2, lowBucket.count());
        assertEquals(0.5, lowBucket.actualFrequency(), 0.0001); // 1 of 2 happened

        CalibrationBucket highBucket = buckets.stream().filter(b -> b.rangeLabel().equals("80-90%")).findFirst().orElseThrow();
        assertEquals(1, highBucket.count());
        assertEquals(1.0, highBucket.actualFrequency(), 0.0001);
    }
}
