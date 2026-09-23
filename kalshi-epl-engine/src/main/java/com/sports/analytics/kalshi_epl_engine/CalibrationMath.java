package com.sports.analytics.kalshi_epl_engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared scoring math for "does this predicted probability match reality"
 * questions - used by both ModelValidationService (checked against historical
 * Understat/Kalshi data) and PredictionLogService (checked against our own
 * live predictions as they resolve), so the two report numbers that mean the
 * same thing and are directly comparable.
 */
public final class CalibrationMath {

    private CalibrationMath() {}

    /** Mean squared error between predicted probability and the 0/1 actual outcome. 0=perfect, 1=worst. */
    public static double brierScore(List<PredictionRecord> predictions) {
        if (predictions.isEmpty()) return 0.0;
        double sum = 0.0;
        for (PredictionRecord p : predictions) {
            double y = p.actualOutcome() ? 1.0 : 0.0;
            double diff = p.predictedProbability() - y;
            sum += diff * diff;
        }
        return sum / predictions.size();
    }

    public static double logLoss(List<PredictionRecord> predictions) {
        if (predictions.isEmpty()) return 0.0;
        double epsilon = 1e-15;
        double sum = 0.0;
        for (PredictionRecord p : predictions) {
            double clamped = Math.min(1 - epsilon, Math.max(epsilon, p.predictedProbability()));
            sum += p.actualOutcome() ? -Math.log(clamped) : -Math.log(1 - clamped);
        }
        return sum / predictions.size();
    }

    public static List<CalibrationBucket> buildCalibrationBuckets(List<PredictionRecord> predictions) {
        int bucketCount = 10;
        double[] sumPredicted = new double[bucketCount];
        int[] sumActual = new int[bucketCount];
        int[] count = new int[bucketCount];

        for (PredictionRecord p : predictions) {
            int bucket = Math.min(bucketCount - 1, (int) (p.predictedProbability() * bucketCount));
            sumPredicted[bucket] += p.predictedProbability();
            sumActual[bucket] += p.actualOutcome() ? 1 : 0;
            count[bucket]++;
        }

        List<CalibrationBucket> result = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            if (count[i] == 0) continue;
            String label = (i * 10) + "-" + ((i + 1) * 10) + "%";
            double avgPredicted = sumPredicted[i] / count[i];
            double actualFrequency = (double) sumActual[i] / count[i];
            result.add(new CalibrationBucket(label, count[i], round4(avgPredicted), round4(actualFrequency)));
        }
        return result;
    }

    public static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
