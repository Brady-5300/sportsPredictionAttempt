package com.sports.analytics.kalshi_epl_engine;

/** One historical match-outcome prediction, for calibration analysis. */
public record PredictionRecord(
    String matchDate,
    String title,
    String marketType,
    double predictedProbability,
    boolean actualOutcome
) {
}
